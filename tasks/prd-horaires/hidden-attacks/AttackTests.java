import com.example.hours.OpeningHours;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Callable;

/** Attaques cachées de la tâche prd-horaires. L'agent ne les voit pas. */
public class AttackTests {
    // Repère : 2026-11-16 est un lundi.
    static void invalide(String spec) {
        Check.raises(IllegalArgumentException.class, () -> OpeningHours.parse(spec));
    }

    static final String[] JOURS = {"Mo", "Tu", "Su", "Sa-Mo", "Mo-Fr", "Mo,We", "Xx", "Fr-", "mo", "Fr,Sa"};
    static final String[] PLAGES = {"09:00-12:00", "22:00-02:00", "00:00-24:00", "10:00-10:00", "24:00-01:00",
            "09:00-18:00,20:00-23:00", "25:00-26:00", "9:00-10:00", "12:00-11:59", "off"};
    static final String[] DATES = {"2026-11-20", "2026-11-21", "2026-13-01", "2026-11-22", "2026-11-16"};

    static String regle(Random r) {
        String tete = r.nextInt(3) == 0 ? DATES[r.nextInt(DATES.length)] : JOURS[r.nextInt(JOURS.length)];
        return tete + " " + PLAGES[r.nextInt(PLAGES.length)];
    }

    /** Ce qu'on compare : la syntaxe refusée (toute IllegalArgumentException), ou les réponses en quelques instants. */
    static Callable<String> reponses(String spec, LocalDateTime[] instants, boolean agent) {
        return () -> {
            StringBuilder sb = new StringBuilder();
            try {
                if (agent) {
                    OpeningHours h = OpeningHours.parse(spec);
                    for (LocalDateTime t : instants) {
                        sb.append(h.isOpen(t)).append(' ').append(h.nextOpening(t)).append("; ");
                    }
                } else {
                    dmrref.hours.OpeningHours h = dmrref.hours.OpeningHours.parse(spec);
                    for (LocalDateTime t : instants) {
                        sb.append(h.isOpen(t)).append(' ').append(h.nextOpening(t)).append("; ");
                    }
                }
            } catch (IllegalArgumentException e) {
                return "syntaxe refusée";
            }
            return sb.toString();
        };
    }

    public static void main(String[] args) {
        Attaques a = new Attaques(args);
        a.attaque("regles-nombreuses", () -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 150_000; i++) {
                sb.append("Mo 08:00-09:00;");
            }
            sb.append("Mo 09:00-10:00");
            LocalDate d = LocalDate.parse("2026-11-17");
            for (int i = 0; i < 150_000; i++) {
                sb.append(';').append(d.plusDays(i)).append(" off");
            }
            OpeningHours h = OpeningHours.parse(sb.toString());
            Check.eq(true, h.isOpen(LocalDateTime.parse("2026-11-16T09:30")));
            Check.eq(false, h.isOpen(LocalDateTime.parse("2026-11-16T08:30")));
            Check.eq(Optional.empty(), h.nextOpening(LocalDateTime.parse("2026-11-16T10:00")));
        });
        a.attaque("heures-hors-bornes", () -> {
            invalide("Mo 10:00-24:30");
            invalide("Mo 10:00-24:01");
            invalide("Mo 24:00-02:00");
            invalide("Mo 99:99-10:00");
            invalide("Mo 09:00-10:00:00");
            invalide("Mo -1:00-02:00");
        });
        a.attaque("syntaxe-mal-formee", () -> {
            for (String s : new String[] {"Mo-", "-Fr 09:00-10:00", "Mo,,Tu 09:00-10:00", "Mo, 09:00-10:00",
                    "Mo 09:00-10:00,", "Mo 09:00-10:00;", "2026-02-30 off", "2026-02-29 off", "2026-1-01 off",
                    "Mo-Tu-We 09:00-10:00", "mo 09:00-10:00", "Mo 09:00–10:00", "Mo\u000009:00-10:00", ";"}) {
                invalide(s);
            }
        });
        a.attaque("entree-geante", () -> {
            invalide("x".repeat(2_000_000));
            invalide("Mo-Fr " + "0".repeat(1_000_000));
            invalide("Mo,".repeat(300_000) + "Xx 09:00-10:00");
            invalide("Mo " + "09:00-10:00,".repeat(200_000));
        });
        a.attaque("instant-extreme", () -> {
            OpeningHours h = OpeningHours.parse("Mo 09:00-10:00");
            Attaques.borne(() -> h.nextOpening(LocalDateTime.MAX), IllegalArgumentException.class, DateTimeException.class);
            Attaques.borne(() -> h.isOpen(LocalDateTime.MIN), IllegalArgumentException.class, DateTimeException.class);
            Attaques.borne(() -> h.nextOpening(LocalDateTime.MIN), IllegalArgumentException.class, DateTimeException.class);
        });
        a.attaque("fuzz", () -> {
            Random r = new Random(20261119L);
            LocalDateTime base = LocalDateTime.parse("2026-11-14T00:00");
            for (int i = 0; i < 400; i++) {
                StringBuilder sb = new StringBuilder(regle(r));
                int n = r.nextInt(3);
                for (int j = 0; j < n; j++) {
                    sb.append(r.nextBoolean() ? "; " : ";").append(regle(r));
                }
                LocalDateTime[] instants = new LocalDateTime[3];
                for (int j = 0; j < 3; j++) {
                    instants[j] = base.plusMinutes(r.nextInt(14 * 24 * 60)).plusSeconds(r.nextInt(4) == 0 ? 30 : 0);
                }
                String spec = sb.toString();
                Attaques.memeIssue(spec, reponses(spec, instants, true), reponses(spec, instants, false));
            }
        });
        a.fin();
    }
}
