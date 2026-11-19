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

    /** Fuzzing différentiel : même valeur, ou même type d'exception, que la référence. */
    public static void memeIssue(String entree, Callable<?> agent, Callable<?> reference) {
        String attendu = issue(reference);
        String obtenu = issue(agent);
        if (!attendu.equals(obtenu)) {
            String e = entree.length() > 120 ? entree.substring(0, 120) + "…" : entree;
            throw new AssertionError("entrée " + e.replace("\n", "\\n") + " : attendu " + attendu + ", obtenu " + obtenu);
        }
    }
}
