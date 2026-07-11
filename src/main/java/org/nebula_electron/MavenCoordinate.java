package org.nebula_electron;

import java.nio.file.*;

/**
 * A Maven artifact coordinate in {@code group:artifact:version} form.
 *
 * @param group    the Maven group ID (e.g. {@code net.neoforged})
 * @param artifact the Maven artifact ID (e.g. {@code neoforge})
 * @param version  the artifact version (e.g. {@code 21.1.0+1})
 */
public record MavenCoordinate(String group, String artifact, String version) {

    /**
     * Parses a {@code group:artifact:version} string.
     *
     * @param coord the coordinate string to parse
     * @return the parsed coordinate, or null if fewer than three colon-separated parts are present
     */
    public static MavenCoordinate parse(String coord) {
        String[] parts = coord.split(":");
        if (parts.length < 3) return null;
        return new MavenCoordinate(parts[0], parts[1], parts[2]);
    }

    /**
     * Resolves the path to this artifact's jar in a local Maven repository.
     *
     * <p>If the version contains {@code '+'}, a {@code .patched.jar} variant is preferred when it
     * exists. Patched jars have the {@code '+'} stripped in {@code neoforge.mods.toml} so Maven
     * version ranges work correctly. Jars that don't need patching are left alone since patching
     * a signed jar invalidates its signature.
     *
     * @param repoRoot root directory of the local Maven repository
     * @return the normalized path to the jar file
     */
    public Path resolveIn(Path repoRoot) {
        Path base = repoRoot
                .resolve(group.replace('.', '/'))
                .resolve(artifact)
                .resolve(version);

        return base.resolve(artifact + "-" + version + ".jar").normalize();
    }

    /** Returns the coordinate as a {@code group:artifact:version} string. */
    @Override
    public String toString() {
        return group + ":" + artifact + ":" + version;
    }
}
