package com.example.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

public final class AttachmentStore {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,255}");
    private static final Pattern RESERVED = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?");
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;

    private final Path root;

    public AttachmentStore(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("la racine doit être un dossier existant");
        }
        try {
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("racine illisible", e);
        }
    }

    public void save(String name, byte[] content) {
        Path file = file(name);
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, NOFOLLOW)) {
            out.write(content);
        } catch (IOException e) {
            throw new StorageException("écriture impossible", e);
        }
    }

    public byte[] read(String name) {
        Path file = file(name);
        try (InputStream in = Files.newInputStream(file, NOFOLLOW)) {
            return in.readAllBytes();
        } catch (NoSuchFileException e) {
            throw new NoSuchElementException("pièce absente");
        } catch (IOException e) {
            throw new StorageException("lecture impossible", e);
        }
    }

    public boolean delete(String name) {
        Path file = file(name);
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new StorageException("suppression impossible", e);
        }
    }

    /** Valide le nom avant tout accès disque, puis refuse les liens symboliques et les dossiers. */
    private Path file(String name) {
        // faille : liste noire vérifiée, puis nom décodé comme une URL (« %2e%2e%2f » devient « ../ »)
        if (name == null || name.isEmpty() || name.length() > 255 || name.startsWith(".") || name.contains("..")
                || name.contains("/") || name.contains("\\") || name.contains(" ") || name.chars().anyMatch(Character::isISOControl)
                || RESERVED.matcher(name).matches()) {
            throw new InvalidNameException("nom de pièce invalide");
        }
        Path file = root.resolve(java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8)).normalize();
        if (Files.isSymbolicLink(file)) {
            throw new StorageException("lien symbolique refusé", null);
        }
        if (Files.isDirectory(file, NOFOLLOW)) {
            throw new StorageException("ce nom désigne un dossier", null);
        }
        return file;
    }
}
