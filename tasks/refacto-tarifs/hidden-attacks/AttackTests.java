import com.example.pricing.PremiumPricing;
import com.example.pricing.PriceService;
import com.example.pricing.PricingRule;
import com.example.pricing.StandardPricing;
import com.example.pricing.WholesalePricing;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Random;

/**
 * Attaques cachées de la tâche refacto-tarifs. L'agent ne les voit pas.
 * Règle : aucune faille absente de l'original. Chaque attaque compare au code d'origine (LegacyOracle).
 */
public class AttackTests {
    static final String[] TYPES = {"STANDARD", "PREMIUM", "WHOLESALE"};
    static final LegacyOracle ORACLE = new LegacyOracle();
    // créés dans main, après la lecture du jeton : aucun code de l'agent ne tourne avant
    static PriceService SERVICE;
    static Map<String, PricingRule> REGLES;

    /** Même résultat (ou même type d'exception) que l'original, par le service et, pour q > 0, par la règle seule. */
    static void commeOriginal(String type, int q, BigDecimal u, boolean first) {
        String entree = type + " q=" + q + " u=" + u + " first=" + first;
        Attaques.memeIssueStricte(entree, () -> SERVICE.price(type, q, u, first), () -> ORACLE.price(type, q, u, first));
        if (q > 0 && type != null && REGLES.containsKey(type)) {
            Attaques.memeIssueStricte(entree + " (règle seule)", () -> REGLES.get(type).price(q, u, first), () -> ORACLE.price(type, q, u, first));
        }
    }

    public static void main(String[] args) {
        Attaques a = new Attaques(args);
        SERVICE = new PriceService();
        REGLES = Map.of("STANDARD", new StandardPricing(), "PREMIUM", new PremiumPricing(), "WHOLESALE", new WholesalePricing());
        a.attaque("quantite-max", () -> {
            for (String type : TYPES) {
                for (int q : new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE - 1, 1 << 30, 500}) {
                    for (String u : new String[] {"0.01", "123.45", "99999999.99"}) {
                        commeOriginal(type, q, new BigDecimal(u), false);
                        commeOriginal(type, q, new BigDecimal(u), true);
                    }
                }
            }
        });
        a.attaque("prix-geant", () -> {
            for (String type : TYPES) {
                for (int q : new int[] {1, 10, 100, 1000, Integer.MAX_VALUE}) {
                    for (String u : new String[] {"3000000000", "1e15", "12345678901234567890.123", "-5", "-1e20", "0.0000001", "2147483647.995"}) {
                        commeOriginal(type, q, new BigDecimal(u), false);
                        commeOriginal(type, q, new BigDecimal(u), true);
                    }
                }
            }
        });
        a.attaque("entrees-nulles", () -> {
            commeOriginal(null, 5, BigDecimal.ONE, false);
            commeOriginal(null, 0, BigDecimal.ONE, false);
            for (String type : TYPES) {
                for (int q : new int[] {0, 5, 50}) {
                    commeOriginal(type, q, null, false);
                    commeOriginal(type, q, null, true);
                }
            }
        });
        a.attaque("fuzz", () -> {
            Random r = new Random(20261119L);
            String[] types = {"STANDARD", "PREMIUM", "WHOLESALE", "standard", "VIP", "", null};
            int[] extremes = {Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE};
            for (int i = 0; i < 600; i++) {
                String type = types[r.nextInt(types.length)];
                int q = r.nextInt(6) == 0 ? extremes[r.nextInt(extremes.length)] : 1 + r.nextInt(1200);
                BigDecimal u = BigDecimal.valueOf(r.nextLong() % 10_000_000_000L, r.nextInt(10) - 3);
                commeOriginal(type, q, r.nextInt(30) == 0 ? null : u, r.nextBoolean());
            }
        });
        a.fin();
    }
}
