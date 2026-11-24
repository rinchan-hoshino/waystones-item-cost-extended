package dev.rinchan.waystonesitemcost.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for Sable 2.0.x offline storage parsing ({@code *.slvlr} 128B sectors +
 * {@code *.slvls} 4096B sectors + external streaming {@code *.s/<idx>.slvl}).
 */
final class SableOfflineStorageTest {
    private static final double EPS = 1e-4;

    // Exact pose values from production r.-1.2.0.slvls slot 2 (plot_x=0, plot_z=2)
    private static final double PROD_POS_X = -415.21708772012386;
    private static final double PROD_POS_Y = 231.0073288012798;
    private static final double PROD_POS_Z = 1330.4042359800121;
    private static final double PROD_ROT_X = 20_481_033.511329938;
    private static final double PROD_ROT_Y = 135.13647313170512;
    private static final double PROD_ROT_Z = 20_485_107.28324999;
    private static final double PROD_QX = 0.005660655442625284;
    private static final double PROD_QY = 0.7772189974784851;
    private static final double PROD_QZ = 0.008058736100792885;
    private static final double PROD_QW = -0.6291531920433044;

    @Test
    void regionSlotIndexAndPointerBitPackingRoundTrip() {
        // Verify r.-1.2.slvlr header[547] -> (-29, 81)
        SableOfflineStorage.ChunkCoords hc = SableOfflineStorage.holdingChunkForSlot(-1, 2, 547);
        assertEquals(-29, hc.x());
        assertEquals(81, hc.z());

        // Verify SavedSubLevelPointer packing across storageIdx & subLevelIdx
        for (int storageIdx : new int[]{0, 1, 7, 123}) {
            for (int subLevelIdx : new int[]{0, 1, 2, 511, 1023}) {
                int packed = (storageIdx << 16) | (subLevelIdx & 0xFFFF);
                assertEquals(storageIdx, SableOfflineStorage.unpackPointerStorageIndex(packed));
                assertEquals(subLevelIdx, SableOfflineStorage.unpackPointerSubLevelIndex(packed));
            }
        }

        int[] slvlrCoords = SableOfflineStorage.parseSlvlrFilename("r.-1.2.slvlr");
        assertNotNull(slvlrCoords);
        assertEquals(-1, slvlrCoords[0]);
        assertEquals(2, slvlrCoords[1]);

        int[] slvlsCoords = SableOfflineStorage.parseSlvlsFilename("r.-1.2.0.slvls");
        assertNotNull(slvlsCoords);
        assertEquals(-1, slvlsCoords[0]);
        assertEquals(2, slvlsCoords[1]);
        assertEquals(0, slvlsCoords[2]);
    }

