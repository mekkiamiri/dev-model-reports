import com.example.config.ConfigException;
import com.example.config.ConfigResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/** Attaques cachées de la tâche prd-config. L'agent ne les voit pas. */
public class AttackTests {
    /** a0 = base, a(i) = ${a(i-1)}${a(i-1)} : 2^niveaux remplacements si rien n'est mémorisé. */
    static String doublement(String base, int niveaux) {
        StringBuilder sb = new StringBuilder("a0 = ").append(base).append('\n');
        for (int i = 1; i <= niveaux; i++) {
            sb.append('a').append(i).append(" = ${a").append(i - 1).append("}${a").append(i - 1).append("}\n");
        }
        return sb.toString();
    }

    /** k0 -> k1 -> … -> k(n-1) -> fin (ou -> k0 si cycle). */
    static String chaine(int n, boolean cycle) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n - 1; i++) {
            sb.append('k').append(i).append(" = ${k").append(i + 1).append("}\n");
        }
        sb.append('k').append(n - 1).append(cycle ? " = ${k0}\n" : " = fin\n");
        return sb.toString();
    }

    static final String[] JETONS = {"x", "${a}", "${b}", "${c:-d}", "${zz:-}", "$$", "$", "${", "}", "${}", "!unset",
            "=", "${ a }", ":-", "${a:-${b}}", "#", "$${a}"};

    static String couche(Random r) {
        StringBuilder sb = new StringBuilder();
        int lignes = r.nextInt(5);
        for (int i = 0; i < lignes; i++) {
            switch (r.nextInt(10)) {
                case 0 -> sb.append("# commentaire");
                case 1 -> sb.append("sans egal");
                case 2 -> sb.append(" = vide");
                case 3 -> sb.append("");
                default -> {
                    sb.append((char) ('a' + r.nextInt(4))).append(r.nextBoolean() ? " = " : "=");
                    int n = r.nextInt(4);
                    for (int j = 0; j < n; j++) {
                        sb.append(JETONS[r.nextInt(JETONS.length)]);
                    }
                }
            }
            sb.append(r.nextInt(4) == 0 ? "\r\n" : "\n");
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        Attaques a = new Attaques(args);
        a.attaque("expansion-exponentielle", () -> {
            Map<String, String> m = ConfigResolver.resolve(List.of(doublement("", 60)));
            Check.eq(61, m.size());
            Check.eq("", m.get("a60"));
        });
        a.attaque("expansion-geante", () -> Attaques.borne(() -> {
            Map<String, String> m = ConfigResolver.resolve(List.of(doublement("xxxxxxxxxx", 40)));
            Check.isTrue(m.get("a40").length() == 10L << 40, "résultat tronqué sans exception");
        }, ConfigException.class));
        a.attaque("cycle-long", () -> {
            ConfigException e = Check.raises(ConfigException.class, () -> ConfigResolver.resolve(List.of(chaine(2000, true))));
            Check.isTrue(String.valueOf(e.getMessage()).contains("cycle"), "le message doit contenir « cycle »");
        });
        a.attaque("chaine-longue-sans-cycle", () -> {
            Map<String, String> m = ConfigResolver.resolve(List.of(chaine(2000, false)));
            Check.eq("fin", m.get("k0"));
            Check.eq(2000, m.size());
        });
        a.attaque("cle-geante", () -> {
            String cle = "c".repeat(1_000_000);
            String valeur = "v".repeat(1_000_000);
            Map<String, String> m = ConfigResolver.resolve(List.of(cle + " = " + valeur + "\n"));
            Check.eq(1, m.size());
            Check.eq(valeur, m.get(cle));
        });
        a.attaque("valeur-geante", () -> {
            Map<String, String> m = ConfigResolver.resolve(List.of("x = ab\ny = " + "$$".repeat(300_000) + "${x}".repeat(200_000) + "\n"));
            Check.eq("$".repeat(300_000) + "ab".repeat(200_000), m.get("y"));
        });
        a.attaque("fuzz", () -> {
            Random r = new Random(20261119L);
            for (int i = 0; i < 500; i++) {
                List<String> couches = new ArrayList<>();
                int n = 1 + r.nextInt(3);
                for (int j = 0; j < n; j++) {
                    couches.add(couche(r));
                }
                Attaques.memeIssue(couches.toString(),
                        () -> new TreeMap<>(ConfigResolver.resolve(couches)),
                        () -> new TreeMap<>(dmrref.config.ConfigResolver.resolve(couches)));
            }
        });
        a.fin();
    }
}
