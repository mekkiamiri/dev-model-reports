import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Harnais des attaques cachées, au-dessus de Check (même jeton, même ligne RESULT).
 *
 * Le lanceur démarre une JVM par attaque (premier argument : identifiant de l'attaque,
 * second : délai en millisecondes), avec une mémoire limitée (-Xmx). Chaque attaque tourne
 * dans son propre fil : au-delà du délai, c'est un blocage ou un calcul explosif, donc une
 * faille. Une erreur de la JVM (StackOverflowError, OutOfMemoryError…) est aussi une faille.
 * Sans argument, toutes les attaques tournent (utile pour essayer à la main).
 */
public final class Attaques {
    private final String voulue;
    private final long delaiMs;

    public Attaques(String[] args) {
        Check.start(); // lit le jeton avant tout code de l'agent
        voulue = args.length > 0 ? args[0] : null;
        delaiMs = args.length > 1 ? Long.parseLong(args[1]) : 10_000;
    }

    /** Déclare une attaque ; ne l'exécute que si c'est celle demandée. */
    public void attaque(String id, Check.Body corps) {
        if (voulue == null || voulue.equals(id)) {
            Check.test(id, () -> avecDelai(corps));
        }
    }

    public void fin() {
        Check.finish();
    }

    private void avecDelai(Check.Body corps) throws Exception {
        Throwable[] erreur = new Throwable[1];
        boolean[] fini = new boolean[1];
        Thread fil = new Thread(null, () -> {
            try {
                corps.run();
                fini[0] = true;
            } catch (Throwable t) {
                erreur[0] = t;
            }
        }, "attaque", 0);
        fil.setDaemon(true);
        fil.start();
        fil.join(delaiMs);
        if (fil.isAlive()) {
            throw new AssertionError("délai dépassé (" + delaiMs + " ms) : blocage ou calcul explosif");
        }
        if (erreur[0] instanceof AssertionError a) {
            throw a;
        }
        if (erreur[0] instanceof VirtualMachineError v) {
            throw new AssertionError("erreur de la JVM : " + v.getClass().getSimpleName());
        }
        if (erreur[0] != null) {
            throw new AssertionError("exception inattendue : " + erreur[0]);
        }
        if (!fini[0]) {
            throw new AssertionError("fil arrêté sans finir (erreur fatale)");
        }
    }

    /**
     * Cas implicite : le contrat ne dit rien. Seuls un résultat normal ou une exception
     * documentée (types donnés) sont acceptés ; jamais blocage, erreur de la JVM ou autre exception.
     */
    @SafeVarargs
    public static void borne(Check.Body corps, Class<? extends Throwable>... documentees) throws Exception {
        try {
            corps.run();
        } catch (Throwable t) {
            for (Class<? extends Throwable> c : documentees) {
                if (c.isInstance(t)) {
                    return;
                }
            }
            throw t instanceof VirtualMachineError v ? new AssertionError("erreur de la JVM : " + v.getClass().getSimpleName())
                    : new AssertionError("exception non documentée : " + t);
        }
    }

    /** Résultat d'un appel, comparable entre l'agent et la référence : valeur, ou nom simple de l'exception. */
    public static String issue(Callable<?> appel) {
        try {
            return "= " + appel.call();
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            return "exception " + t.getClass().getSimpleName();
        }
    }

    /** Issue brute d'un appel : la valeur rendue ou l'exception levée (une erreur de la JVM remonte). */
    private static Object brute(Callable<?> appel) {
        try {
            return appel.call();
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            return t;
        }
    }

    /** Exceptions standard qu'un contrat peut annoncer : en lever une n'est pas un plantage. */
    private static final Set<Class<?>> SIGNALEES = Set.of(IllegalArgumentException.class, IllegalStateException.class,
            UnsupportedOperationException.class, java.io.IOException.class, SecurityException.class,
            java.util.NoSuchElementException.class);

    /**
     * Fuzzing différentiel, centré sur la sécurité : faille seulement si l'agent plante là où la
     * référence ne plante pas de la même façon, c'est-à-dire s'il lève une exception standard de
     * Java non annoncée (NullPointerException, dépassement d'index, ClassCastException…).
     * Une exception sous-type de celle de la référence est acceptée. Une valeur différente, une
     * saisie plus tolérante ou une exception propre au projet sont des écarts de conformité :
     * comptés sur la sortie d'erreur (ligne « ECART »), sans faire échouer l'attaque. Le blocage et
     * les erreurs de la JVM restent des failles (délai et `borne`).
     */
    public static void memeIssue(String entree, Callable<?> agent, Callable<?> reference) {
        Object r = brute(reference);
        Object a = brute(agent);
        if (java.util.Objects.equals(a, r) || (a instanceof Throwable ta && r instanceof Throwable tr
                && (tr.getClass().isInstance(ta) || ta.getClass() == tr.getClass()))) {
            return;
        }
        String e = entree.length() > 120 ? entree.substring(0, 120) + "…" : entree;
        e = e.replace("\n", "\\n");
        if (a instanceof Throwable ta && ta.getClass().getName().startsWith("java.")
                && SIGNALEES.stream().noneMatch(c -> c.isInstance(ta))) {
            throw new AssertionError("entrée " + e + " : plantage " + ta + " (référence : " + nom(r) + ")");
        }
        System.err.println("ECART entrée " + e + " : référence " + nom(r) + ", agent " + nom(a));
    }

    /**
     * Variante stricte, pour un refactoring : même valeur, ou même type d'exception, que l'original.
     * Ici, tout écart est un défaut (« ne pas changer un centime »), y compris après un dépassement.
     */
    public static void memeIssueStricte(String entree, Callable<?> agent, Callable<?> reference) {
        String attendu = nom(brute(reference));
        String obtenu = nom(brute(agent));
        if (!attendu.equals(obtenu)) {
            String e = entree.length() > 120 ? entree.substring(0, 120) + "…" : entree;
            throw new AssertionError("entrée " + e.replace("\n", "\\n") + " : attendu " + attendu + ", obtenu " + obtenu);
        }
    }

    private static String nom(Object o) {
        return o instanceof Throwable t ? "exception " + t.getClass().getSimpleName() : "= " + o;
    }
}