    @Test
    void parsesProductionIsomorphicSlvlrAndSlvlsWithOffsetHoldingChunk(@TempDir Path dir) throws IOException {
        // 1. Write r.-1.2.slvlr (128B sectorSize, header[547] = 0x00002001 -> start=32, len=1, off=4096)
        byte[] slvlrPayload = gzip(buildPointersNbt(new int[]{2, 1}));
        byte[] slvlrFile = new byte[4096 + 128];
        ByteBuffer.wrap(slvlrFile).putInt(547 * 4, (32 << 8) | 1);
        ByteBuffer slvlrEntry = ByteBuffer.wrap(slvlrFile, 4096, 128);
        slvlrEntry.putInt(slvlrPayload.length + 1);
        slvlrEntry.put((byte) 0);
        slvlrEntry.put(slvlrPayload);
        Files.write(dir.resolve("r.-1.2.slvlr"), slvlrFile);

        // 2. Write r.-1.2.0.slvls (4096B sectorSize, slot 2 with heavy plot.chunks + production pose)
        byte[] slvlsPayload = gzip(buildSubLevelNbt(
            0, 2,
            PROD_POS_X, PROD_POS_Y, PROD_POS_Z,
            PROD_ROT_X, PROD_ROT_Y, PROD_ROT_Z,
            PROD_QX, PROD_QY, PROD_QZ, PROD_QW,
            true // include heavy plot.chunks list that must be skipped cleanly
        ));
        int sectors = (slvlsPayload.length + 5 + 4095) / 4096;
        byte[] slvlsFile = new byte[(1 + sectors) * 4096];
        ByteBuffer.wrap(slvlsFile).putInt(2 * 4, (1 << 8) | sectors);
        ByteBuffer slvlsEntry = ByteBuffer.wrap(slvlsFile, 4096, sectors * 4096);
        slvlsEntry.putInt(slvlsPayload.length + 1);
        slvlsEntry.put((byte) 0);
        slvlsEntry.put(slvlsPayload);
        Files.write(dir.resolve("r.-1.2.0.slvls"), slvlsFile);

        List<SableOfflineStorage.ScannedSubLevel> scanned = SableOfflineStorage.scanDirectoryReadOnly(
            dir,
            SableOfflineStorageTest::readPointersPureJava,
            SableOfflineStorageTest::readHeaderPureJava
        );

        assertEquals(1, scanned.size());
        SableOfflineStorage.ScannedSubLevel entry = scanned.get(0);
        assertEquals(0, entry.storageIdx());
        assertEquals(2, entry.subLevelIdx());
        assertEquals(0, entry.header().plotX());
        assertEquals(2, entry.header().plotZ());
        assertTrue(entry.fromRegionFile(), "Must resolve holdingChunk from r.-1.2.slvlr");
        assertEquals(new SableOfflineStorage.ChunkCoords(-29, 81), entry.holdingChunk(),
            "Exact holdingChunk from r.-1.2.slvlr header[547] must be (-29, 81)");
        assertEquals(new SableOfflineStorage.ChunkCoords(-26, 83), entry.header().centerChunk(),
            "pose.position centerChunk is (-26, 83), 3 chunks away from holdingChunk (-29, 81)");

        // Project real Waystone [20481033, 127, 20485127] (center +0.5)
        PhysicsPosCore.Vec3d worldPos = entry.header().project(20_481_033.5, 127.5, 20_485_127.5);
        assertNotNull(worldPos);
        assertEquals(-435.129, worldPos.x(), 0.05);
        assertEquals(223.770, worldPos.y(), 0.05);
        assertEquals(1326.143, worldPos.z(), 0.05);
        // Rigid-body rotation must preserve exact Euclidean distance from rotationPoint
        double localDist = new PhysicsPosCore.Vec3d(20_481_033.5, 127.5, 20_485_127.5)
            .distanceTo(new PhysicsPosCore.Vec3d(PROD_ROT_X, PROD_ROT_Y, PROD_ROT_Z));
        double worldDist = worldPos.distanceTo(new PhysicsPosCore.Vec3d(PROD_POS_X, PROD_POS_Y, PROD_POS_Z));
        assertEquals(localDist, worldDist, EPS);
    }

    @Test
    void parsesRealProductionFilesWhenPresentInTmpWmfInspect() {
        Path inspectDir = Path.of("/tmp/wmf-inspect");
        if (!Files.isRegularFile(inspectDir.resolve("r.-1.2.slvlr"))
            || !Files.isRegularFile(inspectDir.resolve("r.-1.2.0.slvls"))) {
            return;
        }
        List<SableOfflineStorage.ScannedSubLevel> scanned = SableOfflineStorage.scanDirectoryReadOnly(
            inspectDir,
            SableOfflineStorageTest::readPointersPureJava,
            SableOfflineStorageTest::readHeaderPureJava
        );
        SableOfflineStorage.ScannedSubLevel idx2 = scanned.stream()
            .filter(s -> s.storageIdx() == 0 && s.subLevelIdx() == 2)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected idx=2 in /tmp/wmf-inspect/r.-1.2.0.slvls"));

        assertTrue(idx2.fromRegionFile());
        assertEquals(0, idx2.header().plotX());
        assertEquals(2, idx2.header().plotZ());
        assertEquals(new SableOfflineStorage.ChunkCoords(-29, 81), idx2.holdingChunk());

        PhysicsPosCore.Vec3d worldPos = idx2.header().project(20_481_033.5, 127.5, 20_485_127.5);
        assertNotNull(worldPos);
        assertEquals(-435.129, worldPos.x(), 0.05);
        assertEquals(223.770, worldPos.y(), 0.05);
        assertEquals(1326.143, worldPos.z(), 0.05);
    }

