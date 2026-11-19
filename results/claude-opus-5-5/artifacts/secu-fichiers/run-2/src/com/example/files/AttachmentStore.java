package com.example.files;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Stockage de pièces jointes à plat sous un dossier racine.
 *
 * <p>Les noms sont considérés comme hostiles : ils sont validés par liste blanche avant tout accès
 * au disque, et aucun lien symbolique n'est jamais suivi pour une pièce.
 */
public final class AttachmentStore {

    private static final int MAX_NAME_LENGTH = 255;

    private static final Set<String> RESERVED_WINDOWS_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};

    private final Path root;

    public AttachmentStore(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("root must be an existing directory");
        }
        try {
            this.root = root.toRealPath();
        } catch (IOException | SecurityException e) {
            throw new IllegalArgumentException("root cannot be resolved", e);
        }
        if (!Files.isDirectory(this.root, NO_FOLLOW)) {
            throw new IllegalArgumentException("root must be an existing directory");
        }
    }

    /** Crée la pièce ou remplace son contenu. */
    public void save(String name, byte[] content) {
        Path file = resolve(name);
        Objects.requireNonNull(content, "content must not be null");

        BasicFileAttributes attrs = attributesOrNull(file);
        if (attrs != null) {
            rejectIfNotRegular(attrs);
        }

        OpenOption[] options = {
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS
        };
        // NOFOLLOW_LINKS (O_NOFOLLOW) ferme la course entre la vérification et l'ouverture :
        // si un lien est apparu entre-temps, l'ouverture échoue sans toucher la cible.
        try (SeekableByteChannel ch = Files.newByteChannel(file, Set.of(options));
             OutputStream out = Channels.newOutputStream(ch)) {
            out.write(content);
        } catch (IOException e) {
            throw new StorageException("cannot write attachment", e);
        }
    }

    /** Lit le contenu de la pièce. */
    public byte[] read(String name) {
        Path file = resolve(name);

        BasicFileAttributes attrs = attributesOrNull(file);
        if (attrs == null) {
            throw new NoSuchElementException("attachment not found");
        }
        rejectIfNotRegular(attrs);

        try (SeekableByteChannel ch = Files.newByteChannel(file,
                     Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             InputStream in = Channels.newInputStream(ch)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            in.transferTo(buf);
            return buf.toByteArray();
        } catch (NoSuchFileException e) {
            throw new NoSuchElementException("attachment not found");
        } catch (IOException e) {
            throw new StorageException("cannot read attachment", e);
        }
    }

    /** Supprime la pièce ; rend true si elle existait. */
    public boolean delete(String name) {
        Path file = resolve(name);

        BasicFileAttributes attrs = attributesOrNull(file);
        if (attrs == null) {
            return false;
        }
        rejectIfNotRegular(attrs);

        try {
            // Files.delete ne suit pas les liens : au pire (course), c'est le lien qui disparaît.
            Files.delete(file);
            return true;
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            throw new StorageException("cannot delete attachment", e);
        }
    }

    // ------------------------------------------------------------------ interne

    private Path resolve(String name) {
        validate(name);
        Path file = root.resolve(name);
        // Défense en profondeur : la validation garantit déjà ces propriétés.
        if (!root.equals(file.getParent())
                || file.getFileName() == null
                || !name.equals(file.getFileName().toString())) {
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
            throw new InvalidNameException("attachment name must not begin with a dot");
        }
        int dot = name.indexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        if (RESERVED_WINDOWS_NAMES.contains(base.toUpperCase(Locale.ROOT))) {
            throw new InvalidNameException("attachment name is reserved");
        }
    }

    private static boolean isAllowed(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '-';
    }

    /** Attributs sans suivre les liens, ou null si le fichier n'existe pas. */
    private static BasicFileAttributes attributesOrNull(Path file) {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class, NO_FOLLOW);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new StorageException("cannot access attachment", e);
        }
    }

    private static void rejectIfNotRegular(BasicFileAttributes attrs) {
        if (attrs.isSymbolicLink()) {
            throw new StorageException("attachment is a symbolic link; refusing to follow it",
                    new IOException("symbolic link"));
        }
        if (!attrs.isRegularFile()) {
            throw new StorageException("attachment is not a regular file",
                    new IOException("not a regular file"));
        }
    }
}
