package dev.rinchan.waystonesitemcost.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Automated architecture hard-guardrail contract tests scanning real built release JARs
 * and repository sources:
 * 1. Dedicated Server isolation: zero client-only references in non-client .class constant pools.
 * 2. Mixin bidirectional closure: every *.mixins.json entry exists in the JAR, and every @Mixin
 *    class in source/JAR is registered in *.mixins.json.
 * 3. Clean package boundary: zero rinlib or com/terraformersmc/modmenu compile-only stub leakage.
 */
final class ArchitectureContractTest {
    private static final List<String> FORBIDDEN_SERVER_CLIENT_PREFIXES = List.of(
        "net/minecraft/client/",
        "net/neoforged/neoforge/client/",
        "net/minecraftforge/client/",
        "net/fabricmc/fabric/api/client/",
        "net/blay09/mods/waystones/client/",
        "com/terraformersmc/modmenu/"
    );

    private static final List<String> FORBIDDEN_JAR_ENTRY_SUBSTRINGS = List.of(
        "rinlib",
        "com/terraformersmc/modmenu",
        "com/terraformersmc/",
        "waystone_emeralds",
        "waystoneemeralds",
        "paid_waystones"
    );

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern MIXIN_ANNOTATION_PATTERN = Pattern.compile("@Mixin\\s*\\(");
    private static final Pattern JSON_PACKAGE_PATTERN = Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\"(mixins|client|server)\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern JSON_STRING_PATTERN = Pattern.compile("\"([^\"]+)\"");

    private static Path repoRoot() {
        String prop = System.getProperty("waystones.repo.root");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop).toAbsolutePath().normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.exists(cwd.resolve("support/profiles.json"))) {
            return cwd;
        }
        if (cwd.getParent() != null && Files.exists(cwd.getParent().resolve("support/profiles.json"))) {
            return cwd.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root from " + cwd);
    }

    private static List<Path> findBuiltJars() throws IOException {
        String override = System.getProperty("waystones.verify.jars.dir");
        Path dir = (override != null && !override.isBlank())
            ? Path.of(override)
            : repoRoot().resolve("build/release");
        assertTrue(Files.isDirectory(dir),
            "Built JAR directory does not exist: " + dir + " (build at least one target first)");
        List<Path> jars;
        try (var stream = Files.list(dir)) {
            jars = stream
                .filter(p -> p.getFileName().toString().endsWith(".jar"))
                .filter(p -> !p.getFileName().toString().endsWith("-sources.jar"))
                .filter(p -> !p.getFileName().toString().endsWith("-dev.jar"))
                .sorted()
                .toList();
        }
        assertFalse(jars.isEmpty(),
            "No built release JARs found in " + dir + "; ArchitectureContractTest refuses to pass on empty input");
        return jars;
    }

    @Test
    void dedicatedServerIsolationZeroClientReferencesInNonClientClasses() throws IOException {
        var violations = new ArrayList<String>();
        for (Path jarPath : findBuiltJars()) {
            try (ZipFile zf = new ZipFile(jarPath.toFile())) {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.endsWith(".class") || isClientPackage(name)) {
                        continue;
                    }
                    byte[] bytes = zf.getInputStream(entry).readAllBytes();
                    List<String> poolStrings = readConstantPoolStrings(bytes);
                    for (String sym : poolStrings) {
                        for (String forbidden : FORBIDDEN_SERVER_CLIENT_PREFIXES) {
                            if (sym.contains(forbidden)) {
                                violations.add(jarPath.getFileName() + " :: " + name + " references " + sym);
                            }
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "Dedicated Server isolation redline violated:\n  " + String.join("\n  ", violations));
    }

    @Test
    void mixinBidirectionalClosureBetweenJarAndSource() throws IOException {
        var violations = new ArrayList<String>();
        var allRegisteredMixinsAcrossJars = new HashSet<String>();
        var generationsPresent = new HashSet<String>();

        for (Path jarPath : findBuiltJars()) {
            String jarName = jarPath.getFileName().toString();
            if (jarName.contains("1.20.1")) generationsPresent.add("legacy");
            else if (jarName.contains("1.21.1")) generationsPresent.add("requirement");
            else if (jarName.contains("26.")) generationsPresent.add("shogi");

            try (ZipFile zf = new ZipFile(jarPath.toFile())) {
                Set<String> jarEntries = new HashSet<>();
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    jarEntries.add(entries.nextElement().getName());
                }

                Set<String> declaredInJar = new HashSet<>();
                for (String configName : List.of(
                    "waystones_item_cost_extended.mixins.json",
                    "waystones_item_cost_extended.client.mixins.json"
                )) {
                    ZipEntry cfg = zf.getEntry(configName);
                    if (cfg == null) {
                        violations.add(jarName + " missing mixin config " + configName);
                        continue;
                    }
                    String json = new String(zf.getInputStream(cfg).readAllBytes(), StandardCharsets.UTF_8);
                    Set<String> declared = parseMixinConfigClasses(json);
                    for (String fqcn : declared) {
                        declaredInJar.add(fqcn);
                        allRegisteredMixinsAcrossJars.add(fqcn);
                        String classEntry = fqcn.replace('.', '/') + ".class";
                        if (!jarEntries.contains(classEntry)) {
                            violations.add(jarName + " :: " + configName + " declares missing class " + classEntry);
                        }
                    }
                }

                // Reverse check inside JAR: every top-level class under .../mixin/... must be declared
                for (String entry : jarEntries) {
                    if (!entry.endsWith(".class") || entry.contains("$")) continue;
                    if (!entry.startsWith("dev/rinchan/waystonesitemcost/mixin/")) continue;
                    String fqcn = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
                    if (!declaredInJar.contains(fqcn)) {
                        violations.add(jarName + " contains unregistered mixin class " + fqcn);
                    }
                }
            }
        }

        // Source-level reverse check: every @Mixin-annotated class in active generations must be registered
        Map<String, String> sourceMixins = findAnnotatedSourceMixins(repoRoot());
        assertFalse(sourceMixins.isEmpty(), "Expected @Mixin source classes in repository");
        for (var entry : sourceMixins.entrySet()) {
            String fqcn = entry.getKey();
            String relPath = entry.getValue();
            String gen = generationForSourcePath(relPath);
            if (gen == null || generationsPresent.contains(gen)) {
                if (!allRegisteredMixinsAcrossJars.contains(fqcn)) {
                    violations.add("Source @Mixin class " + fqcn + " (" + relPath + ") is NOT registered in any built JAR *.mixins.json");
                }
            }
        }

        assertTrue(violations.isEmpty(),
            "Mixin bidirectional closure violated:\n  " + String.join("\n  ", violations));
    }

    @Test
    void cleanPackageBoundaryZeroRinlibOrModMenuStubLeakage() throws IOException {
        var violations = new ArrayList<String>();
        for (Path jarPath : findBuiltJars()) {
            String jarName = jarPath.getFileName().toString();
            try (ZipFile zf = new ZipFile(jarPath.toFile())) {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    String lower = name.toLowerCase();
                    for (String bad : FORBIDDEN_JAR_ENTRY_SUBSTRINGS) {
                        if (lower.contains(bad)) {
                            violations.add(jarName + " contains forbidden entry: " + name);
                        }
                    }
                    if (name.endsWith(".class")) {
                        byte[] bytes = zf.getInputStream(entry).readAllBytes();
                        for (String sym : readConstantPoolStrings(bytes)) {
                            if (sym.toLowerCase().contains("rinlib")) {
                                violations.add(jarName + " :: " + name + " constant pool references rinlib: " + sym);
                            }
                        }
                    }
                    if (name.equals("fabric.mod.json")
                        || name.equals("META-INF/mods.toml")
                        || name.equals("META-INF/neoforge.mods.toml")) {
                        String meta = new String(zf.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
                        for (String bad : List.of("rinlib", "waystone_emeralds", "paid_waystones")) {
                            if (meta.contains(bad)) {
                                violations.add(jarName + " :: " + name + " metadata contains forbidden token: " + bad);
                            }
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "Clean package boundary redline violated:\n  " + String.join("\n  ", violations));
    }

    private static boolean isClientPackage(String classEntryName) {
        return classEntryName.contains("/client/");
    }

    private static String generationForSourcePath(String relPath) {
        if (relPath.startsWith("versions/legacy/")) return "legacy";
        if (relPath.startsWith("versions/requirement/")) return "requirement";
        if (relPath.startsWith("versions/shogi/")) return "shogi";
        return null; // shared (e.g., networking/*/NetworkingMixin)
    }

    private static Map<String, String> findAnnotatedSourceMixins(Path root) throws IOException {
        var result = new HashMap<String, String>();
        for (String dirName : List.of("common", "adapters", "networking", "versions", "compat", "loaders")) {
            Path dir = root.resolve(dirName);
            if (!Files.isDirectory(dir)) continue;
            try (var walk = Files.walk(dir)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    if (!MIXIN_ANNOTATION_PATTERN.matcher(content).find()) continue;
                    Matcher pm = PACKAGE_PATTERN.matcher(content);
                    if (!pm.find()) continue;
                    String pkg = pm.group(1);
                    String fileName = file.getFileName().toString();
                    String simpleName = fileName.substring(0, fileName.length() - ".java".length());
                    result.put(pkg + "." + simpleName, root.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        return result;
    }

    private static Set<String> parseMixinConfigClasses(String json) {
        Matcher pkgMatcher = JSON_PACKAGE_PATTERN.matcher(json);
        assertTrue(pkgMatcher.find(), "Mixin JSON missing 'package' field: " + json);
        String pkg = pkgMatcher.group(1);
        Set<String> classes = new TreeSet<>();
        Matcher arrMatcher = JSON_ARRAY_PATTERN.matcher(json);
        while (arrMatcher.find()) {
            String body = arrMatcher.group(2);
            Matcher strMatcher = JSON_STRING_PATTERN.matcher(body);
            while (strMatcher.find()) {
                String rel = strMatcher.group(1);
                classes.add(pkg + "." + rel);
            }
        }
        return classes;
    }

    /**
     * Exact JVM ClassFile constant-pool parser (JVMS §4.4) extracting all CONSTANT_Utf8 strings.
     */
    private static List<String> readConstantPoolStrings(byte[] classBytes) throws IOException {
        var out = new ArrayList<String>();
        try (var in = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            int magic = in.readInt();
            if (magic != 0xCAFEBABE) {
                throw new IOException("Invalid class magic: 0x" + Integer.toHexString(magic));
            }
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int cpCount = in.readUnsignedShort();
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> out.add(in.readUTF()); // CONSTANT_Utf8
                    case 3, 4 -> in.skipBytes(4); // Integer, Float
                    case 5, 6 -> { // Long, Double (occupy two CP entries)
                        in.skipBytes(8);
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> in.skipBytes(2); // Class, String, MethodType, Module, Package
                    case 9, 10, 11, 12, 17, 18 -> in.skipBytes(4); // Fieldref, Methodref, InterfaceMethodref, NameAndType, Dynamic, InvokeDynamic
                    case 15 -> in.skipBytes(3); // MethodHandle
                    default -> throw new IOException("Unknown constant pool tag: " + tag);
                }
            }
        }
        return out;
    }
}
