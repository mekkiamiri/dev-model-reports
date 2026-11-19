package com.example.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Stockage de pièces jointes à plat sous un dossier racine.
 * Les noms sont traités comme des données hostiles : ils sont validés par liste blanche
 * avant tout accès disque, et les liens symboliques ne sont jamais suivis.
 */
public final class AttachmentStore {

    private static final int MAX_NAME_LENGTH = 255;

    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private final Path root;

    public AttachmentStore(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        Path real;
        try {
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("root must be an existing directory");
            }
            real = root.toRealPath();
        } catch (IOException | SecurityException | InvalidPathException e) {
            throw new IllegalArgumentException("root cannot be resolved", e);
        }
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("root must be an existing directory");
        }
        this.root = real;
    }

    /** Crée la pièce ou remplace son contenu. */
    public void save(String name, byte[] content) {
        Path file = resolve(name);
        Objects.requireNonNull(content, "content must not be null");
        try {
            BasicFileAttributes attrs = attributesOrNull(file);
            if (attrs != null) {
                rejectIfNotRegular(attrs);
            }
            // NOFOLLOW_LINKS => O_NOFOLLOW : si un lien apparaît entre la vérification
            // et l'ouverture, l'ouverture échoue au lieu de suivre le lien.
            try (OutputStream out = Files.newOutputStream(file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) {
                out.write(content);
            }
        } catch (IOException | SecurityException e) {
            throw new StorageException("cannot save attachment", e);
        }
    }

    /** Lit le contenu de la pièce. */
    public byte[] read(String name) {
        Path file = resolve(name);
        try {
            BasicFileAttributes attrs = attributesOrNull(file);
            if (attrs == null) {
                throw new NoSuchElementException("attachment not found");
            }
            rejectIfNotRegular(attrs);
            try (InputStream in = Files.newInputStream(file,
                    StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                return in.readAllBytes();
            }
        } catch (NoSuchFileException e) {
            throw new NoSuchElementException("attachment not found");
        } catch (IOException | SecurityException e) {
            throw new StorageException("cannot read attachment", e);
        }
    }

    /** Supprime la pièce ; rend true si elle existait. */
    public boolean delete(String name) {
        Path file = resolve(name);
        try {
            BasicFileAttributes attrs = attributesOrNull(file);
            if (attrs == null) {
                return false;
            }
            rejectIfNotRegular(attrs);
            // Files.delete ne suit jamais un lien : au pire (course), c'est le lien
            // lui-même qui serait supprimé, jamais sa cible.
            Files.delete(file);
            return true;
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException | SecurityException e) {
            throw new StorageException("cannot delete attachment", e);
        }
    }

    // ------------------------------------------------------------------ interne

    private Path resolve(String name) {
        validate(name);
        Path file;
        try {
            file = root.resolve(name);
        } catch (InvalidPathException e) {
            throw new InvalidNameException("invalid attachment name");
        }
        // Défense en profondeur : la pièce doit être un enfant direct de la racine.
        if (!root.equals(file.getParent()) || !name.equals(file.getFileName().toString())) {
            throw new InvalidNameException("invalid attachment name");
        }
        return file;
    }

    static void validate(String name) {
        if (name == null) {
            throw new InvalidNameException("attachment name must not be null");
        }
        int len = name.length();
        if (len < 1 || len > MAX_NAME_LENGTH) {
            throw new InvalidNameException("attachment name must have 1 to 255 characters");
        }
        for (int i = 0; i < len; i++) {
            if (!isAllowed(name.charAt(i))) {
                throw new InvalidNameException("attachment name contains a forbidden character");
            }
        }
        if (name.charAt(0) == '.') {
            throw new InvalidNameException("attachment name must not start with '.'");
        }
        int dot = name.indexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        if (RESERVED.contains(base.toUpperCase(Locale.ROOT))) {
            throw new InvalidNameException("attachment name is a reserved device name");
        }
    }

    private static boolean isAllowed(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '-';
    }

    private static BasicFileAttributes attributesOrNull(Path file) throws IOException {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    private static void rejectIfNotRegular(BasicFileAttributes attrs) {
        if (attrs.isSymbolicLink()) {
            throw new StorageException("attachment is a symbolic link; refusing to follow it", null);
        }
        if (!attrs.isRegularFile()) {
            throw new StorageException("attachment is not a regular file", null);
        }
    }
}
