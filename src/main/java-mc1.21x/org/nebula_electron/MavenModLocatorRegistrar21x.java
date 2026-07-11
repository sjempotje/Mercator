package org.nebula_electron;

import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Mod locator for NeoForge on Minecraft 1.21.9+, reads jars from a local Maven repo.
 *
 * <p>Uses {@link MavenModLocator} to get the jar list and {@link OverlappingJarResolver21x}
 * to handle any JarInJar mod-ID conflicts before adding jars to the pipeline.
 *
 * <p>Jars that are Fabric-only mods (i.e. they ship {@code fabric.mod.json} but no
 * {@code neoforge.mods.toml}) are not handed to NeoForge's own discovery pipeline, since it
 * rejects them outright. Instead their paths are appended to the
 * {@code connector.additionalModLocations} system property, which Sinytra Connector's
 * {@code FabricModsDiscoverer} reads to pick up additional Fabric mod locations beyond the
 * regular mods folder.
 *
 * <p>Exception: some Fabric mods ship a dedicated NeoForge-native companion jar (named
 * {@code <artifact>_neopatcher}) that registers its own {@code IDependencyLocator} to patch and
 * load the Fabric jar itself (e.g. stripping mixins that don't apply under Connector). When such
 * a companion is present in the mod list, the Fabric jar is left for the normal pipeline instead
 * of being diverted to Connector, so the companion's patching logic still runs.
 *
 * <p>Lives in {@code META-INF/versions/21/} and takes over from the base stub on Java 21+.
 */
public class MavenModLocatorRegistrar21x implements IModFileCandidateLocator {

    private static final String CONNECTOR_ADDITIONAL_MODS_PROPERTY = "connector.additionalModLocations";

    /**
     * Resolves mod jars from the list file and adds each one to the discovery pipeline, or
     * routes it to Sinytra Connector if it's a Fabric-only mod.
     */
    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        MavenModLocator locator = new MavenModLocator();
        OverlappingJarResolver21x resolver = new OverlappingJarResolver21x();

        System.out.println("[Mercator] Running on the 1.21x-26.x branch");

        List<Path> jars = locator.resolveModJars();
        List<Path> fabricJars = new ArrayList<>();

        for (Path jarPath : jars) {
            try {
                if (isFabricOnlyMod(jarPath) && !hasNeopatcherCompanion(jarPath, jars)) {
                    fabricJars.add(jarPath);
                    System.out.println("[Mercator] Routing to Connector because it's a Fabric mod: " + jarPath.getFileName());
                    continue;
                }

                Object result = resolver.resolve(jarPath, jarPath.getFileName().toString());
                if (result instanceof JarContents contents) {
                    pipeline.addJarContent(contents,
                            ModFileDiscoveryAttributes.DEFAULT.withLocator(this),
                            IncompatibleFileReporting.ERROR);
                    System.out.println("[Mercator] Added: " + jarPath.getFileName());
                }
            } catch (IOException e) {
                System.err.println("[Mercator] Failed to register: " + jarPath);
                e.printStackTrace();
            }
        }

        if (!fabricJars.isEmpty()) {
            registerWithConnector(fabricJars);
        }

        System.out.println("[Mercator] Done");
    }

    /**
     * A jar is treated as Fabric-only when it declares {@code fabric.mod.json} but has no
     * {@code neoforge.mods.toml}, i.e. NeoForge (or Connector's own JarInJar handling) cannot
     * load it directly and it needs Connector's Fabric-to-NeoForge translation.
     */
    private boolean isFabricOnlyMod(Path jarPath) throws IOException {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            return zip.getEntry("fabric.mod.json") != null
                    && zip.getEntry("META-INF/neoforge.mods.toml") == null;
        }
    }

    /**
     * True when another jar in the mod list looks like a {@code <artifact>_neopatcher} companion
     * for this one, based on the artifact name derived from the jar's filename.
     */
    private boolean hasNeopatcherCompanion(Path jarPath, List<Path> allJars) {
        String companionPrefix = (artifactName(jarPath) + "_neopatcher").toLowerCase();
        for (Path other : allJars) {
            if (!other.equals(jarPath) && artifactName(other).toLowerCase().startsWith(companionPrefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Derives the Maven artifact name from a jar's filename by stripping the trailing
     * {@code -<version>.jar} portion (the first {@code -<digit>} boundary).
     */
    private String artifactName(Path jarPath) {
        String name = jarPath.getFileName().toString();
        if (name.endsWith(".jar")) name = name.substring(0, name.length() - 4);
        for (int i = 0; i < name.length() - 1; i++) {
            if (name.charAt(i) == '-' && Character.isDigit(name.charAt(i + 1))) {
                return name.substring(0, i);
            }
        }
        return name;
    }

    /**
     * Appends the given jar paths to the {@code connector.additionalModLocations} system
     * property, preserving any locations already set, so Connector's {@code FabricModsDiscoverer}
     * picks them up during its own scan.
     */
    private void registerWithConnector(List<Path> fabricJars) {
        StringBuilder value = new StringBuilder(System.getProperty(CONNECTOR_ADDITIONAL_MODS_PROPERTY, ""));
        for (Path jarPath : fabricJars) {
            if (!value.isEmpty()) value.append(',');
            value.append(jarPath.toAbsolutePath());
        }
        System.setProperty(CONNECTOR_ADDITIONAL_MODS_PROPERTY, value.toString());
        System.out.println("[Mercator] " + CONNECTOR_ADDITIONAL_MODS_PROPERTY + "=" + value);
    }

    /** Runs before all other mod locators so our jars are visible to JarInJar. */
    @Override
    public int getPriority() {
        return IOrderedProvider.HIGHEST_SYSTEM_PRIORITY;
    }
}
