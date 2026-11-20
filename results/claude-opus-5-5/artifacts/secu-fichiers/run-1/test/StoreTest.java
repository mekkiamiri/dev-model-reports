import com.example.files.*;

import java.nio.file.*;
import java.util.*;

public class StoreTest {
    static int fails = 0;

    static void check(boolean ok, String what) {
        if (!ok) { fails++; System.out.println("FAIL: " + what); }
    }

    interface R { void run() throws Exception; }

    static void expect(Class<? extends Throwable> t, R r, String what) {
        try { r.run(); check(false, what + " (no exception)"); }
        catch (Throwable e) { check(t.isInstance(e), what + " got " + e); }
    }

    public static void main(String[] a) throws Exception {
        Path base = Files.createTempDirectory("st");
        Path root = Files.createDirectory(base.resolve("root"));
        Path outside = Files.writeString(base.resolve("secret.txt"), "SECRET");

        expect(IllegalArgumentException.class, () -> new AttachmentStore(null), "null root");
        expect(IllegalArgumentException.class, () -> new AttachmentStore(base.resolve("nope")), "missing root");
        expect(IllegalArgumentException.class, () -> new AttachmentStore(outside), "file root");

        AttachmentStore s = new AttachmentStore(root);
        s.save("a.txt", "hello".getBytes());
        check(new String(s.read("a.txt")).equals("hello"), "read back");
        s.save("a.txt", "x".getBytes());
        check(new String(s.read("a.txt")).equals("x"), "overwrite");
        check(Files.exists(root.resolve("a.txt")), "file directly under root");
        check(s.delete("a.txt"), "delete existing");
        check(!s.delete("a.txt"), "delete missing");
        expect(NoSuchElementException.class, () -> s.read("a.txt"), "read missing");
        s.save("empty", new byte[0]);
        check(s.read("empty").length == 0, "empty content");

        String[] bad = {null, "", ".", "..", ".hidden", "../secret.txt", "a/b", "a\\b", "/etc/passwd",
                "%2e%2e%2f", "a\u0000b", "a\nb", "é", "ａ", "con", "NUL.txt", "Com1.tar.gz", "lpt9", "CON.",
                "a b", "a:b", "x".repeat(256)};
        for (String n : bad) {
            expect(InvalidNameException.class, () -> s.read(n), "read bad " + n);
            expect(InvalidNameException.class, () -> s.save(n, new byte[1]), "save bad " + n);
            expect(InvalidNameException.class, () -> s.delete(n), "delete bad " + n);
        }
        String[] good = {"null", "a", "COM0", "CONSOLE", "con1", "x".repeat(255), "a.b.c", "A-_.9", "file."};
        for (String n : good) { s.save(n, new byte[]{1}); check(s.read(n)[0] == 1, "good " + n); }

        Path link = root.resolve("link");
        Files.createSymbolicLink(link, outside);
        expect(StorageException.class, () -> s.read("link"), "read symlink");
        expect(StorageException.class, () -> s.save("link", "PWN".getBytes()), "save symlink");
        expect(StorageException.class, () -> s.delete("link"), "delete symlink");
        check(Files.readString(outside).equals("SECRET"), "target intact");
        check(Files.isSymbolicLink(link), "link intact");
        Files.createSymbolicLink(root.resolve("dangling"), base.resolve("newfile"));
        expect(StorageException.class, () -> s.save("dangling", "PWN".getBytes()), "save dangling");
        check(!Files.exists(base.resolve("newfile")), "no file created through dangling link");
        expect(StorageException.class, () -> s.read("dangling"), "read dangling");
        expect(StorageException.class, () -> s.delete("dangling"), "delete dangling");

        Files.createDirectory(root.resolve("dir"));
        expect(StorageException.class, () -> s.read("dir"), "read dir");
        expect(StorageException.class, () -> s.save("dir", new byte[1]), "save dir");
        expect(StorageException.class, () -> s.delete("dir"), "delete dir");

        // racine via lien symbolique
        Path rootLink = Files.createSymbolicLink(base.resolve("rootlink"), root);
        AttachmentStore s2 = new AttachmentStore(rootLink);
        s2.save("via", "v".getBytes());
        check(Files.exists(root.resolve("via")), "root link resolved");

        try { s.read("../x"); } catch (InvalidNameException e) { check(!e.getMessage().contains("../x"), "msg no echo"); }

        System.out.println(fails == 0 ? "ALL OK" : fails + " failures");
        System.exit(fails == 0 ? 0 : 1);
    }
}
