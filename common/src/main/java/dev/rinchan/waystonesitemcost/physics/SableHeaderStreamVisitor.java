package dev.rinchan.waystonesitemcost.physics;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.nbt.TagType;

/**
 * Zero-allocation streaming NBT visitors for Sable offline sub-level files.
 *
 * {@link #headerVisitor()} extracts only top-level {@code plot.{plot_x, plot_z}} and
 * {@code pose.{position, orientation, rotation_point}}, returning {@link EntryResult#SKIP}
 * immediately on {@code plot.chunks} and every other heavy subtree so multi-MB airship
 * block palettes are never allocated on the heap.
 */
public final class SableHeaderStreamVisitor implements StreamTagVisitor {
    private static final int SECTION_NONE = 0;
    private static final int SECTION_PLOT = 1;
    private static final int SECTION_POSE = 2;

    private static final int SUB_NONE = 0;
    private static final int SUB_POSITION = 1;
    private static final int SUB_ROTATION_POINT = 2;
    private static final int SUB_ORIENTATION = 3;

    private static final int FIELD_NONE = 0;
    private static final int FIELD_PLOT_X = 1;
    private static final int FIELD_PLOT_Z = 2;
    private static final int FIELD_POS_X = 3;
    private static final int FIELD_POS_Y = 4;
    private static final int FIELD_POS_Z = 5;
    private static final int FIELD_ROT_X = 6;
    private static final int FIELD_ROT_Y = 7;
    private static final int FIELD_ROT_Z = 8;
    private static final int FIELD_ORI_X = 9;
    private static final int FIELD_ORI_Y = 10;
    private static final int FIELD_ORI_Z = 11;
    private static final int FIELD_ORI_W = 12;

    private int depth;
    private int section = SECTION_NONE;
    private int subSection = SUB_NONE;
    private int field = FIELD_NONE;

    private boolean donePlot;
    private boolean donePose;

    private boolean hasPlotX;
    private boolean hasPlotZ;
    private boolean hasPosition;
    private boolean hasRotationPoint;

    private int plotX;
    private int plotZ;
    private double posX;
    private double posY;
    private double posZ;
    private double rotX;
    private double rotY;
    private double rotZ;
    private double qx;
    private double qy;
    private double qz;
    private double qw = 1.0;

    public SableOfflineStorage.OfflineHeader buildHeader() {
        if (!hasPlotX || !hasPlotZ || !hasPosition || !hasRotationPoint) {
            return null;
        }
        return new SableOfflineStorage.OfflineHeader(
            plotX, plotZ, posX, posY, posZ, qx, qy, qz, qw, rotX, rotY, rotZ
        );
    }

    @Override
    public ValueResult visitRootEntry(TagType<?> type) {
        if (type == CompoundTag.TYPE) {
            depth = 1;
            return ValueResult.CONTINUE;
        }
        return ValueResult.HALT;
    }

    @Override
    public EntryResult visitEntry(TagType<?> type) {
        return EntryResult.ENTER;
    }

