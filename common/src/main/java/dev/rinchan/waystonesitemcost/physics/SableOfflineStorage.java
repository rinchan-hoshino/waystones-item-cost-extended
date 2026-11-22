package dev.rinchan.waystonesitemcost.physics;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java read-only scanner for Sable 2.0.x offline sub-level storage files:
 * - {@code r.<rx>.<rz>.slvlr} (SubLevelRegionFile: 128-byte sectors, 4096-byte 1024-slot header,
 *   slot index = {@code localX | (localZ << 5)}, NBT {@code pointers: int[]} packing
 *   {@code (storageIdx << 16) | (subLevelIdx & 0xFFFF)})
 * - {@code r.<rx>.<rz>.<storageIdx>.slvls} (SubLevelStorageFile: 4096-byte sectors, 4096-byte 1024-slot header,
 *   optional external {@code <stem>.s/<idx>.slvl} when {@code (flags & 16) != 0})
 *
 * Strictly read-only ({@link StandardOpenOption#READ}): never acquires write locks,
 * never creates files, and never mutates Sable's storage state.
 */
public final class SableOfflineStorage {
    public static final int SLVLR_SECTOR_SIZE = 128;
    public static final int SLVLS_SECTOR_SIZE = 4096;
    public static final int HEADER_SLOTS = 1024;
    public static final int HEADER_BYTES = HEADER_SLOTS * 4;
    public static final int EXTERNAL_FLAG_MASK = 16;
    public static final int REGION_LOG_SIDE = 5;
    private static final int STREAM_BUFFER_SIZE = 16 * 1024;

    private SableOfflineStorage() {
    }

    public record RegionPointerKey(int rx, int rz, int storageIdx, int subLevelIdx) {
    }

    public record ChunkCoords(int x, int z) {
    }

    public record OfflineHeader(
        int plotX,
        int plotZ,
        double posX,
        double posY,
        double posZ,
        double qx,
        double qy,
        double qz,
        double qw,
        double rotX,
        double rotY,
        double rotZ
    ) {
        public PhysicsPosCore.Vec3d project(double x, double y, double z) {
            return PhysicsPosCore.transformPose(
                new PhysicsPosCore.Vec3d(x, y, z),
                new PhysicsPosCore.Vec3d(posX, posY, posZ),
                qx, qy, qz, qw,
                new PhysicsPosCore.Vec3d(rotX, rotY, rotZ)
            );
        }

        public ChunkCoords centerChunk() {
            int bx = (int) Math.floor(posX);
            int bz = (int) Math.floor(posZ);
            return new ChunkCoords(bx >> 4, bz >> 4);
        }
    }

    public record ScannedSubLevel(
        OfflineHeader header,
        ChunkCoords holdingChunk,
        boolean fromRegionFile,
        int storageIdx,
        int subLevelIdx
    ) {
    }

    @FunctionalInterface
    public interface PointerArrayReader {
        int[] read(InputStream in, boolean gzip) throws IOException;
    }

    @FunctionalInterface
    public interface HeaderReader {
        OfflineHeader read(InputStream in, boolean gzip) throws IOException;
    }

    /**
     * Parses {@code r.<rx>.<rz>.slvlr} into {@code [rx, rz]}, or returns null if malformed.
     */
    public static int[] parseSlvlrFilename(String fileName) {
        if (fileName == null || !fileName.startsWith("r.") || !fileName.endsWith(".slvlr")) {
            return null;
        }
        String body = fileName.substring(2, fileName.length() - ".slvlr".length());
        int dot = body.indexOf('.');
        if (dot <= 0 || dot >= body.length() - 1 || body.indexOf('.', dot + 1) >= 0) {
            return null;
        }
        try {
            int rx = Integer.parseInt(body.substring(0, dot));
            int rz = Integer.parseInt(body.substring(dot + 1));
            return new int[]{rx, rz};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Parses {@code r.<rx>.<rz>.<storageIdx>.slvls} into {@code [rx, rz, storageIdx]}, or returns null.
     */
    public static int[] parseSlvlsFilename(String fileName) {
        if (fileName == null || !fileName.startsWith("r.") || !fileName.endsWith(".slvls")) {
            return null;
        }
        String body = fileName.substring(2, fileName.length() - ".slvls".length());
        int dot1 = body.indexOf('.');
        if (dot1 <= 0) {
            return null;
        }
        int dot2 = body.indexOf('.', dot1 + 1);
        if (dot2 <= dot1 + 1 || dot2 >= body.length() - 1 || body.indexOf('.', dot2 + 1) >= 0) {
            return null;
        }
        try {
            int rx = Integer.parseInt(body.substring(0, dot1));
            int rz = Integer.parseInt(body.substring(dot1 + 1, dot2));
            int storageIdx = Integer.parseInt(body.substring(dot2 + 1));
            return new int[]{rx, rz, storageIdx};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Inverse of Sable's {@code SubLevelRegionFile.getIndex(localX, localZ) = localX | (localZ << 5)}.
     */
    public static ChunkCoords holdingChunkForSlot(int rx, int rz, int slotIndex) {
        int localX = slotIndex & 31;
        int localZ = (slotIndex >>> REGION_LOG_SIDE) & 31;
        return new ChunkCoords((rx << REGION_LOG_SIDE) + localX, (rz << REGION_LOG_SIDE) + localZ);
    }

    /**
     * Matches Sable's {@code SavedSubLevelPointer.unpack(int)}.
     */
    public static int unpackPointerStorageIndex(int packed) {
        return (short) (packed >> 16);
    }

    /**
     * Matches Sable's {@code SavedSubLevelPointer.unpack(int)}.
     */
    public static int unpackPointerSubLevelIndex(int packed) {
        return (short) (packed & 0xFFFF);
    }

    /**
     * Scans all {@code r.<rx>.<rz>.slvlr} region pointer files in {@code folder} read-only
     * and returns the exact mapping {@code (rx, rz, storageIdx, subLevelIdx) -> holdingChunk}.
     */
    public static Map<RegionPointerKey, ChunkCoords> scanRegionFilesReadOnly(
        Path folder,
        PointerArrayReader pointerReader
    ) {
        Map<RegionPointerKey, ChunkCoords> map = new HashMap<>();
        if (folder == null || pointerReader == null || !Files.isDirectory(folder)) {
            return map;
        }
        List<Path> files;
        try (var stream = Files.list(folder)) {
            files = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".slvlr"))
                .toList();
        } catch (IOException ignored) {
            return map;
        }
        for (Path slvlrFile : files) {
            scanSingleSlvlrFile(slvlrFile, folder, pointerReader, map);
        }
        return map;
    }

    /**
     * Scans a Sable sublevels directory read-only:
     * 1. Reads lightweight {@code *.slvlr} files to map each sub-level slot to its exact Holding ChunkPos.
     * 2. Streams {@code *.slvls} (and external {@code *.s/<idx>.slvl}) headers via {@code headerReader}.
     */
    public static List<ScannedSubLevel> scanDirectoryReadOnly(
        Path folder,
        PointerArrayReader pointerReader,
        HeaderReader headerReader
    ) {
        List<ScannedSubLevel> results = new ArrayList<>();
        if (folder == null || headerReader == null || !Files.isDirectory(folder)) {
            return results;
        }
        Map<RegionPointerKey, ChunkCoords> holdingMap = scanRegionFilesReadOnly(folder, pointerReader);

        List<Path> slvlsFiles;
        try (var stream = Files.list(folder)) {
            slvlsFiles = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".slvls"))
                .toList();
        } catch (IOException ignored) {
            return results;
        }
        for (Path slvlsFile : slvlsFiles) {
            scanSingleSlvlsFile(slvlsFile, folder, holdingMap, headerReader, results);
        }
        return results;
    }

    private static void scanSingleSlvlrFile(
        Path slvlrFile,
        Path folder,
        PointerArrayReader pointerReader,
        Map<RegionPointerKey, ChunkCoords> outMap
    ) {
        String fileName = slvlrFile.getFileName().toString();
        int[] regionCoords = parseSlvlrFilename(fileName);
        if (regionCoords == null) {
            return;
        }
        int rx = regionCoords[0];
        int rz = regionCoords[1];
        String stem = fileName.substring(0, fileName.length() - ".slvlr".length());
        Path externalDir = folder.resolve(stem + ".s");

        try (FileChannel channel = FileChannel.open(slvlrFile, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < HEADER_BYTES) {
                return;
            }
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            if (!readFully(channel, header, 0L)) {
                return;
            }
            header.flip();
            for (int slot = 0; slot < HEADER_SLOTS; slot++) {
                int span = header.getInt(slot * 4);
                int start = (span >>> 8) & 0xFFFFFF;
                int length = span & 0xFF;
                if (start <= 0 || length <= 0) {
                    continue;
                }
                long offset = (long) start * SLVLR_SECTOR_SIZE;
                long byteLen = (long) length * SLVLR_SECTOR_SIZE;
                if (offset + 5 > fileSize) {
                    continue;
                }
                int toRead = (int) Math.min(byteLen, fileSize - offset);
                ByteBuffer sectorBuf = ByteBuffer.allocate(toRead);
                if (!readFully(channel, sectorBuf, offset)) {
                    continue;
                }
                sectorBuf.flip();
                if (sectorBuf.remaining() < 5) {
                    continue;
                }
                int dataLen = sectorBuf.getInt();
                byte flags = sectorBuf.get();
                if (dataLen <= 0) {
                    continue;
                }
                int[] pointers = readPointersEntry(sectorBuf, dataLen - 1, flags, externalDir, slot, pointerReader);
                if (pointers == null || pointers.length == 0) {
                    continue;
                }
                ChunkCoords holdingChunk = holdingChunkForSlot(rx, rz, slot);
                for (int packed : pointers) {
                    int storageIdx = unpackPointerStorageIndex(packed);
                    int subLevelIdx = unpackPointerSubLevelIndex(packed);
                    outMap.put(new RegionPointerKey(rx, rz, storageIdx, subLevelIdx), holdingChunk);
                }
            }
        } catch (Throwable ignored) {
            // Skip unreadable or concurrently rotated region file gracefully
        }
    }

    private static void scanSingleSlvlsFile(
        Path slvlsFile,
        Path folder,
        Map<RegionPointerKey, ChunkCoords> holdingMap,
        HeaderReader headerReader,
        List<ScannedSubLevel> results
    ) {
        String fileName = slvlsFile.getFileName().toString();
        String stem = fileName.substring(0, fileName.length() - ".slvls".length());
        Path externalDir = folder.resolve(stem + ".s");
        int[] parsed = parseSlvlsFilename(fileName);

        try (FileChannel channel = FileChannel.open(slvlsFile, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < HEADER_BYTES) {
                return;
            }
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            if (!readFully(channel, header, 0L)) {
                return;
            }
            header.flip();
            for (int idx = 0; idx < HEADER_SLOTS; idx++) {
                int span = header.getInt(idx * 4);
                int start = (span >>> 8) & 0xFFFFFF;
                int length = span & 0xFF;
                if (start <= 0 || length <= 0) {
                    continue;
                }
                long offset = (long) start * SLVLS_SECTOR_SIZE;
                long byteLen = (long) length * SLVLS_SECTOR_SIZE;
                if (offset + 5 > fileSize) {
                    continue;
                }
                int toRead = (int) Math.min(byteLen, fileSize - offset);
                ByteBuffer sectorBuf = ByteBuffer.allocate(toRead);
                if (!readFully(channel, sectorBuf, offset)) {
                    continue;
                }
                sectorBuf.flip();
                if (sectorBuf.remaining() < 5) {
                    continue;
                }
                int dataLen = sectorBuf.getInt();
                byte flags = sectorBuf.get();
                if (dataLen <= 0) {
                    continue;
                }
                OfflineHeader parsedHeader = readHeaderEntry(sectorBuf, dataLen - 1, flags, externalDir, idx, headerReader);
                if (parsedHeader == null) {
                    continue;
                }
                ChunkCoords exactHolding = null;
                int storageIdx = parsed != null ? parsed[2] : -1;
                if (parsed != null && !holdingMap.isEmpty()) {
                    exactHolding = holdingMap.get(new RegionPointerKey(parsed[0], parsed[1], parsed[2], idx));
                }
                boolean fromRegion = exactHolding != null;
                ChunkCoords holdingChunk = fromRegion ? exactHolding : parsedHeader.centerChunk();
                results.add(new ScannedSubLevel(parsedHeader, holdingChunk, fromRegion, storageIdx, idx));
            }
        } catch (Throwable ignored) {
            // Skip unreadable or concurrently rotated storage file gracefully
        }
    }

    private static int[] readPointersEntry(
        ByteBuffer sectorBuf,
        int payloadLen,
        byte flags,
        Path externalDir,
        int slot,
        PointerArrayReader pointerReader
    ) {
        try {
            if ((flags & EXTERNAL_FLAG_MASK) != 0) {
                Path extFile = externalDir.resolve(slot + ".slvl");
                if (!Files.isRegularFile(extFile)) {
                    return null;
                }
                try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(extFile), STREAM_BUFFER_SIZE)) {
                    boolean gzip = peekIsGzip(in);
                    return pointerReader.read(in, gzip);
                }
            }
            if (payloadLen < 2 || payloadLen > sectorBuf.remaining()) {
                return null;
            }
            byte[] arr = sectorBuf.array();
            int off = sectorBuf.arrayOffset() + sectorBuf.position();
            boolean gzip = (arr[off] == 0x1f && arr[off + 1] == (byte) 0x8b);
            try (InputStream in = new ByteArrayInputStream(arr, off, payloadLen)) {
                return pointerReader.read(in, gzip);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static OfflineHeader readHeaderEntry(
        ByteBuffer sectorBuf,
        int payloadLen,
        byte flags,
        Path externalDir,
        int idx,
        HeaderReader headerReader
    ) {
        try {
            if ((flags & EXTERNAL_FLAG_MASK) != 0) {
                Path extFile = externalDir.resolve(idx + ".slvl");
                if (!Files.isRegularFile(extFile)) {
                    return null;
                }
                // Stream directly from disk without Files.readAllBytes to avoid multi-MB heap spikes
                try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(extFile), STREAM_BUFFER_SIZE)) {
                    boolean gzip = peekIsGzip(in);
                    return headerReader.read(in, gzip);
                }
            }
            if (payloadLen < 2 || payloadLen > sectorBuf.remaining()) {
                return null;
            }
            byte[] arr = sectorBuf.array();
            int off = sectorBuf.arrayOffset() + sectorBuf.position();
            boolean gzip = (arr[off] == 0x1f && arr[off + 1] == (byte) 0x8b);
            try (InputStream in = new ByteArrayInputStream(arr, off, payloadLen)) {
                return headerReader.read(in, gzip);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean peekIsGzip(BufferedInputStream in) throws IOException {
        in.mark(2);
        int b0 = in.read();
        int b1 = in.read();
        in.reset();
        return b0 == 0x1f && b1 == 0x8b;
    }

    private static boolean readFully(FileChannel channel, ByteBuffer buf, long pos) throws IOException {
        long cursor = pos;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, cursor);
            if (n < 0) {
                return false;
            }
            cursor += n;
        }
        return true;
    }
}
