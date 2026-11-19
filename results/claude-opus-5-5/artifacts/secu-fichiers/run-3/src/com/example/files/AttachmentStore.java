package com.example.files;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Stockage de pièces jointes à plat dans un dossier racine.
 * Les noms reçus sont considérés comme hostiles : ils sont validés par liste blanche
 * avant tout accès disque, et ne sont jamais recopiés dans les messages d'erreur.
 */
public final class AttachmentStore {

    private static final int MAX_NAME_LENGTH = 255;
    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
    /** Préfixe des fichiers temporaires : commence par '.', donc jamais un nom de pièce valide. */
    private static final String TEMP_PREFIX = ".tmp-";

    private final Path root;

    public AttachmentStore(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        try {
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("root must be an existing directory");
            }
            this.root = root.toRealPath();
        } catch (IOException | SecurityException e) {
            throw new IllegalArgumentException("root cannot be resolved", e);
        }
        if (!Files.isDirectory(this.root, NOFOLLOW)) {
            throw new IllegalArgumentException("root must be an existing directory");
        }
    }

    public void save(String name, byte[] content) {
        Path target = resolve(name);
        Objects.requireNonNull(content, "content must not be null");
        checkRegularOrAbsent(target);
        Path tmp = root.resolve(TEMP_PREFIX + UUID.randomUUID());
        try {
            try (OutputStream out = Files.newOutputStream(tmp,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                out.write(content);
            }
            // Revérification juste avant le remplacement (réduit la fenêtre de course).
            checkRegularOrAbsent(target);
            // rename(2) remplace l'entrée de répertoire sans jamais suivre un lien éventuel :
            // même en cas de course, la cible d'un lien n'est jamais modifiée.
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | SecurityException e) {
            throw new StorageException("cannot save attachment", e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException | SecurityException ignored) {
                // nettoyage au mieux
            }
        }
    }

    public byte[] read(String name) {
        Path target = resolve(name);
        if (!checkRegularOrAbsent(target)) {
            throw new NoSuchElementException("attachment not found");
        }
        // NOFOLLOW_LINKS => O_NOFOLLOW : si un lien est substitué entre-temps, l'ouverture échoue.
        try (SeekableByteChannel ch = Files.newByteChannel(target,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(ch.size(), 1 << 20));
            ByteBuffer buf = ByteBuffer.allocate(8192);
            int n;
            while ((n = ch.read(buf)) >= 0) {
                out.write(buf.array(), 0, n);
                buf.clear();
            }
            return out.toByteArray();
        } catch (NoSuchFileException e) {
            throw new NoSuchElementException("attachment not found");
        } catch (IOException | SecurityException e) {
            throw new StorageException("cannot read attachment", e);
        }
    }

    public boolean delete(String name) {
        Path target = resolve(name);
        if (!checkRegularOrAbsent(target)) {
            return false;
        }
        try {
            // unlink(2) ne suit jamais un lien : au pire c'est le lien lui-même qui disparaît.
            return Files.deleteIfExists(target);
        } catch (IOException | SecurityException e) {
            throw new StorageException("cannot delete attachment", e);
        }
    }

    // ------------------------------------------------------------------ interne

    private Path resolve(String name) {
        validate(name);
        Path p = root.resolve(name);
        // Défense en profondeur : la pièce doit être un enfant direct de la racine.
        if (!root.equals(p.getParent()) || !p.getFileName().toString().equals(name)) {
            throw new InvalidNameException("invalid attachment name");
        }
        return p;
    }

    static void validate(String name) {
        if (name == null) {
            throw new InvalidNameException("attachment name must not be null");
        }
        int len = name.length();
        if (len < 1 || len > MAX_NAME_LENGTH) {
            throw new InvalidNameException("attachment name must have 1 to " + MAX_NAME_LENGTH + " characters");
        }
        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (!ok) {
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

    /**
     * Inspecte l'entrée sans suivre les liens.
     * @return true si c'est un fichier ordinaire, false si elle n'existe pas
     * @throws StorageException si c'est un lien symbolique, un dossier ou autre chose
     */
    private static boolean checkRegularOrAbsent(Path p) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(p, BasicFileAttributes.class, NOFOLLOW);
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException | SecurityException e) {
            throw new StorageException("cannot access attachment", e);
        }
        if (attrs.isRegularFile()) {
            return true;
        }
        String what = attrs.isSymbolicLink() ? "attachment is a symbolic link"
                : attrs.isDirectory() ? "attachment is a directory"
                : "attachment is not a regular file";
        throw new StorageException(what + "; refusing to follow or modify it", null);
    }
}