    @Override
    public EntryResult visitEntry(TagType<?> type, String name) {
        if (depth == 1) {
            if (type == CompoundTag.TYPE) {
                if ("plot".equals(name)) {
                    depth = 2;
                    section = SECTION_PLOT;
                    return EntryResult.ENTER;
                }
                if ("pose".equals(name)) {
                    depth = 2;
                    section = SECTION_POSE;
                    return EntryResult.ENTER;
                }
            }
            return EntryResult.SKIP;
        }

        if (depth == 2 && section == SECTION_PLOT) {
            if (type != CompoundTag.TYPE && type != ListTag.TYPE) {
                if ("plot_x".equals(name)) {
                    field = FIELD_PLOT_X;
                    return EntryResult.ENTER;
                }
                if ("plot_z".equals(name)) {
                    field = FIELD_PLOT_Z;
                    return EntryResult.ENTER;
                }
            }
            // Zero-allocation skip for plot.chunks, neoforge:attachments, etc.
            return EntryResult.SKIP;
        }

        if (depth == 2 && section == SECTION_POSE) {
            if (type == CompoundTag.TYPE) {
                if ("position".equals(name)) {
                    depth = 3;
                    subSection = SUB_POSITION;
                    hasPosition = true;
                    return EntryResult.ENTER;
                }
                if ("rotation_point".equals(name)) {
                    depth = 3;
                    subSection = SUB_ROTATION_POINT;
                    hasRotationPoint = true;
                    return EntryResult.ENTER;
                }
                if ("orientation".equals(name)) {
                    depth = 3;
                    subSection = SUB_ORIENTATION;
                    return EntryResult.ENTER;
                }
            }
            return EntryResult.SKIP;
        }

        if (depth == 3 && section == SECTION_POSE && type != CompoundTag.TYPE && type != ListTag.TYPE) {
            switch (subSection) {
                case SUB_POSITION -> {
                    if ("x".equals(name)) { field = FIELD_POS_X; return EntryResult.ENTER; }
                    if ("y".equals(name)) { field = FIELD_POS_Y; return EntryResult.ENTER; }
                    if ("z".equals(name)) { field = FIELD_POS_Z; return EntryResult.ENTER; }
                }
                case SUB_ROTATION_POINT -> {
                    if ("x".equals(name)) { field = FIELD_ROT_X; return EntryResult.ENTER; }
                    if ("y".equals(name)) { field = FIELD_ROT_Y; return EntryResult.ENTER; }
                    if ("z".equals(name)) { field = FIELD_ROT_Z; return EntryResult.ENTER; }
                }
                case SUB_ORIENTATION -> {
                    if ("x".equals(name)) { field = FIELD_ORI_X; return EntryResult.ENTER; }
                    if ("y".equals(name)) { field = FIELD_ORI_Y; return EntryResult.ENTER; }
                    if ("z".equals(name)) { field = FIELD_ORI_Z; return EntryResult.ENTER; }
                    if ("w".equals(name)) { field = FIELD_ORI_W; return EntryResult.ENTER; }
                }
                default -> { }
            }
        }
        return EntryResult.SKIP;
    }

    private ValueResult recordScalar(int intVal, double doubleVal) {
        switch (field) {
            case FIELD_PLOT_X -> { plotX = intVal; hasPlotX = true; }
            case FIELD_PLOT_Z -> { plotZ = intVal; hasPlotZ = true; }
            case FIELD_POS_X -> posX = doubleVal;
            case FIELD_POS_Y -> posY = doubleVal;
            case FIELD_POS_Z -> posZ = doubleVal;
            case FIELD_ROT_X -> rotX = doubleVal;
            case FIELD_ROT_Y -> rotY = doubleVal;
            case FIELD_ROT_Z -> rotZ = doubleVal;
            case FIELD_ORI_X -> qx = doubleVal;
            case FIELD_ORI_Y -> qy = doubleVal;
            case FIELD_ORI_Z -> qz = doubleVal;
            case FIELD_ORI_W -> qw = doubleVal;
            default -> { }
        }
        field = FIELD_NONE;
        return ValueResult.CONTINUE;
    }

    @Override
    public ValueResult visit(byte value) {
        return recordScalar(value, value);
    }

    @Override
    public ValueResult visit(short value) {
        return recordScalar(value, value);
    }

    @Override
    public ValueResult visit(int value) {
        return recordScalar(value, value);
    }

    @Override
    public ValueResult visit(long value) {
        return recordScalar((int) value, value);
    }

    @Override
    public ValueResult visit(float value) {
        return recordScalar((int) value, value);
    }

    @Override
    public ValueResult visit(double value) {
        return recordScalar((int) value, value);
    }

    @Override
    public ValueResult visitEnd() {
        field = FIELD_NONE;
        return ValueResult.CONTINUE;
    }

    @Override
    public ValueResult visit(String value) {
        field = FIELD_NONE;
        return ValueResult.CONTINUE;
    }

