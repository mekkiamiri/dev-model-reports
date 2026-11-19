package com.example.hours;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Horaires d'ouverture d'une boutique, analysés depuis une spécification textuelle.
 * Voir PRD.md pour la syntaxe.
 */
public final class OpeningHours {

    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final long NANOS_PER_MINUTE = 60L * 1_000_000_000L;
    private static final int SEARCH_DAYS = 366;

    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final Pattern SEPARATOR_SPACES = Pattern.compile("\\s*([,:\\-])\\s*");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Map<String, DayOfWeek> DAYS = Map.of(
            "Mo", DayOfWeek.MONDAY,
            "Tu", DayOfWeek.TUESDAY,
            "We", DayOfWeek.WEDNESDAY,
            "Th", DayOfWeek.THURSDAY,
            "Fr", DayOfWeek.FRIDAY,
            "Sa", DayOfWeek.SATURDAY,
            "Su", DayOfWeek.SUNDAY);

    /** Plage en minutes depuis minuit ; si {@code end <= start}, la plage déborde sur le lendemain. */
    private static final class Range {
        final int start;
        final int end;

        Range(int start, int end) {
            this.start = start;
            this.end = end;
        }

        boolean overnight() {
            return end <= start;
        }
    }

    private final Map<DayOfWeek, List<Range>> weekRules;
    private final Map<LocalDate, List<Range>> dateRules;

    private OpeningHours(Map<DayOfWeek, List<Range>> weekRules, Map<LocalDate, List<Range>> dateRules) {
        this.weekRules = weekRules;
        this.dateRules = dateRules;
    }

    // ------------------------------------------------------------------ parsing

    public static OpeningHours parse(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            throw new IllegalArgumentException("Spécification vide");
        }
        Map<DayOfWeek, List<Range>> week = new EnumMap<>(DayOfWeek.class);
        Map<LocalDate, List<Range>> dates = new HashMap<>();

        String[] rules = spec.split(";", -1);
        for (String raw : rules) {
            String rule = SEPARATOR_SPACES.matcher(raw.trim()).replaceAll("$1");
            if (rule.isEmpty()) {
                throw new IllegalArgumentException("Règle vide dans : " + spec);
            }
            String[] parts = WHITESPACE.split(rule);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Règle invalide : " + raw.trim());
            }
            String head = parts[0];
            String tail = parts[1];

            Matcher dm = DATE.matcher(head);
            if (dm.matches()) {
                LocalDate date;
                try {
                    date = LocalDate.of(Integer.parseInt(dm.group(1)),
                            Integer.parseInt(dm.group(2)),
                            Integer.parseInt(dm.group(3)));
                } catch (DateTimeException e) {
                    throw new IllegalArgumentException("Date invalide : " + head, e);
                }
                dates.put(date, parseRangesOrOff(tail));
            } else {
                List<DayOfWeek> days = parseDays(head);
                List<Range> ranges = parseRangesOrOff(tail);
                for (DayOfWeek d : days) {
                    week.put(d, ranges); // la dernière règle l'emporte
                }
            }
        }
        return new OpeningHours(week, dates);
    }

    private static List<DayOfWeek> parseDays(String s) {
        List<DayOfWeek> result = new ArrayList<>();
        for (String item : s.split(",", -1)) {
            if (item.isEmpty()) {
                throw new IllegalArgumentException("Jour vide dans : " + s);
            }
            String[] bounds = item.split("-", -1);
            if (bounds.length == 1) {
                result.add(day(bounds[0]));
            } else if (bounds.length == 2) {
                DayOfWeek from = day(bounds[0]);
                DayOfWeek to = day(bounds[1]);
                DayOfWeek d = from;
                result.add(d);
                while (d != to) {
                    d = d.plus(1);
                    result.add(d);
                }
            } else {
                throw new IllegalArgumentException("Intervalle de jours invalide : " + item);
            }
        }
        return result;
    }

    private static DayOfWeek day(String s) {
        DayOfWeek d = DAYS.get(s);
        if (d == null) {
            throw new IllegalArgumentException("Jour inconnu : " + s);
        }
        return d;
    }

    private static List<Range> parseRangesOrOff(String s) {
        if (s.equals("off")) {
            return Collections.emptyList();
        }
        List<Range> ranges = new ArrayList<>();
        for (String item : s.split(",", -1)) {
            String[] bounds = item.split("-", -1);
            if (bounds.length != 2) {
                throw new IllegalArgumentException("Plage invalide : " + item);
            }
            int start = parseTime(bounds[0], false);
            int end = parseTime(bounds[1], true);
            ranges.add(new Range(start, end));
        }
        return Collections.unmodifiableList(ranges);
    }

    private static int parseTime(String s, boolean allow2400) {
        Matcher m = TIME.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("Heure invalide : " + s);
        }
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (min > 59) {
            throw new IllegalArgumentException("Heure invalide : " + s);
        }
        if (h == 24 && min == 0 && allow2400) {
            return MINUTES_PER_DAY;
        }
        if (h > 23) {
            throw new IllegalArgumentException("Heure invalide : " + s);
        }
        return h * 60 + min;
    }

    // ------------------------------------------------------------------ queries

    /** Plages effectives d'un jour (règle de date prioritaire sur la règle de semaine). */
    private List<Range> rangesFor(LocalDate date) {
        List<Range> r = dateRules.get(date);
        if (r != null) {
            return r;
        }
        r = weekRules.get(date.getDayOfWeek());
        return r != null ? r : Collections.emptyList();
    }

    public boolean isOpen(LocalDateTime t) {
        if (t == null) {
            throw new NullPointerException("t");
        }
        long nanos = t.toLocalTime().toNanoOfDay();
        LocalDate date = t.toLocalDate();

        for (Range r : rangesFor(date)) {
            long start = r.start * NANOS_PER_MINUTE;
            if (r.overnight()) {
                if (nanos >= start) {
                    return true;
                }
            } else if (nanos >= start && nanos < r.end * NANOS_PER_MINUTE) {
                return true;
            }
        }
        if (date.equals(LocalDate.MIN)) {
            return false;
        }
        for (Range r : rangesFor(date.minusDays(1))) {
            if (r.overnight() && nanos < r.end * NANOS_PER_MINUTE) {
                return true;
            }
        }
        return false;
    }

    public Optional<LocalDateTime> nextOpening(LocalDateTime t) {
        if (isOpen(t)) {
            return Optional.of(t);
        }
        // Boutique fermée à t : la prochaine ouverture est forcément le début d'une plage
        // (un débordement de nuit est la continuation d'une plage déjà commencée).
        LocalDateTime limit;
        try {
            limit = t.plusDays(SEARCH_DAYS);
        } catch (DateTimeException e) {
            limit = LocalDateTime.MAX;
        }
        LocalDate date = t.toLocalDate();
        for (int i = 0; i <= SEARCH_DAYS; i++) {
            LocalDateTime best = null;
            for (Range r : rangesFor(date)) {
                LocalDateTime candidate = LocalDateTime.of(date, LocalTime.ofSecondOfDay(r.start * 60L));
                if (!candidate.isBefore(t) && (best == null || candidate.isBefore(best))) {
                    best = candidate;
                }
            }
            if (best != null) {
                return best.isAfter(limit) ? Optional.empty() : Optional.of(best);
            }
            if (date.equals(LocalDate.MAX)) {
                break;
            }
            date = date.plusDays(1);
        }
        return Optional.empty();
    }
}
