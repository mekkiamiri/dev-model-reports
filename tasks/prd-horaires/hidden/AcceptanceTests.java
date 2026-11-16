import com.example.hours.OpeningHours;
import java.time.LocalDateTime;
import java.util.Optional;

/** Tests d'acceptation cachés de la tâche prd-horaires. L'agent ne les voit pas. */
public class AcceptanceTests {
    // Repères : 2026-11-16 est un lundi, 2026-11-20 un vendredi, 2026-12-25 un vendredi.
    static final String SHOP = "Mo-Fr 09:00-12:00,14:00-18:00; Sa 10:00-13:00";

    static LocalDateTime at(String iso) {
        return LocalDateTime.parse(iso);
    }

    static void open(String spec, String iso, boolean expected) {
        Check.eq(expected, OpeningHours.parse(spec).isOpen(at(iso)));
    }

    static void next(String spec, String from, String expected) {
        Check.eq(Optional.of(at(expected)), OpeningHours.parse(spec).nextOpening(at(from)));
    }

    static void invalid(String spec) {
        Check.raises(IllegalArgumentException.class, () -> OpeningHours.parse(spec));
    }

    public static void main(String[] args) {
        Check.test("ouvert à l'heure d'ouverture", () -> open(SHOP, "2026-11-16T09:00", true));
        Check.test("fermé juste avant", () -> open(SHOP, "2026-11-16T08:59", false));
        Check.test("fermé à l'heure de fin", () -> open(SHOP, "2026-11-16T12:00", false));
        Check.test("ouvert dans la seconde plage", () -> open(SHOP, "2026-11-16T17:59", true));
        Check.test("fermé le dimanche", () -> open(SHOP, "2026-11-22T11:00", false));
        Check.test("secondes prises en compte", () -> open(SHOP, "2026-11-16T11:59:59", true));
        Check.test("réouverture après la pause", () -> next(SHOP, "2026-11-16T12:30", "2026-11-16T14:00"));
        Check.test("réouverture le samedi", () -> next(SHOP, "2026-11-20T18:30", "2026-11-21T10:00"));
        Check.test("réouverture le lundi suivant", () -> next(SHOP, "2026-11-21T13:00", "2026-11-23T09:00"));
        Check.test("déjà ouvert : renvoie t", () -> next(SHOP, "2026-11-16T10:15:30", "2026-11-16T10:15:30"));
        Check.test("avant l'ouverture avec secondes", () -> next(SHOP, "2026-11-16T08:00:30", "2026-11-16T09:00"));
        String night = "Fr,Sa 22:00-02:00";
        Check.test("nuit : ouvert vendredi 23h", () -> open(night, "2026-11-20T23:00", true));
        Check.test("nuit : débordement samedi 1h", () -> open(night, "2026-11-21T01:00", true));
        Check.test("nuit : fermé samedi 2h", () -> open(night, "2026-11-21T02:00", false));
        Check.test("nuit : débordement dimanche 1h59", () -> open(night, "2026-11-22T01:59", true));
        Check.test("nuit : rien lundi 1h", () -> open(night, "2026-11-23T01:00", false));
        Check.test("nuit : prochaine ouverture", () -> next(night, "2026-11-20T21:00", "2026-11-20T22:00"));
        String wrap = "Sa-Mo 10:00-11:00";
        Check.test("intervalle par dimanche : dimanche", () -> open(wrap, "2026-11-22T10:30", true));
        Check.test("intervalle par dimanche : lundi", () -> open(wrap, "2026-11-23T10:30", true));
        Check.test("intervalle par dimanche : pas mardi", () -> open(wrap, "2026-11-24T10:30", false));
        String override = "Mo-Fr 09:00-18:00; We 10:00-12:00";
        Check.test("dernière règle : remplace", () -> open(override, "2026-11-18T09:30", false));
        Check.test("dernière règle : nouvelle plage", () -> open(override, "2026-11-18T10:30", true));
        Check.test("dernière règle : pas d'ajout", () -> open(override, "2026-11-18T15:00", false));
        Check.test("dernière règle : autres jours intacts", () -> open(override, "2026-11-19T15:00", true));
        String xmas = "Mo-Su 09:00-18:00; 2026-12-25 off";
        Check.test("date fermée", () -> open(xmas, "2026-12-25T10:00", false));
        Check.test("date fermée : réouverture le lendemain", () -> next(xmas, "2026-12-25T08:00", "2026-12-26T09:00"));
        Check.test("date avec plages", () -> open("Mo-Fr 09:00-18:00; 2026-12-24 09:00-12:00", "2026-12-24T13:00", false));
        Check.test("débordement sur un jour fermé", () -> open("Fr 22:00-02:00; 2026-12-26 off", "2026-12-26T01:00", true));
        Check.test("pas de débordement si le jour de départ est fermé",
                () -> open("Th 22:00-03:00; 2026-12-24 off", "2026-12-25T01:00", false));
        Check.test("fin à 24:00", () -> open("Su 20:00-24:00", "2026-11-22T23:59", true));
        Check.test("24:00 ne déborde pas", () -> open("Su 20:00-24:00", "2026-11-23T00:00", false));
        Check.test("espaces tolérés", () -> open(" Mo-Fr 09:00-10:00 , 11:00-12:00 ;  Sa 10:00-11:00 ", "2026-11-16T11:30", true));
        Check.test("jamais ouvert dans les 366 jours", () ->
                Check.eq(Optional.empty(), OpeningHours.parse("2020-01-01 10:00-11:00").nextOpening(at("2026-11-16T00:00"))));
        Check.test("ouverture lointaine trouvée", () -> next("2027-06-01 10:00-11:00", "2026-11-16T00:00", "2027-06-01T10:00"));
        Check.test("invalide : jour inconnu", () -> invalid("Xx 09:00-10:00"));
        Check.test("invalide : heure 25", () -> invalid("Mo 25:00-26:00"));
        Check.test("invalide : minute 60", () -> invalid("Mo 10:60-11:00"));
        Check.test("invalide : texte vide", () -> invalid(""));
        Check.test("invalide : règle vide", () -> invalid("Mo-Fr 09:00-12:00;;Sa 10:00-11:00"));
        Check.test("invalide : jours sans plages", () -> invalid("Mo"));
        Check.test("invalide : date impossible", () -> invalid("2026-13-01 off"));
        Check.finish();
    }
}
