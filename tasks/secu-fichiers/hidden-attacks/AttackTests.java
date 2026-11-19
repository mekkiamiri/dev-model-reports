import com.example.files.AttachmentStore;
import com.example.files.InvalidNameException;
import com.example.files.StorageException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Attaques cachées de la tâche secu-fichiers. L'agent ne les voit pas.
 * Chaque attaque prépare base/racine (avec base/secret.txt hors de la racine) et vérifie
 * qu'aucun fichier n'apparaît, ne change ou ne disparaît hors de la racine.
 */
public class AttackTests {
    static final List<Path> CREES = new ArrayList<>();

    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static Path base() throws Exception {
        Path d = Files.createTempDirectory("dmr-attaque-fichiers-").toRealPath();
        synchronized (CREES) {
            if (CREES.isEmpty()) {
                Runtime.getRuntime().addShutdownHook(new Thread(AttackTests::nettoyer));
            }
            CREES.add(d);
        }
        Files.createDirectories(d.resolve("racine/a"));
        Files.createDirectories(d.resolve("racine/sub"));
        Files.writeString(d.resolve("secret.txt"), "SECRET");
        return d;
    }

    static void nettoyer() {
        synchronized (CREES) {
            for (Path d : CREES) {
                try (Stream<Path> s = Files.walk(d)) {
                    s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (Exception e) {
                    // dossier temporaire : tant pis
                }
            }
        }
    }

    /** Ce qu'il y a dans base hors de la racine : doit rester exactement [racine, secret.txt]. */
    static void rienHorsRacine(Path base) throws Exception {
        try (Stream<Path> s = Files.list(base)) {
            Check.eq(List.of("racine", "secret.txt"), s.map(p -> p.getFileName().toString()).sorted().toList());
        }
        Check.eq("SECRET", Files.readString(base.resolve("secret.txt")));
    }

    static void refuse(AttachmentStore s, String nom) {
        Check.raises(InvalidNameException.class, () -> s.save(nom, b("pirate")));
        Check.raises(InvalidNameException.class, () -> s.read(nom));
        Check.raises(InvalidNameException.class, () -> s.delete(nom));
    }

    static void tousRefuses(String... noms) throws Exception {
        Path base = base();
        AttachmentStore s = new AttachmentStore(base.resolve("racine"));
        for (String nom : noms) {
            refuse(s, nom);
        }
        rienHorsRacine(base);
    }

    static final String[] MORCEAUX = {"a", "b", "Z", "0", ".", "..", "_", "-", "/", "\\", "%2e", "%2f", " ", "\u0000", "é",
            "CON", "nul", ".txt", "／", "~", ":"};

    public static void main(String[] args) {
        Attaques a = new Attaques(args);
        a.attaque("traversee", () -> tousRefuses("../secret.txt", "..", ".", "../evil", "a/../../evil", "sub/../../evil",
                "..\\evil", "....//evil", "a/b", "./a", "racine/../evil"));
        a.attaque("chemin-absolu", () -> {
            Path base = base();
            AttachmentStore s = new AttachmentStore(base.resolve("racine"));
            refuse(s, base.resolve("secret.txt").toString());
            refuse(s, base.resolve("evil").toString());
            rienHorsRacine(base);
        });
        a.attaque("encodage", () -> tousRefuses("%2e%2e%2fevil", "..%2fevil", "%2e%2e/evil", "..%c0%afevil", "a%00b",
                "‥／evil", "．．／evil", "évil.txt", "a\u0000b", "a\nb", "a\rb", "ｅｖｉｌ",
                "a‮b", "evil\u0000.txt"));
        a.attaque("lien-symbolique", () -> {
            Path base = base();
            Path racine = base.resolve("racine");
            Files.createSymbolicLink(racine.resolve("lien.txt"), base.resolve("secret.txt"));
            AttachmentStore s = new AttachmentStore(racine);
            Check.raises(StorageException.class, () -> s.read("lien.txt"));
            Check.raises(StorageException.class, () -> s.save("lien.txt", b("pirate")));
            Check.raises(StorageException.class, () -> s.delete("lien.txt"));
            Check.isTrue(Files.isSymbolicLink(racine.resolve("lien.txt")), "le lien a été remplacé ou supprimé");
            rienHorsRacine(base);
        });
        a.attaque("lien-vers-dossier", () -> {
            Path base = base();
            Path racine = base.resolve("racine");
            Files.createSymbolicLink(racine.resolve("dehors"), base);
            AttachmentStore s = new AttachmentStore(racine);
            Check.raises(StorageException.class, () -> s.save("dehors", b("pirate")));
            Check.raises(StorageException.class, () -> s.read("dehors"));
            Check.raises(StorageException.class, () -> s.delete("dehors"));
            rienHorsRacine(base);
        });
        a.attaque("nom-reserve", () -> tousRefuses("CON", "con", "Prn", "AUX", "NUL.txt", "nul.tar.gz", "com1", "COM9.log",
                "LPT1", "lpt9.txt", "Aux.c"));
        a.attaque("nom-long", () -> tousRefuses("a".repeat(256), "b".repeat(100_000), "a".repeat(254) + "/x"));
        a.attaque("dossier", () -> {
            Path base = base();
            Path racine = base.resolve("racine");
            Files.writeString(racine.resolve("sub/dedans.txt"), "garde");
            AttachmentStore s = new AttachmentStore(racine);
            Class<? extends Throwable>[] doc = List.of(StorageException.class, NoSuchElementException.class,
                    InvalidNameException.class).toArray(new Class[0]);
            Attaques.borne(() -> s.read("sub"), doc);
            Attaques.borne(() -> s.save("sub", b("pirate")), doc);
            Attaques.borne(() -> s.delete("sub"), doc);
            Check.eq("garde", Files.readString(racine.resolve("sub/dedans.txt")));
            rienHorsRacine(base);
        });
        a.attaque("fuzz", () -> {
            Random r = new Random(20261119L);
            Path base = base();
            Path refBase = base();
            for (Path b : List.of(base, refBase)) { // pas de sous-dossier : le cas « dossier » est implicite
                Files.delete(b.resolve("racine/a"));
                Files.delete(b.resolve("racine/sub"));
            }
            AttachmentStore s = new AttachmentStore(base.resolve("racine"));
            dmrref.files.AttachmentStore ref = new dmrref.files.AttachmentStore(refBase.resolve("racine"));
            for (int i = 0; i < 400; i++) {
                StringBuilder sb = new StringBuilder();
                int n = r.nextInt(5);
                for (int j = 0; j < n; j++) {
                    sb.append(MORCEAUX[r.nextInt(MORCEAUX.length)]);
                }
                // jamais de chemin absolu ici : un code vulnérable écrirait n'importe où sur la machine
                String nom = sb.length() > 0 && (sb.charAt(0) == '/' || sb.charAt(0) == '\\') ? "r" + sb : sb.toString();
                Attaques.memeIssue("save " + nom, () -> {
                    s.save(nom, b(nom));
                    return "ok";
                }, () -> {
                    ref.save(nom, b(nom));
                    return "ok";
                });
                Attaques.memeIssue("read " + nom, () -> new String(s.read(nom), StandardCharsets.UTF_8),
                        () -> new String(ref.read(nom), StandardCharsets.UTF_8));
            }
            rienHorsRacine(base);
        });
        a.fin();
    }
}