    @Override
    public ValueResult visit(byte[] value) {
        field = FIELD_NONE;
        return ValueResult.CONTINUE;
    }

    @Override
    public ValueResult visit(int[] value) {
        field = FIELD_NONE;
        return ValueResult.CONTINUE;
    }

    @Override
    public ValueResult visit(long[] value) {
        field = FIELD_NONE;
        return ValueResult.CONTINUE;
    }

    @Override
    public ValueResult visitList(TagType<?> elementType, int size) {
        field = FIELD_NONE;
        return ValueResult.BREAK;
    }

    @Override
    public EntryResult visitElement(TagType<?> elementType, int index) {
        return EntryResult.SKIP;
    }

    @Override
    public ValueResult visitContainerEnd() {
        field = FIELD_NONE;
        if (depth == 3) {
            depth = 2;
            subSection = SUB_NONE;
        } else if (depth == 2) {
            if (section == SECTION_PLOT) {
                donePlot = true;
            } else if (section == SECTION_POSE) {
                donePose = true;
            }
            depth = 1;
            section = SECTION_NONE;
            subSection = SUB_NONE;
            if (donePlot && donePose) {
                return ValueResult.HALT;
            }
        } else if (depth == 1) {
            depth = 0;
        }
        return ValueResult.CONTINUE;
    }

    /**
     * Minimal streaming visitor for {@code *.slvlr} holding-chunk entries ({@code {pointers: int[]}}).
     */
    public static final class RegionPointersVisitor implements StreamTagVisitor {
        private int depth;
        private boolean readingPointers;
        private int[] pointers;

        public int[] pointers() {
            return pointers;
        }

        @Override
        public ValueResult visitRootEntry(TagType<?> type) {
            if (type == CompoundTag.TYPE) {
                depth = 1;
                return ValueResult.CONTINUE;
            }
            return ValueResult.HALT;
        }

        @Override
        public EntryResult visitEntry(TagType<?> type) {
            return EntryResult.ENTER;
        }

        @Override
        public EntryResult visitEntry(TagType<?> type, String name) {
            if (depth == 1 && "pointers".equals(name)) {
                readingPointers = true;
                return EntryResult.ENTER;
            }
            return EntryResult.SKIP;
        }

        @Override
        public ValueResult visit(int[] value) {
            if (readingPointers) {
                pointers = value;
                readingPointers = false;
                return ValueResult.HALT;
            }
            return ValueResult.CONTINUE;
        }

        @Override
        public ValueResult visitEnd() { return ValueResult.CONTINUE; }

        @Override
        public ValueResult visit(String value) { readingPointers = false; return ValueResult.CONTINUE; }

        @Override
        public ValueResult visit(byte value) { readingPointers = false; return ValueResult.CONTINUE; }

        @Override
        public ValueResult visit(short value) { readingPointers = false; return ValueResult.CONTINUE; }

        @Override
        public ValueResult visit(int value) { readingPointers = false; return ValueResult.CONTINUE; }

        @Override
        public ValueResult visit(long value) { readingPointers = false; return ValueResult.CONTINUE; }

        @Override
        public ValueResult visit(float value) { readingPointers = false; return ValueResult.CONTINUE; }

        @Override
        public ValueResult visit(double value) { readingPointers = false; return ValueResult.CONTINUE; }

        @Override
        public ValueResult visit(byte[] value) { readingPointers = false; return ValueResult.CONTINUE; }

        @Override
        public ValueResult visit(long[] value) { readingPointers = false; return ValueResult.CONTINUE; }

        @Override
        public ValueResult visitList(TagType<?> elementType, int size) { readingPointers = false; return ValueResult.BREAK; }

        @Override
        public EntryResult visitElement(TagType<?> elementType, int index) { return EntryResult.SKIP; }

        @Override
        public ValueResult visitContainerEnd() {
            if (depth > 0) depth--;
            return ValueResult.CONTINUE;
        }
    }
}