    @Test
    void streamsExternalSlvlFileAndFallsBackToCenterChunkWithoutSlvlr(@TempDir Path dir) throws IOException {
        // Slot 5 marked external (flags = 16) with no .slvlr file -> must stream r.0.0.0.s/5.slvl and fallback to centerChunk
        byte[] slvlsFile = new byte[8192];
        ByteBuffer.wrap(slvlsFile).putInt(5 * 4, (1 << 8) | 1);
        ByteBuffer entryHeader = ByteBuffer.wrap(slvlsFile, 4096, 4096);
        entryHeader.putInt(1); // dataLen = 1 (flags only, external payload)
        entryHeader.put((byte) SableOfflineStorage.EXTERNAL_FLAG_MASK);
        Files.write(dir.resolve("r.0.0.0.slvls"), slvlsFile);

        Path extDir = dir.resolve("r.0.0.0.s");
        Files.createDirectories(extDir);
        byte[] extPayload = gzip(buildSubLevelNbt(
            3, 4,
            320.5, 90.0, -160.5,
            20_480_100.5, 70.5, 20_480_200.5,
            0.0, 0.0, 0.0, 1.0,
            true
        ));
        Files.write(extDir.resolve("5.slvl"), extPayload);

        List<SableOfflineStorage.ScannedSubLevel> scanned = SableOfflineStorage.scanDirectoryReadOnly(
            dir,
            SableOfflineStorageTest::readPointersPureJava,
            SableOfflineStorageTest::readHeaderPureJava
        );
        assertEquals(1, scanned.size());
        SableOfflineStorage.ScannedSubLevel s = scanned.get(0);
        assertFalse(s.fromRegionFile(), "Without .slvlr, must fall back to centerChunk");
        assertEquals(new SableOfflineStorage.ChunkCoords(20, -11), s.holdingChunk());
        assertEquals(3, s.header().plotX());
        assertEquals(4, s.header().plotZ());
    }

    @Test
    void streamsUuidAndWorldBoundsAndComputesClampedChunkBounds(@TempDir Path dir) throws IOException {
        UUID shipUuid = UUID.fromString("12345678-9abc-def0-1234-56789abcdef0");
        // 84x68 airship centered near (2400.5, 70.5, -2000.5) -> spans 6x5 chunks
        double posX = 2400.5;
        double posY = 70.5;
        double posZ = -2000.5;
        double minX = posX - 42.0; // 2358.5 -> chunk 147
        double maxX = posX + 42.0; // 2442.5 -> chunk 152 (6 chunks: 147..152)
        double minZ = posZ - 34.0; // -2034.5 -> chunk -128
        double maxZ = posZ + 34.0; // -1966.5 -> chunk -123 (6 chunks with +-1 pad: -128..-123)

        byte[] slvlrPayload = gzip(buildPointersNbt(new int[]{0, 1}));
        byte[] slvlrFile = new byte[4096 + 128];
        ByteBuffer.wrap(slvlrFile).putInt(0, (32 << 8) | 1);
        ByteBuffer slvlrEntry = ByteBuffer.wrap(slvlrFile, 4096, 128);
        slvlrEntry.putInt(slvlrPayload.length + 1);
        slvlrEntry.put((byte) 0);
        slvlrEntry.put(slvlrPayload);
        Files.write(dir.resolve("r.0.0.slvlr"), slvlrFile);

        byte[] slvlsPayload = gzip(buildSubLevelNbt(
            shipUuid,
            1, 2,
            posX, posY, posZ,
            20_480_100.5, 70.5, 20_480_200.5,
            0.0, 0.0, 0.0, 1.0,
            true,
            true, minX, minZ, maxX, maxZ
        ));
        int sectors = (slvlsPayload.length + 5 + 4095) / 4096;
        byte[] slvlsFile = new byte[(1 + sectors) * 4096];
        ByteBuffer.wrap(slvlsFile).putInt(0, (1 << 8) | sectors);
        ByteBuffer slvlsEntry = ByteBuffer.wrap(slvlsFile, 4096, sectors * 4096);
        slvlsEntry.putInt(slvlsPayload.length + 1);
        slvlsEntry.put((byte) 0);
        slvlsEntry.put(slvlsPayload);
        Files.write(dir.resolve("r.0.0.0.slvls"), slvlsFile);

        List<SableOfflineStorage.ScannedSubLevel> scanned = SableOfflineStorage.scanDirectoryReadOnly(
            dir,
            SableOfflineStorageTest::readPointersPureJava,
            SableOfflineStorageTest::readHeaderPureJava
        );
        assertEquals(1, scanned.size());
        SableOfflineStorage.OfflineHeader header = scanned.get(0).header();
        assertEquals(shipUuid, header.subLevelUuid());
        assertTrue(header.hasWorldBounds());
        assertEquals(minX, header.minX(), EPS);
        assertEquals(minZ, header.minZ(), EPS);
        assertEquals(maxX, header.maxX(), EPS);
        assertEquals(maxZ, header.maxZ(), EPS);

        SableOfflineStorage.ChunkBounds bounds = header.worldChunkBounds();
        assertEquals(((int) Math.floor(minX - 1.0)) >> 4, bounds.minChunkX());
        assertEquals(((int) Math.floor(maxX + 1.0)) >> 4, bounds.maxChunkX());
        assertEquals(((int) Math.floor(minZ - 1.0)) >> 4, bounds.minChunkZ());
        assertEquals(((int) Math.floor(maxZ + 1.0)) >> 4, bounds.maxChunkZ());

        // Fallback when world_bounds is absent -> DEFAULT_FALLBACK_RADIUS (2) around centerChunk
        SableOfflineStorage.OfflineHeader noBounds = new SableOfflineStorage.OfflineHeader(
            1, 2, posX, posY, posZ, 0, 0, 0, 1, 0, 0, 0
        );
        SableOfflineStorage.ChunkBounds fallback = noBounds.worldChunkBounds();
        assertEquals(noBounds.centerChunk().x() - 2, fallback.minChunkX());
        assertEquals(noBounds.centerChunk().x() + 2, fallback.maxChunkX());
        assertEquals(noBounds.centerChunk().z() - 2, fallback.minChunkZ());
        assertEquals(noBounds.centerChunk().z() + 2, fallback.maxChunkZ());

        // Clamping when AABB exceeds MAX_CLAMP_RADIUS (8)
        SableOfflineStorage.OfflineHeader huge = new SableOfflineStorage.OfflineHeader(
            shipUuid, 1, 2, posX, posY, posZ, 0, 0, 0, 1, 0, 0, 0,
            true, posX - 500.0, posZ - 500.0, posX + 500.0, posZ + 500.0
        );
        SableOfflineStorage.ChunkBounds clamped = huge.worldChunkBounds();
        assertEquals(huge.centerChunk().x() - 8, clamped.minChunkX());
        assertEquals(huge.centerChunk().x() + 8, clamped.maxChunkX());
        assertEquals(huge.centerChunk().z() - 8, clamped.minChunkZ());
        assertEquals(huge.centerChunk().z() + 8, clamped.maxChunkZ());
    }

