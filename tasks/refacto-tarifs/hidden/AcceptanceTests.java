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
        Check.start();
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
        Check.test("arrondi commercial au centime", () ->
                Check.eq("1.67", service.price("STANDARD", 5, new BigDecimal("0.333"), false).toPlainString()));
        Check.test("standard : remise de bienvenue calculée après la remise de volume", () ->
                Check.eq("47.50", service.price("STANDARD", 100, new BigDecimal("0.50"), true).toPlainString()));
        Check.test("premium : la première commande ne change rien", () ->
                Check.eq(service.price("PREMIUM", 7, new BigDecimal("3.10"), false), service.price("PREMIUM", 7, new BigDecimal("3.10"), true)));
        Check.test("premium : remise de volume dès 50", () ->
                Check.eq("427.50", service.price("PREMIUM", 50, BigDecimal.TEN, false).toPlainString()));
        Check.test("grossiste : plancher à zéro", () ->
                Check.eq("0.00", service.price("WHOLESALE", 10, BigDecimal.ONE, true).toPlainString()));
        Check.test("grossiste : minimum 10 unités", () -> Check.raises(IllegalArgumentException.class,
                () -> service.price("WHOLESALE", 9, BigDecimal.TEN, false)));
        Check.test("résultat stable d'un appel à l'autre", () -> {
            PriceService other = new PriceService();
            for (int i = 0; i < 3; i++) {
                Check.eq(oracle.price("WHOLESALE", 500, new BigDecimal("2.5"), true), other.price("WHOLESALE", 500, new BigDecimal("2.5"), true));
            }
        });
        Check.test("une classe par règle, dans son propre fichier", () -> {
            for (String name : RULE_CLASSES) {
                Check.isTrue(Files.exists(Path.of("src/com/example/pricing/" + name + ".java")), name + ".java absent");
            }
        });
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
