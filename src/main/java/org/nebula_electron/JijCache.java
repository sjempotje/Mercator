package org.nebula_electron;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.util.zip.*;

/**
 * Content-addressed cache for JarInJar inner jars.
 *
 * <p>Inner jars are stored under {@code cacheDir} keyed by their SHA-256 hash.
 * Extracting the same bytes twice is a no-op: if the destination already exists the
 * temp file is deleted and the existing path is returned.
 */
public class JijCache {

    private final Path cacheDir;

    /**
     * @param cacheDir directory where cached jars are stored
     */
    public JijCache(Path cacheDir) {
        this.cacheDir = cacheDir;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create JijCache directory: " + cacheDir, e);
        }
    }

    /**
     * Extracts a zip entry to the cache, keyed by its SHA-256 hash.
     *
     * <p>The entry is written to a temp file, hashed, then moved atomically to
     * {@code <cacheDir>/<sha256>/<filename>}. If that path already exists the temp
     * file is discarded and the cached path is returned.
     *
     * @param zip       zip file containing the entry
     * @param entryPath path of the entry within {@code zip}
     * @param filename  filename to use for the cached artifact
     * @return path to the cached jar
     * @throws IOException if reading, hashing, or moving the file fails
     */
    public Path extract(ZipFile zip, String entryPath, String filename) throws IOException {
        Path tmp = Files.createTempFile(cacheDir, "_jij", ".tmp");
        String checksum;
        try {
            MessageDigest digest = newSha256();
            try (InputStream in = zip.getInputStream(zip.getEntry(entryPath));
                DigestOutputStream out = new DigestOutputStream(Files.newOutputStream(tmp), digest)) {
                in.transferTo(out);
            }
            checksum = HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }

        Path dest = cacheDir.resolve(checksum + "/" + filename);
        if (!Files.isRegularFile(dest)) {
            Files.createDirectories(dest.getParent());
            try {
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            Files.deleteIfExists(tmp);
        }
        return dest;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
