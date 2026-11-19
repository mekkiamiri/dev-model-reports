import com.example.files.*;

import java.nio.file.*;
import java.util.*;

public class AttachmentStoreTest {
    static int fails = 0;

    static void check(boolean ok, String what) {
        if (!ok) { fails++; System.out.println("FAIL: " + what); }
    }

    interface R { void run() throws Exception; }

    static void expect(Class<? extends Throwable> type, R r, String what) {
        try { r.run(); check(false, what + " (no exception)"); }
        catch (Throwable t) {
            check(type.isInstance(t), what + " (got " + t + ")");
        }
    }

    public static void main(String[] a) throws Exception {
        Path base = Files.createTempDirectory("ast");
        Path root = Files.createDirectory(base.resolve("root"));
        Path outside = base.resolve("secret.txt");
        Files.writeString(outside, "SECRET");

        expect(IllegalArgumentException.class, () -> new AttachmentStore(null), "null root");
        expect(IllegalArgumentException.class, () -> new AttachmentStore(base.resolve("nope")), "absent root");
        expect(IllegalArgumentException.class, () -> new AttachmentStore(outside), "file root");

        // root via symlink -> real path
        Path rootLink = Files.createSymbolicLink(base.resolve("rootlink"), root);
        AttachmentStore s = new AttachmentStore(rootLink);

        s.save("a.txt", "hello".getBytes());
        check(Arrays.equals(s.read("a.txt"), "hello".getBytes()), "read");
        check(Files.exists(root.resolve("a.txt")), "file directly under root");
        s.save("a.txt", "x".getBytes());
        check(Arrays.equals(s.read("a.txt"), "x".getBytes()), "replace");
        s.save("empty", new byte[0]);
        check(s.read("empty").length == 0, "empty");
        check(s.delete("a.txt"), "delete true");
        check(!s.delete("a.txt"), "delete false");
        expect(NoSuchElementException.class, () -> s.read("a.txt"), "read absent");
        s.save("con1", new byte[]{1});
        s.save("COM0", new byte[]{1});
        s.save("nul_", new byte[]{1});
        s.save("a..b", new byte[]{1});
        s.save("x".repeat(255), new byte[]{1});

        String[] bad = {null, "", ".", "..", ".hidden", "../x", "a/b", "a\\b", "/etc/passwd",
            "%2e%2e%2f", "a\0b", "a\nb", "é", "a b", "x".repeat(256), "con", "NUL.txt",
            "Com1.tar.gz", "lpt9", "AUX.", "prn.x", "C:x", "a:b", "*", "∕"};
        for (String n : bad) {
            expect(InvalidNameException.class, () -> s.save(n, new byte[]{1}), "save bad " + n);
            expect(InvalidNameException.class, () -> s.read(n), "read bad " + n);
            expect(InvalidNameException.class, () -> s.delete(n), "delete bad " + n);
            try { s.read(n); } catch (InvalidNameException e) {
                check(n == null || n.isEmpty() || !e.getMessage().contains(n), "message leaks " + n);
            }
        }

        // symlinks
        Files.createSymbolicLink(root.resolve("link"), outside);
        expect(StorageException.class, () -> s.save("link", "PWN".getBytes()), "save symlink");
        expect(StorageException.class, () -> s.read("link"), "read symlink");
        expect(StorageException.class, () -> s.delete("link"), "delete symlink");
        check(Files.readString(outside).equals("SECRET"), "target untouched");
        check(Files.isSymbolicLink(root.resolve("link")), "link still there");
        Files.createSymbolicLink(root.resolve("dangling"), base.resolve("created"));
        expect(StorageException.class, () -> s.save("dangling", "PWN".getBytes()), "save dangling");
        check(!Files.exists(base.resolve("created")), "dangling target not created");
        expect(StorageException.class, () -> s.read("dangling"), "read dangling");
        expect(StorageException.class, () -> s.delete("dangling"), "delete dangling");

        // directory at name
        Files.createDirectory(root.resolve("dir"));
        expect(StorageException.class, () -> s.save("dir", new byte[]{1}), "save dir");
        expect(StorageException.class, () -> s.read("dir"), "read dir");
        expect(StorageException.class, () -> s.delete("dir"), "delete dir");

        System.out.println(fails == 0 ? "ALL OK" : fails + " failure(s)");
        System.exit(fails == 0 ? 0 : 1);
    }
}
