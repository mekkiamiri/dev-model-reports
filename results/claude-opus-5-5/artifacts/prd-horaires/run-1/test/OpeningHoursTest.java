import com.example.hours.OpeningHours;
import java.time.LocalDateTime;
import java.util.Optional;

public class OpeningHoursTest {
    static int fails = 0;

    static void check(boolean c, String msg) {
        if (!c) { fails++; System.out.println("FAIL: " + msg); }
    }

    static LocalDateTime dt(String s) { return LocalDateTime.parse(s); }

    static void invalid(String spec) {
        try { OpeningHours.parse(spec); check(false, "should reject: " + spec); }
        catch (IllegalArgumentException e) { }
    }

    public static void main(String[] a) {
        // 2024-01-01 is a Monday
        OpeningHours h = OpeningHours.parse("Mo-Fr 09:00-12:00,14:00-18:00");
        check(h.isOpen(dt("2024-01-01T09:00")), "open at 9");
        check(!h.isOpen(dt("2024-01-01T08:59:59")), "closed before 9");
        check(!h.isOpen(dt("2024-01-01T18:00")), "closed at 18");
        check(h.isOpen(dt("2024-01-01T17:59:59.999")), "open just before 18");
        check(!h.isOpen(dt("2024-01-06T10:00")), "closed Saturday");
        check(h.nextOpening(dt("2024-01-01T12:30")).equals(Optional.of(dt("2024-01-01T14:00"))), "next 14:00");
        check(h.nextOpening(dt("2024-01-05T18:00")).equals(Optional.of(dt("2024-01-08T09:00"))), "next Monday");
        check(h.nextOpening(dt("2024-01-01T10:00")).equals(Optional.of(dt("2024-01-01T10:00"))), "next = t");

        // wrap
        h = OpeningHours.parse("Sa-Mo 10:00-11:00");
        check(h.isOpen(dt("2024-01-06T10:00")) && h.isOpen(dt("2024-01-07T10:00")) && h.isOpen(dt("2024-01-08T10:00")), "Sa-Mo");
        check(!h.isOpen(dt("2024-01-09T10:00")), "not Tu");

        // last wins
        h = OpeningHours.parse("Mo-Fr 09:00-18:00; We 10:00-12:00");
        check(!h.isOpen(dt("2024-01-03T09:30")), "We replaced");
        check(h.isOpen(dt("2024-01-03T11:00")), "We new range");
        check(h.isOpen(dt("2024-01-04T09:30")), "Th untouched");

        // overnight
        h = OpeningHours.parse("Fr 22:00-02:00; 2024-01-06 off; 2024-01-12 off");
        check(h.isOpen(dt("2024-01-05T23:00")), "Fr night");
        check(h.isOpen(dt("2024-01-06T01:59")), "spill onto off date");
        check(!h.isOpen(dt("2024-01-06T02:00")), "spill end");
        check(!h.isOpen(dt("2024-01-12T23:00")), "Fr off");
        check(!h.isOpen(dt("2024-01-13T01:00")), "spill gone when start day off");
        check(h.nextOpening(dt("2024-01-12T00:00")).equals(Optional.of(dt("2024-01-19T22:00"))), "next after off");

        h = OpeningHours.parse("Mo 00:00-24:00; 2024-01-08 10:00-11:00");
        check(h.isOpen(dt("2024-01-01T23:59")), "24:00");
        check(!h.isOpen(dt("2024-01-02T00:00")), "24:00 excl");
        check(!h.isOpen(dt("2024-01-08T09:00")) && h.isOpen(dt("2024-01-08T10:30")), "date rule replaces");

        h = OpeningHours.parse(" Mo , We  09:00 - 10:00 ; 2024-12-25  off ");
        check(h.isOpen(dt("2024-01-03T09:30")), "spaces");

        h = OpeningHours.parse("2024-01-01 off");
        check(h.nextOpening(dt("2024-01-01T00:00")).isEmpty(), "never open");
        h = OpeningHours.parse("2025-01-02 10:00-11:00");
        check(h.nextOpening(dt("2024-01-01T00:00")).isEmpty(), "beyond 366 days");
        check(h.nextOpening(dt("2024-01-03T00:00")).isPresent(), "within 366 days");

        invalid(""); invalid("   "); invalid("Xx 09:00-10:00"); invalid("Mo 25:00-26:00");
        invalid("Mo 10:60-11:00"); invalid("Mo 09:00-10:00;;Tu 09:00-10:00"); invalid("Mo");
        invalid("Mo 24:00-10:00"); invalid("2023-02-29 off"); invalid("Mo 9-10"); invalid("Mo 09:00-24:01");

        System.out.println(fails == 0 ? "ALL OK" : fails + " failure(s)");
    }
}
