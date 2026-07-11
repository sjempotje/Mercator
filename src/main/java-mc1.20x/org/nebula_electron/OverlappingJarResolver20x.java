package org.nebula_electron;

import cpw.mods.jarhandling.JarContents;
import net.neoforged.fml.loading.FMLPaths;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Same conflict resolution as {@link OverlappingJarResolver} in this source set, but named
 * separately so {@link MavenModLocatorRegistrar20x} can reference it explicitly when called
 * from the version-dispatching registrar in {@code java-mc-dispatcher}.
 *
 * @see OverlappingJarResolver the 1.21.x version that can use a filtered overlay
 */
public class OverlappingJarResolver20x {

    private final JijCache jijCache = new JijCache(FMLPaths.GAMEDIR.get().resolve("jij-cache"));

    /**
     * Returns the {@link JarContents} to load for the given jar.
     *
     * <p>Returns the outer jar as-is unless it has JarJar metadata and an inner jar with the
     * same mod ID, in which case the inner jar is extracted to the cache and a union of the
     * inner jar plus a patched copy of the outer jar (with its own {@code neoforge.mods.toml},
     * {@code MANIFEST.MF}, and the self-referential JarJar entry stripped) is returned, so the
     * outer jar's other classes are still available on the classpath.
     *
     * @param jarPath    path to the outer mod jar
     * @param coordLabel Maven coordinate, used only in log output
     * @return the resolved {@link JarContents}
     * @throws IOException if reading the jar or writing to the cache fails
     */
    public Object resolve(Path jarPath, String coordLabel) throws IOException {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            if (zip.getEntry("META-INF/jarjar/metadata.json") == null)
                return JarContents.of(jarPath);

            String outerModId = readModId(zip);
            if (outerModId == null)
                return JarContents.of(jarPath);

            String metadata;
            try (InputStream in = zip.getInputStream(zip.getEntry("META-INF/jarjar/metadata.json"))) {
                metadata = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            String innerEntryPath = findInnerJarWithSameModId(zip, metadata, outerModId);
            if (innerEntryPath == null)
                return JarContents.of(jarPath);

            String filename = innerEntryPath.substring(innerEntryPath.lastIndexOf('/') + 1);
            Path innerJar = jijCache.extract(zip, innerEntryPath, filename);
            Path patchedOuter = createPatchedJar(zip, jarPath, innerEntryPath, metadata);

            System.out.println("[Mercator] Using JarContents for: " + coordLabel);
            return JarContents.of(List.of(innerJar, patchedOuter));
        }
    }

