import com.example.pricing.PriceService;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Tests d'acceptation cachés de la tâche refacto-tarifs. L'agent ne les voit pas. */
public class AcceptanceTests {
    static final List<String> TYPES = List.of("STANDARD", "PREMIUM", "WHOLESALE");
    static final int[] QUANTITIES = {1, 5, 9, 10, 11, 49, 50, 51, 99, 100, 101, 499, 500, 501, 1000};
    static final String[] PRICES = {"0", "0.01", "0.333", "1", "2.5", "7.99", "10", "19.995", "123.45"};
    static final String[] RULE_CLASSES = {"StandardPricing", "PremiumPricing", "WholesalePricing"};

    interface Call {
        BigDecimal get() throws Exception;
    }

    static String outcome(Call call) {
        try {
            return call.get().toPlainString();
        } catch (java.lang.reflect.InvocationTargetException e) {
            return "exception " + e.getCause().getClass().getSimpleName();
        } catch (Exception e) {
            return "exception " + e.getClass().getSimpleName();
        }
    }

    static Class<?> cls(String simpleName) throws ClassNotFoundException {
        return Class.forName("com.example.pricing." + simpleName);
    }

    public static void main(String[] args) {
        LegacyOracle oracle = new LegacyOracle();
        PriceService service = new PriceService();

        Check.test("PriceService : même résultat que l'original sur toute la grille", () -> {
            for (String type : TYPES) {
                for (int q : QUANTITIES) {
                    for (String price : PRICES) {
                        for (boolean first : new boolean[] {false, true}) {
                            BigDecimal u = new BigDecimal(price);
                            String expected = outcome(() -> oracle.price(type, q, u, first));
                            String actual = outcome(() -> service.price(type, q, u, first));
                            Check.isTrue(expected.equals(actual),
                                    type + " q=" + q + " u=" + price + " first=" + first + " : attendu " + expected + ", obtenu " + actual);
                        }
                    }
                }
            }
        });
        Check.test("quantité nulle refusée", () -> Check.raises(IllegalArgumentException.class,
                () -> service.price("STANDARD", 0, BigDecimal.ONE, false)));
        Check.test("quantité négative refusée pour un type inconnu", () -> Check.raises(IllegalArgumentException.class,
                () -> service.price("VIP", -1, BigDecimal.ONE, false)));
        Check.test("type inconnu refusé", () -> Check.raises(IllegalArgumentException.class,
                () -> service.price("standard", 1, BigDecimal.ONE, false)));
        Check.test("PricingRule est une interface avec la bonne méthode", () -> {
            Class<?> rule = cls("PricingRule");
            Check.isTrue(rule.isInterface(), "PricingRule doit être une interface");
            Method m = rule.getMethod("price", int.class, BigDecimal.class, boolean.class);
            Check.eq(BigDecimal.class, m.getReturnType());
        });
        for (int i = 0; i < RULE_CLASSES.length; i++) {
            String name = RULE_CLASSES[i];
            String type = TYPES.get(i);
            Check.test(name + " : implémente PricingRule et reproduit " + type, () -> {
                Class<?> c = cls(name);
                Check.isTrue(cls("PricingRule").isAssignableFrom(c), name + " n'implémente pas PricingRule");
                Check.isTrue(Modifier.isPublic(c.getModifiers()), name + " doit être publique");
                Object rule = c.getConstructor().newInstance();
                Method m = c.getMethod("price", int.class, BigDecimal.class, boolean.class);
                for (int q : QUANTITIES) {
                    for (String price : PRICES) {
                        for (boolean first : new boolean[] {false, true}) {
                            BigDecimal u = new BigDecimal(price);
                            String expected = outcome(() -> oracle.price(type, q, u, first));
                            String actual = outcome(() -> (BigDecimal) m.invoke(rule, q, u, first));
                            Check.isTrue(expected.equals(actual),
                                    name + " q=" + q + " u=" + price + " first=" + first + " : attendu " + expected + ", obtenu " + actual);
                        }
                    }
                }
            });
        }
        Check.test("PriceService ne contient plus de règle de calcul", () -> {
            String src = Files.readString(Path.of("src/com/example/pricing/PriceService.java"), StandardCharsets.UTF_8);
            for (String rate : new String[] {"0.95", "0.90", "0.80", "0.97"}) {
                Check.isTrue(!src.contains(rate), "le taux " + rate + " est encore dans PriceService");
            }
            Check.isTrue(!src.contains("else if"), "chaîne de else if encore présente");
        });
        Check.test("PriceService.java fait 40 lignes non vides au plus", () -> {
            long lines = Files.readAllLines(Path.of("src/com/example/pricing/PriceService.java"), StandardCharsets.UTF_8)
                    .stream().filter(l -> !l.isBlank()).count();
            Check.isTrue(lines <= 40, lines + " lignes non vides");
        });
        Check.finish();
    }
}
