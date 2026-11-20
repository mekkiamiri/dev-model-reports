import com.example.files.*;

import java.nio.file.*;
import java.util.*;

public class StoreTest {
    static int fails = 0;

    static void check(boolean cond, String label) {
        if (!cond) { fails++; System.out.println("FAIL " + label); }
    }

    interface R { void run() throws Exception; }

    static void expect(Class<? extends Throwable> type, R r, String label) {
        try { r.run(); check(false, label + " (no exception)"); }
        catch (Throwable t) {
            check(type.isInstance(t), label + " got " + t);
        }
    }

    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory("store");
        Path outside = Files.createTempDirectory("outside");
        AttachmentStore s = new AttachmentStore(dir);

        expect(IllegalArgumentException.class, () -> new AttachmentStore(null), "null root");
        expect(IllegalArgumentException.class, () -> new AttachmentStore(dir.resolve("nope")), "missing root");
        Path f = Files.writeString(outside.resolve("f"), "x");
        expect(IllegalArgumentException.class, () -> new AttachmentStore(f), "file root");

        s.save("a.txt", "hello".getBytes());
        check(Arrays.equals(s.read("a.txt"), "hello".getBytes()), "read");
        s.save("a.txt", "bye".getBytes());
        check(Arrays.equals(s.read("a.txt"), "bye".getBytes()), "overwrite");
        s.save("empty", new byte[0]);
        check(s.read("empty").length == 0, "empty");
        check(s.delete("a.txt"), "delete true");
        check(!s.delete("a.txt"), "delete false");
        expect(NoSuchElementException.class, () -> s.read("a.txt"), "read missing");
        s.save("null", new byte[]{1});
        s.save("COM0", new byte[]{1});
        s.save("CONSOLE.txt", new byte[]{1});
        s.save("x".repeat(255), new byte[]{1});

        String[] bad = {null, "", ".", "..", ".hidden", "a/b", "a\\b", "/etc/passwd", "../x",
                "%2e%2e%2f", "a\u0000b", "a\nb", "é", "con", "NUL.txt", "Com1.tar.gz", "lpt9",
                "x".repeat(256), "a b", "a:b", "AUX."};
        for (String b : bad) {
            expect(InvalidNameException.class, () -> s.save(b, new byte[1]), "save bad");
            expect(InvalidNameException.class, () -> s.read(b), "read bad");
            expect(InvalidNameException.class, () -> s.delete(b), "delete bad");
        }
        try { s.read("a\nevil"); } catch (InvalidNameException e) {
            check(!e.getMessage().contains("evil"), "message leaks name");
        }

        Path target = Files.writeString(outside.resolve("secret"), "secret");
        Files.createSymbolicLink(dir.resolve("link"), target);
        expect(StorageException.class, () -> s.read("link"), "read link");
        expect(StorageException.class, () -> s.save("link", "pwn".getBytes()), "save link");
        expect(StorageException.class, () -> s.delete("link"), "delete link");
        check(Files.readString(target).equals("secret"), "target untouched");
        check(Files.isSymbolicLink(dir.resolve("link")), "link still there");
        Files.createSymbolicLink(dir.resolve("dangling"), outside.resolve("none"));
        expect(StorageException.class, () -> s.save("dangling", "pwn".getBytes()), "save dangling");
        check(!Files.exists(outside.resolve("none")), "dangling not created");
        Files.createDirectory(dir.resolve("sub"));
        expect(StorageException.class, () -> s.read("sub"), "read dir");
        expect(StorageException.class, () -> s.delete("sub"), "delete dir");

        // root given through a symlink
        Path rootLink = outside.resolve("rootlink");
        Files.createSymbolicLink(rootLink, dir);
        AttachmentStore s2 = new AttachmentStore(rootLink);
        check(Arrays.equals(s2.read("null"), new byte[]{1}), "root via link");

        try (var st = Files.list(dir)) {
            st.forEach(p -> check(!p.getFileName().toString().startsWith(".tmp"), "temp left"));
        }
        System.out.println(fails == 0 ? "ALL OK" : fails + " failures");
    }
}
