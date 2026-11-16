import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Mini-harnais de tests sans dépendance (pas de JUnit) : javac et java suffisent.
 * Chaque test est indépendant ; la dernière ligne imprimée est
 * « RESULT <jeton> passed=X total=Y », lue par bench/run.py.
 *
 * Le jeton est tiré au hasard par le lanceur à chaque passage et lu sur l'entrée
 * standard au chargement de cette classe, avant que le code testé ne s'exécute :
 * un code qui imprime lui-même une fausse ligne RESULT ne le connaît pas.
 */
public final class Check {
    private static int passed;
    private static int total;
    private static final List<String> failures = new ArrayList<>();
    private static final String TOKEN = readToken();

    private Check() {}

    private static String readToken() {
        try {
            String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            return line == null ? "sans-jeton" : line.trim();
        } catch (IOException e) {
            return "sans-jeton";
        }
    }

    @FunctionalInterface
    public interface Body {
        void run() throws Exception;
    }

    /** À appeler en premier dans main : lit le jeton avant tout code de l'agent. */
    public static void start() {
        // le chargement de la classe suffit (TOKEN est initialisé ici)
    }

    public static void test(String name, Body body) {
        total++;
        try {
            body.run();
            passed++;
        } catch (Throwable t) {
            failures.add(name + " : " + t);
        }
    }

    public static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("attendu <" + expected + "> obtenu <" + actual + ">");
        }
    }

    public static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static <T extends Throwable> T raises(Class<T> type, Body body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            throw new AssertionError("attendu " + type.getSimpleName() + ", obtenu " + t);
        }
        throw new AssertionError("attendu " + type.getSimpleName() + ", rien n'a été levé");
    }

    public static void finish() {
        for (String f : failures) {
            System.out.println("ECHEC " + f);
        }
        System.out.println("RESULT " + TOKEN + " passed=" + passed + " total=" + total);
        System.exit(passed == total ? 0 : 1);
    }
}
