package com.projectplanner.attachment;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The bytes of the attachments, as files under {@code planner.attachments.dir}
 * ({@code ATTACHMENTS_DIR}). A file is named after a random key that the server made,
 * in a folder named after the key's first two characters, and never after the name it
 * was uploaded with. A file is written to a temporary name next to its place and moved
 * there when it is complete, so a half-written file never has a key.
 */
@ApplicationScoped
public class AttachmentStore {

    private static final Logger LOG = Logger.getLogger(AttachmentStore.class);
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{32}");
    private static final String PARTIAL = ".partial";

    private final Path root;
    private final SecureRandom random = new SecureRandom();

    public AttachmentStore(@ConfigProperty(name = "planner.attachments.dir") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** Copies {@code source} into the store and gives the key it is under. */
    public String save(Path source) {
        String key = newKey();
        Path target = path(key);
        try {
            Files.createDirectories(target.getParent());
            Path partial = target.resolveSibling(key + PARTIAL);
            Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
            move(partial, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store an attachment", e);
        }
        return key;
    }

    /** A second file with the same bytes under a key of its own, for a copied project. */
    public String copy(String key) {
        return save(path(key));
    }

    /** Where the file of {@code key} is. Only a key the store made is taken. */
    public Path path(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Not a storage key");
        }
        return root.resolve(key.substring(0, 2)).resolve(key);
    }

    public boolean exists(String key) {
        return Files.isRegularFile(path(key));
    }

    /** Removes the files. One that cannot be removed is left to the sweep. */
    public void delete(List<String> keys) {
        for (String key : keys) {
            try {
                Files.deleteIfExists(path(key));
            } catch (IOException | RuntimeException e) {
                LOG.warnf("Could not remove the attachment file %s, the sweep will: %s", key, e.getMessage());
            }
        }
    }

    /**
     * Removes the files that no row knows, and the partial files, when they are older
     * than {@code cutoff}. The age keeps it away from an upload whose row is not written
     * yet. Gives how many files went.
     */
    public int sweep(Set<String> known, Instant cutoff) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> files = Files.walk(root, 2)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                String key = name.endsWith(PARTIAL) ? name.substring(0, name.length() - PARTIAL.length()) : name;
                boolean ours = KEY.matcher(key).matches() && file.getParent().getFileName().toString().equals(key.substring(0, 2));
                if (!ours || (known.contains(key) && !name.endsWith(PARTIAL))) {
                    continue;
                }
                if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(file);
                    removed++;
                }
            }
        } catch (IOException e) {
            LOG.warnf("The sweep of %s stopped early: %s", root, e.getMessage());
        }
        return removed;
    }

    private String newKey() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