    /**
     * Creates a patched copy of the outer jar with the self-referential entry removed from
     * {@code META-INF/jarjar/} (both the jar file entry and its {@code metadata.json} record),
     * and with {@code META-INF/neoforge.mods.toml} / {@code META-INF/MANIFEST.MF} dropped so the
     * inner jar's copies are the only ones seen. The result is cached next to the original as
     * {@code <name>.patched.jar}.
     */
    private Path createPatchedJar(ZipFile zip, Path jarPath, String innerEntryPath, String originalMetadata)
            throws IOException {
        String patchedName = jarPath.getFileName().toString().replace(".jar", ".patched.jar");
        Path patchedPath = jarPath.getParent().resolve(patchedName);

        if (Files.isRegularFile(patchedPath)) return patchedPath;

        String patchedMetadata = removePathFromMetadata(originalMetadata, innerEntryPath);

        Path tmp = Files.createTempFile(jarPath.getParent(), "_patched", ".tmp");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.equals(innerEntryPath)
                        || name.equals("META-INF/neoforge.mods.toml")
                        || name.equals("META-INF/MANIFEST.MF")) continue;
                out.putNextEntry(new ZipEntry(name));
                if (name.equals("META-INF/jarjar/metadata.json")) {
                    out.write(patchedMetadata.getBytes(StandardCharsets.UTF_8));
                } else {
                    try (InputStream in = zip.getInputStream(entry)) {
                        in.transferTo(out);
                    }
                }
                out.closeEntry();
            }
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }

        try {
            Files.move(tmp, patchedPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, patchedPath, StandardCopyOption.REPLACE_EXISTING);
        }
        return patchedPath;
    }

    /**
     * Removes the JSON object for {@code pathToRemove} from the {@code jars} array in the
     * JarJar {@code metadata.json} string.
     *
     * <p>Scans backwards from the {@code "path"} key to find the outermost {@code {} enclosing
     * the entry (not an inner nested one), then forward to its matching {@code }}, and splices
     * out the object along with any surrounding comma.
     */
    private String removePathFromMetadata(String metadata, String pathToRemove) {
        String pathKey = "\"path\": \"" + pathToRemove + "\"";
        int pathIdx = metadata.indexOf(pathKey);
        if (pathIdx == -1) return metadata;

        int depth = 0, blockStart = -1;
        for (int i = pathIdx - 1; i >= 0; i--) {
            char c = metadata.charAt(i);
            if (c == '}') depth++;
            else if (c == '{') {
                if (depth == 0) { blockStart = i; break; }
                depth--;
            }
        }
        if (blockStart == -1) return metadata;

        depth = 0;
        int blockEnd = -1;
        for (int i = blockStart; i < metadata.length(); i++) {
            char c = metadata.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                if (--depth == 0) { blockEnd = i + 1; break; }
            }
        }
        if (blockEnd == -1) return metadata;

        String before = metadata.substring(0, blockStart).stripTrailing();
        String after  = metadata.substring(blockEnd).stripLeading();

        boolean beforeComma = before.endsWith(",");
        boolean afterComma  = after.startsWith(",");

        if (beforeComma) before = before.substring(0, before.length() - 1).stripTrailing();
        if (afterComma)  after  = after.substring(1).stripLeading();

        if (beforeComma && afterComma) return before + "," + after;
        return before + after;
    }

    /**
     * Walks the JarJar metadata JSON looking for an inner jar whose {@code neoforge.mods.toml}
     * has the same mod ID as the outer jar.
     *
     * @param zip         the outer zip to read inner jar entries from
     * @param metadata    raw contents of {@code META-INF/jarjar/metadata.json}
     * @param targetModId the mod ID to look for
     * @return the zip-entry path of the matching inner jar, or null if not found
     * @throws IOException if reading an inner jar entry fails
     */
    private String findInnerJarWithSameModId(ZipFile zip, String metadata, String targetModId) throws IOException {
        int pos = 0;
        while (true) {
            int idx = metadata.indexOf("\"path\"", pos);
            if (idx == -1) break;
            int colon = metadata.indexOf(':', idx + 6);
            int open  = metadata.indexOf('"', colon + 1);
            int close = metadata.indexOf('"', open + 1);
            if (colon == -1 || open == -1 || close == -1) break;
            String innerPath = metadata.substring(open + 1, close);
            pos = close + 1;

            ZipEntry entry = zip.getEntry(innerPath);
            if (entry == null) continue;
            try (ZipInputStream zis = new ZipInputStream(zip.getInputStream(entry))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if ("META-INF/neoforge.mods.toml".equals(e.getName())) {
                        if (targetModId.equals(extractModId(new String(zis.readAllBytes()))))
                            return innerPath;
                        break;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Reads the mod ID out of {@code neoforge.mods.toml} in the zip.
     *
     * @param zip the zip to read from
     * @return the mod ID, or null if the entry is missing or the field isn't found
     * @throws IOException if reading fails
     */
    private String readModId(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("META-INF/neoforge.mods.toml");
        if (entry == null) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            return extractModId(new String(in.readAllBytes()));
        }
    }

    /**
     * Pulls the first {@code modId} value out of a TOML string with basic string scanning.
     *
     * @param toml raw TOML content
     * @return the mod ID value, or null if the key isn't present
     */
    private String extractModId(String toml) {
        int idx = toml.indexOf("modId");
        if (idx == -1) return null;
        int eq    = toml.indexOf('=', idx + 5);
        int open  = toml.indexOf('"', eq + 1);
        int close = toml.indexOf('"', open + 1);
        if (eq == -1 || open == -1 || close == -1) return null;
        return toml.substring(open + 1, close);
    }
}
