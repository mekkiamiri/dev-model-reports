import com.example.files.AttachmentStore;
import com.example.files.InvalidNameException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

/** Tests d'acceptation cachés de la tâche secu-fichiers (fonctionnel seulement). L'agent ne les voit pas. */
public class AcceptanceTests {
    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static final List<Path> CREES = new ArrayList<>();

    static Path dossier() throws Exception {
        Path d = Files.createTempDirectory("dmr-fichiers-");
        synchronized (CREES) {
            if (CREES.isEmpty()) {
                Runtime.getRuntime().addShutdownHook(new Thread(AcceptanceTests::nettoyer));
            }
            CREES.add(d);
        }
        return d;
    }

    /** Supprime les dossiers temporaires à la sortie, sans suivre les liens. */
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

    static AttachmentStore store() throws Exception {
        return new AttachmentStore(dossier());
    }

    public static void main(String[] args) {
        Check.start();
        Check.test("enregistrer puis lire", () -> {
            AttachmentStore s = store();
            s.save("facture.pdf", b("contenu"));
            Check.isTrue(Arrays.equals(b("contenu"), s.read("facture.pdf")), "contenu relu différent");
        });
        Check.test("enregistrer remplace le contenu", () -> {
            AttachmentStore s = store();
            s.save("a.txt", b("long contenu initial"));
            s.save("a.txt", b("court"));
            Check.eq("court", new String(s.read("a.txt"), StandardCharsets.UTF_8));
        });
        Check.test("supprimer rend true puis la pièce est absente", () -> {
            AttachmentStore s = store();
            s.save("a.txt", b("x"));
            Check.eq(true, s.delete("a.txt"));
            Check.raises(NoSuchElementException.class, () -> s.read("a.txt"));
        });
        Check.test("supprimer une pièce absente rend false", () -> Check.eq(false, store().delete("absent.txt")));
        Check.test("lire une pièce absente", () -> Check.raises(NoSuchElementException.class, () -> store().read("absent.txt")));
        Check.test("fichier directement sous la racine", () -> {
            Path root = dossier();
            new AttachmentStore(root).save("note.txt", b("n"));
            Check.isTrue(Files.isRegularFile(root.resolve("note.txt")), "racine/note.txt absent");
            Check.eq("n", Files.readString(root.resolve("note.txt")));
        });
        Check.test("contenu vide", () -> {
            AttachmentStore s = store();
            s.save("vide", new byte[0]);
            Check.eq(0, s.read("vide").length);
        });
        Check.test("contenu binaire intact", () -> {
            byte[] all = new byte[256];
            for (int i = 0; i < 256; i++) {
                all[i] = (byte) i;
            }
            AttachmentStore s = store();
            s.save("bin.dat", all);
            Check.isTrue(Arrays.equals(all, s.read("bin.dat")), "octets modifiés");
        });
        Check.test("nom avec point, tiret, souligné et chiffres", () -> {
            AttachmentStore s = store();
            s.save("Rapport_2026-11.v2.pdf", b("r"));
            Check.eq("r", new String(s.read("Rapport_2026-11.v2.pdf"), StandardCharsets.UTF_8));
        });
        Check.test("nom de 255 caractères accepté", () -> {
            AttachmentStore s = store();
            String n = "a".repeat(255);
            s.save(n, b("long"));
            Check.eq("long", new String(s.read(n), StandardCharsets.UTF_8));
        });
        Check.test("noms proches des noms réservés acceptés", () -> {
            AttachmentStore s = store();
            for (String n : new String[] {"console.txt", "COM10", "nulle.txt", "LPT0"}) {
                s.save(n, b(n));
                Check.eq(n, new String(s.read(n), StandardCharsets.UTF_8));
            }
        });
        Check.test("racine absente refusée", () -> Check.raises(IllegalArgumentException.class,
                () -> new AttachmentStore(dossier().resolve("nexiste-pas"))));
        Check.test("racine qui est un fichier refusée", () -> {
            Path f = dossier().resolve("fichier");
            Files.writeString(f, "x");
            Check.raises(IllegalArgumentException.class, () -> new AttachmentStore(f));
        });
        Check.test("racine null refusée", () -> Check.raises(IllegalArgumentException.class, () -> new AttachmentStore(null)));
        Check.test("nom vide refusé", () -> Check.raises(InvalidNameException.class, () -> store().save("", b("x"))));
        Check.test("nom null refusé", () -> Check.raises(InvalidNameException.class, () -> store().read(null)));
        Check.test("nom avec espace refusé", () -> Check.raises(InvalidNameException.class, () -> store().save("mon fichier", b("x"))));
        Check.test("nom commençant par un point refusé", () -> Check.raises(InvalidNameException.class, () -> store().save(".cache", b("x"))));
        Check.test("InvalidNameException est une IllegalArgumentException", () ->
                Check.isTrue(IllegalArgumentException.class.isAssignableFrom(InvalidNameException.class), "mauvaise hiérarchie"));
        Check.test("deux magasins sur la même racine", () -> {
            Path root = dossier();
            new AttachmentStore(root).save("partage.txt", b("p"));
            Check.eq("p", new String(new AttachmentStore(root).read("partage.txt"), StandardCharsets.UTF_8));
        });
        Check.test("racine donnée par un chemin non normalisé", () -> {
            Path root = dossier();
            Files.createDirectory(root.resolve("sous"));
            new AttachmentStore(root.resolve("sous").resolve("..").resolve("sous")).save("x.txt", b("x"));
            Check.isTrue(Files.exists(root.resolve("sous").resolve("x.txt")), "fichier mal placé");
        });
        Check.finish();
    }
}
