import com.example.hours.OpeningHours;

import java.time.LocalDateTime;
import java.util.Optional;

public class OpeningHoursTest {
    static int failures = 0;

    static void check(boolean cond, String msg) {
        if (!cond) {
            failures++;
            System.out.println("FAIL: " + msg);
        }
    }

    static LocalDateTime dt(String s) {
        return LocalDateTime.parse(s);
    }

    static void invalid(String spec) {
        try {
            OpeningHours.parse(spec);
            check(false, "should be invalid: [" + spec + "]");
        } catch (IllegalArgumentException e) {
            // ok
        }
    }

    public static void main(String[] args) {
        // 2024-01-01 est un lundi
        OpeningHours h = OpeningHours.parse("Mo-Fr 09:00-12:00,14:00-18:00");
        check(h.isOpen(dt("2024-01-01T09:00")), "open at 9:00");
        check(!h.isOpen(dt("2024-01-01T08:59:59")), "closed before 9");
        check(!h.isOpen(dt("2024-01-01T12:00")), "closed at 12:00");
        check(h.isOpen(dt("2024-01-01T17:59:59.999")), "open just before 18");
        check(!h.isOpen(dt("2024-01-01T18:00")), "closed at 18:00");
        check(!h.isOpen(dt("2024-01-06T10:00")), "closed saturday");
        check(h.nextOpening(dt("2024-01-01T12:30")).equals(Optional.of(dt("2024-01-01T14:00"))), "next 14:00");
        check(h.nextOpening(dt("2024-01-01T10:00")).equals(Optional.of(dt("2024-01-01T10:00"))), "next = t");
        check(h.nextOpening(dt("2024-01-05T18:00")).equals(Optional.of(dt("2024-01-08T09:00"))), "next monday");

        // dernière règle l'emporte
        OpeningHours o = OpeningHours.parse("Mo-Fr 09:00-18:00; We 10:00-12:00");
        check(!o.isOpen(dt("2024-01-03T09:30")), "we replaced");
        check(o.isOpen(dt("2024-01-03T11:00")), "we open 11");
        check(!o.isOpen(dt("2024-01-03T15:00")), "we not additive");

        // intervalle par dimanche
        OpeningHours w = OpeningHours.parse(" Sa-Mo 10:00-11:00 ");
        check(w.isOpen(dt("2024-01-06T10:30")), "sa");
        check(w.isOpen(dt("2024-01-07T10:30")), "su");
        check(w.isOpen(dt("2024-01-08T10:30")), "mo");
        check(!w.isOpen(dt("2024-01-09T10:30")), "tu");

        // nuit
        OpeningHours n = OpeningHours.parse("Fr 22:00-02:00; 2024-01-06 off");
        check(n.isOpen(dt("2024-01-05T23:00")), "fri night");
        check(n.isOpen(dt("2024-01-06T01:59")), "overflow to sat even if date off");
        check(!n.isOpen(dt("2024-01-06T02:00")), "closed at 2");
        OpeningHours n2 = OpeningHours.parse("Fr 22:00-02:00; 2024-01-05 off");
        check(!n2.isOpen(dt("2024-01-06T01:00")), "overflow vanishes if start day off");
        check(n2.nextOpening(dt("2024-01-05T00:00")).equals(Optional.of(dt("2024-01-12T22:00"))), "next fri");
        OpeningHours n3 = OpeningHours.parse("Mo 10:00-10:00");
        check(n3.isOpen(dt("2024-01-02T09:59")), "24h overnight");
        check(!n3.isOpen(dt("2024-01-02T10:00")), "24h end");
        OpeningHours n4 = OpeningHours.parse("Mo 00:00-24:00");
        check(n4.isOpen(dt("2024-01-01T23:59:59")), "24:00");
        check(!n4.isOpen(dt("2024-01-02T00:00")), "24:00 exclusive");

        // date rule replaces
        OpeningHours d = OpeningHours.parse("Mo-Fr 09:00-18:00; 2024-12-25 off; 2024-12-24 09:00-12:00");
        check(!d.isOpen(dt("2024-12-25T10:00")), "xmas off");
        check(!d.isOpen(dt("2024-12-24T13:00")), "xmas eve short");
        check(d.nextOpening(dt("2024-12-24T13:00")).equals(Optional.of(dt("2024-12-26T09:00"))), "after xmas");

        // vide sur 366 jours
        OpeningHours only = OpeningHours.parse("2024-01-01 10:00-11:00");
        check(only.nextOpening(dt("2024-01-02T00:00")).isEmpty(), "empty");
        check(only.nextOpening(dt("2023-06-01T00:00")).equals(Optional.of(dt("2024-01-01T10:00"))), "within year");

        // espaces
        OpeningHours sp = OpeningHours.parse("  Mo , We  09:00 - 12:00 , 14:00-15:00 ;Fr 10:00-11:00");
        check(sp.isOpen(dt("2024-01-03T14:30")), "spaces");

        invalid("");
        invalid("   ");
        invalid("Xx 09:00-10:00");
        invalid("Mo 25:00-26:00");
        invalid("Mo 10:60-11:00");
        invalid("Mo 24:00-10:00");
        invalid("Mo 09:00-12:00;");
        invalid("Mo 09:00-12:00;;Tu 09:00-10:00");
        invalid("Mo");
        invalid("09:00-10:00");
        invalid("2024-02-30 off");
        invalid("Mo 9-10");
        invalid("Mo-Tu-We 09:00-10:00");

        System.out.println(failures == 0 ? "ALL OK" : failures + " failure(s)");
        if (failures > 0) System.exit(1);
    }
}
