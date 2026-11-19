import com.example.hours.OpeningHours;

import java.time.LocalDateTime;
import java.util.Optional;

public class OpeningHoursTest {
    static int fail = 0, ok = 0;

    static void check(String name, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            fail++;
            System.out.println("ÉCHEC " + name + " : attendu " + expected + ", obtenu " + actual);
        } else {
            ok++;
        }
    }

    static LocalDateTime dt(String s) { return LocalDateTime.parse(s); }

    static void invalid(String spec) {
        try {
            OpeningHours.parse(spec);
            fail++;
            System.out.println("ÉCHEC : pas d'exception pour '" + spec + "'");
        } catch (IllegalArgumentException e) {
            ok++;
        }
    }

    public static void main(String[] args) {
        // 2024-01-01 est un lundi
        OpeningHours h = OpeningHours.parse("Mo-Fr 09:00-12:00,14:00-18:00");
        check("lun 9h", true, h.isOpen(dt("2024-01-01T09:00")));
        check("lun 12h", false, h.isOpen(dt("2024-01-01T12:00")));
        check("lun 17:59", true, h.isOpen(dt("2024-01-01T17:59")));
        check("lun 18h", false, h.isOpen(dt("2024-01-01T18:00")));
        check("sam", false, h.isOpen(dt("2024-01-06T10:00")));
        check("next ouvert", Optional.of(dt("2024-01-01T10:00")), h.nextOpening(dt("2024-01-01T10:00")));
        check("next midi", Optional.of(dt("2024-01-01T14:00")), h.nextOpening(dt("2024-01-01T12:30")));
        check("next ven soir", Optional.of(dt("2024-01-08T09:00")), h.nextOpening(dt("2024-01-05T18:00")));

        // espaces
        OpeningHours sp = OpeningHours.parse("  Mo , We - Fr  09:00 - 12:00 , 14:00-18:00 ;  2024-01-03 off ");
        check("espaces mer off", false, sp.isOpen(dt("2024-01-03T10:00")));
        check("espaces jeu", true, sp.isOpen(dt("2024-01-04T15:00")));
        check("espaces mar", false, sp.isOpen(dt("2024-01-02T10:00")));

        // dernière règle l'emporte
        OpeningHours last = OpeningHours.parse("Mo-Fr 09:00-18:00; We 10:00-11:00");
        check("mer 9h", false, last.isOpen(dt("2024-01-03T09:30")));
        check("mer 10h", true, last.isOpen(dt("2024-01-03T10:30")));

        // intervalle via dimanche
        OpeningHours wrap = OpeningHours.parse("Sa-Mo 10:00-11:00");
        check("sam", true, wrap.isOpen(dt("2024-01-06T10:00")));
        check("dim", true, wrap.isOpen(dt("2024-01-07T10:00")));
        check("lun", true, wrap.isOpen(dt("2024-01-08T10:00")));
        check("mar", false, wrap.isOpen(dt("2024-01-09T10:00")));

        // nuit
        OpeningHours night = OpeningHours.parse("Fr 22:00-02:00; 2024-01-06 12:00-13:00; 2024-01-12 off");
        check("ven 23h", true, night.isOpen(dt("2024-01-05T23:00")));
        check("sam 1h (débord sur date rule)", true, night.isOpen(dt("2024-01-06T01:00")));
        check("sam 2h", false, night.isOpen(dt("2024-01-06T02:00")));
        check("ven 12 off", false, night.isOpen(dt("2024-01-12T23:00")));
        check("sam 13 1h (départ off)", false, night.isOpen(dt("2024-01-13T01:00")));
        check("next", Optional.of(dt("2024-01-06T12:00")), night.nextOpening(dt("2024-01-06T02:00")));
        check("next saut off", Optional.of(dt("2024-01-19T22:00")), night.nextOpening(dt("2024-01-12T00:00")));

        OpeningHours full = OpeningHours.parse("Mo 00:00-00:00");
        check("24h", true, full.isOpen(dt("2024-01-01T23:59")));
        check("24h fin", false, full.isOpen(dt("2024-01-02T00:00")));
        OpeningHours e24 = OpeningHours.parse("Mo 20:00-24:00; Tu 00:00-01:00");
        check("24:00", true, e24.isOpen(dt("2024-01-01T23:59")));
        check("mardi 0h", true, e24.isOpen(dt("2024-01-02T00:00")));

        // limite 366 jours
        OpeningHours once = OpeningHours.parse("2025-01-01 10:00-11:00");
        check("dans 366j", Optional.of(dt("2025-01-01T10:00")), once.nextOpening(dt("2024-01-01T10:00")));
        check("au-delà", Optional.empty(), once.nextOpening(dt("2023-01-01T10:00")));

        invalid(""); invalid("   "); invalid("Xx 09:00-10:00"); invalid("Mo 25:00-26:00");
        invalid("Mo 10:60-11:00"); invalid("Mo 09:00-10:00;"); invalid("Mo 09:00-10:00;;Tu 09:00-10:00");
        invalid("Mo"); invalid("Mo 24:00-10:00"); invalid("2024-02-30 off"); invalid("Mo 9-10");
        invalid("Mo 09:00-24:01"); invalid("Mo, 09:00-10:00"); invalid("Mo 09:00-10:00,");

        System.out.println(ok + " OK, " + fail + " échec(s)");
        if (fail > 0) System.exit(1);
    }
}