    // ---- Pure-Java NBT encoding/streaming helpers for logic-module tests ----

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(raw);
        }
        return baos.toByteArray();
    }

    private static byte[] buildPointersNbt(int[] pointers) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeByte(10); // root compound
        out.writeUTF("");
        out.writeByte(11); // int array
        out.writeUTF("pointers");
        out.writeInt(pointers.length);
        for (int p : pointers) {
            out.writeInt(p);
        }
        out.writeByte(0); // end root
        return baos.toByteArray();
    }

    private static byte[] buildSubLevelNbt(
        int plotX, int plotZ,
        double posX, double posY, double posZ,
        double rotX, double rotY, double rotZ,
        double qx, double qy, double qz, double qw,
        boolean includeHeavyChunks
    ) throws IOException {
        return buildSubLevelNbt(
            null, plotX, plotZ, posX, posY, posZ, rotX, rotY, rotZ, qx, qy, qz, qw,
            includeHeavyChunks, false, 0.0, 0.0, 0.0, 0.0
        );
    }

    private static byte[] buildSubLevelNbt(
        UUID subLevelUuid,
        int plotX, int plotZ,
        double posX, double posY, double posZ,
        double rotX, double rotY, double rotZ,
        double qx, double qy, double qz, double qw,
        boolean includeHeavyChunks,
        boolean includeWorldBounds,
        double minX, double minZ, double maxX, double maxZ
    ) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeByte(10); // root compound
        out.writeUTF("");

        if (subLevelUuid != null) {
            out.writeByte(11); // int array
            out.writeUTF("uuid");
            out.writeInt(4);
            long most = subLevelUuid.getMostSignificantBits();
            long least = subLevelUuid.getLeastSignificantBits();
            out.writeInt((int) (most >>> 32));
            out.writeInt((int) most);
            out.writeInt((int) (least >>> 32));
            out.writeInt((int) least);
        }

        if (includeWorldBounds) {
            out.writeByte(10);
            out.writeUTF("world_bounds");
            out.writeByte(6); out.writeUTF("minX"); out.writeDouble(minX);
            out.writeByte(6); out.writeUTF("minY"); out.writeDouble(posY - 2.0);
            out.writeByte(6); out.writeUTF("minZ"); out.writeDouble(minZ);
            out.writeByte(6); out.writeUTF("maxX"); out.writeDouble(maxX);
            out.writeByte(6); out.writeUTF("maxY"); out.writeDouble(posY + 4.0);
            out.writeByte(6); out.writeUTF("maxZ"); out.writeDouble(maxZ);
            out.writeByte(0); // end world_bounds
        }

        // plot compound
        out.writeByte(10);
        out.writeUTF("plot");
        if (includeHeavyChunks) {
            out.writeByte(9); // list of compounds
            out.writeUTF("chunks");
            out.writeByte(10); // element type: compound
            out.writeInt(4);
            for (int i = 0; i < 4; i++) {
                out.writeByte(7); // byte array
                out.writeUTF("blocks");
                out.writeInt(1024);
                out.write(new byte[1024]);
                out.writeByte(0);
            }
        }
        out.writeByte(3); out.writeUTF("plot_x"); out.writeInt(plotX);
        out.writeByte(3); out.writeUTF("plot_z"); out.writeInt(plotZ);
        out.writeByte(0); // end plot

        // pose compound
        out.writeByte(10);
        out.writeUTF("pose");
        writeVec3dTag(out, "position", posX, posY, posZ);
        writeVec3dTag(out, "rotation_point", rotX, rotY, rotZ);
        out.writeByte(10); out.writeUTF("orientation");
        out.writeByte(6); out.writeUTF("x"); out.writeDouble(qx);
        out.writeByte(6); out.writeUTF("y"); out.writeDouble(qy);
        out.writeByte(6); out.writeUTF("z"); out.writeDouble(qz);
        out.writeByte(6); out.writeUTF("w"); out.writeDouble(qw);
        out.writeByte(0); // end orientation
        out.writeByte(0); // end pose

        out.writeByte(0); // end root
        return baos.toByteArray();
    }

    private static void writeVec3dTag(DataOutputStream out, String name, double x, double y, double z) throws IOException {
        out.writeByte(10);
        out.writeUTF(name);
        out.writeByte(6); out.writeUTF("x"); out.writeDouble(x);
        out.writeByte(6); out.writeUTF("y"); out.writeDouble(y);
        out.writeByte(6); out.writeUTF("z"); out.writeDouble(z);
        out.writeByte(0);
    }

    private static int[] readPointersPureJava(InputStream in, boolean gzip) throws IOException {
        try (InputStream src = gzip ? new BufferedInputStream(new GZIPInputStream(in)) : in) {
            DataInputStream dis = new DataInputStream(src);
            byte rootType = dis.readByte();
            if (rootType != 10) return null;
            skipString(dis);
            while (true) {
                byte t = dis.readByte();
                if (t == 0) return null;
                String name = dis.readUTF();
                if (t == 11 && "pointers".equals(name)) {
                    int len = dis.readInt();
                    int[] arr = new int[len];
                    for (int i = 0; i < len; i++) {
                        arr[i] = dis.readInt();
                    }
                    return arr;
                }
                skipPayload(dis, t);
            }
        }
    }

    private static SableOfflineStorage.OfflineHeader readHeaderPureJava(InputStream in, boolean gzip) throws IOException {
        try (InputStream src = gzip ? new BufferedInputStream(new GZIPInputStream(in)) : in) {
            DataInputStream dis = new DataInputStream(src);
            if (dis.readByte() != 10) return null;
            skipString(dis);
            Integer plotX = null, plotZ = null;
            UUID subLevelUuid = null;
            double posX = 0, posY = 0, posZ = 0;
            double rotX = 0, rotY = 0, rotZ = 0;
            double qx = 0, qy = 0, qz = 0, qw = 1.0;
            boolean hasPos = false, hasRot = false;
            boolean hasMinX = false, hasMinZ = false, hasMaxX = false, hasMaxZ = false;
            double minX = 0, minZ = 0, maxX = 0, maxZ = 0;

            while (true) {
                byte t = dis.readByte();
                if (t == 0) break;
                String name = dis.readUTF();
                if (t == 11 && "uuid".equals(name)) {
                    int len = dis.readInt();
                    if (len == 4) {
                        int a = dis.readInt(), b = dis.readInt(), c = dis.readInt(), d = dis.readInt();
                        long most = ((long) a << 32) | (b & 0xFFFFFFFFL);
                        long least = ((long) c << 32) | (d & 0xFFFFFFFFL);
                        subLevelUuid = new UUID(most, least);
                    } else {
                        dis.skipNBytes((long) len * 4L);
                    }
                } else if (t == 10 && "world_bounds".equals(name)) {
                    while (true) {
                        byte pt = dis.readByte();
                        if (pt == 0) break;
                        String pname = dis.readUTF();
                        switch (pname) {
                            case "minX" -> { minX = readNumber(dis, pt); hasMinX = true; }
                            case "minZ" -> { minZ = readNumber(dis, pt); hasMinZ = true; }
                            case "maxX" -> { maxX = readNumber(dis, pt); hasMaxX = true; }
                            case "maxZ" -> { maxZ = readNumber(dis, pt); hasMaxZ = true; }
                            default -> skipPayload(dis, pt);
                        }
                    }
                } else if (t == 10 && "plot".equals(name)) {
                    while (true) {
                        byte pt = dis.readByte();
                        if (pt == 0) break;
                        String pname = dis.readUTF();
                        if ("plot_x".equals(pname)) plotX = (int) readNumber(dis, pt);
                        else if ("plot_z".equals(pname)) plotZ = (int) readNumber(dis, pt);
                        else skipPayload(dis, pt);
                    }
                } else if (t == 10 && "pose".equals(name)) {
                    while (true) {
                        byte pt = dis.readByte();
                        if (pt == 0) break;
                        String pname = dis.readUTF();
                        if (pt == 10 && "position".equals(pname)) {
                            hasPos = true;
                            double[] v = readVecCompound(dis);
                            posX = v[0]; posY = v[1]; posZ = v[2];
                        } else if (pt == 10 && "rotation_point".equals(pname)) {
                            hasRot = true;
                            double[] v = readVecCompound(dis);
                            rotX = v[0]; rotY = v[1]; rotZ = v[2];
                        } else if (pt == 10 && "orientation".equals(pname)) {
                            double[] v = readVecCompound(dis);
                            qx = v[0]; qy = v[1]; qz = v[2]; qw = v[3];
                        } else {
                            skipPayload(dis, pt);
                        }
                    }
                } else {
                    skipPayload(dis, t);
                }
            }
            if (plotX == null || plotZ == null || !hasPos || !hasRot) {
                return null;
            }
            boolean hasWorldBounds = hasMinX && hasMinZ && hasMaxX && hasMaxZ;
            return new SableOfflineStorage.OfflineHeader(
                subLevelUuid,
                plotX, plotZ, posX, posY, posZ, qx, qy, qz, qw, rotX, rotY, rotZ,
                hasWorldBounds, minX, minZ, maxX, maxZ
            );
        }
    }

    private static double[] readVecCompound(DataInputStream dis) throws IOException {
        double[] v = new double[]{0, 0, 0, 1};
        while (true) {
            byte t = dis.readByte();
            if (t == 0) break;
            String k = dis.readUTF();
            switch (k) {
                case "x" -> v[0] = readNumber(dis, t);
                case "y" -> v[1] = readNumber(dis, t);
                case "z" -> v[2] = readNumber(dis, t);
                case "w" -> v[3] = readNumber(dis, t);
                default -> skipPayload(dis, t);
            }
        }
        return v;
    }

    private static double readNumber(DataInputStream dis, byte type) throws IOException {
        return switch (type) {
            case 1 -> dis.readByte();
            case 2 -> dis.readShort();
            case 3 -> dis.readInt();
            case 4 -> dis.readLong();
            case 5 -> dis.readFloat();
            case 6 -> dis.readDouble();
            default -> { skipPayload(dis, type); yield 0.0; }
        };
    }

    private static void skipString(DataInputStream dis) throws IOException {
        int len = dis.readUnsignedShort();
        dis.skipNBytes(len);
    }

    private static void skipPayload(DataInputStream dis, byte type) throws IOException {
        switch (type) {
            case 1 -> dis.skipNBytes(1);
            case 2 -> dis.skipNBytes(2);
            case 3, 5 -> dis.skipNBytes(4);
            case 4, 6 -> dis.skipNBytes(8);
            case 7 -> dis.skipNBytes(dis.readInt());
            case 8 -> skipString(dis);
            case 9 -> {
                byte elemType = dis.readByte();
                int count = dis.readInt();
                for (int i = 0; i < count; i++) skipPayload(dis, elemType);
            }
            case 10 -> {
                while (true) {
                    byte ct = dis.readByte();
                    if (ct == 0) break;
                    skipString(dis);
                    skipPayload(dis, ct);
                }
            }
            case 11 -> dis.skipNBytes((long) dis.readInt() * 4L);
            case 12 -> dis.skipNBytes((long) dis.readInt() * 8L);
            default -> throw new IOException("Unknown NBT tag type: " + type);
        }
    }
}
