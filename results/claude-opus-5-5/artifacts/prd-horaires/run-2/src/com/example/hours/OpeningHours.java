package com.example.hours;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * Horaires d'ouverture saisis sous forme de texte (voir PRD.md).
 * Instances immuables.
 */
public final class OpeningHours {

    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final int SEARCH_DAYS = 366;

    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern TIME = Pattern.compile("(\\d{2}):(\\d{2})");
    private static final String[] DAY_NAMES = {"Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"};

    /** Plage en minutes depuis minuit du jour de départ ; end peut dépasser 1440 (plage de nuit). */
    private static final class Range {
        final int start;
        final int end;

        Range(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private final Map<DayOfWeek, List<Range>> weekly;
    private final Map<LocalDate, List<Range>> dated;

    private OpeningHours(Map<DayOfWeek, List<Range>> weekly, Map<LocalDate, List<Range>> dated) {
        this.weekly = weekly;
        this.dated = dated;
    }

    // ------------------------------------------------------------------ parsing

    public static OpeningHours parse(String spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec is null");
        }
        if (spec.isBlank()) {
            throw new IllegalArgumentException("empty spec");
        }
        Map<DayOfWeek, List<Range>> weekly = new EnumMap<>(DayOfWeek.class);
        Map<LocalDate, List<Range>> dated = new HashMap<>();

        for (String rawRule : spec.split(";", -1)) {
            // Espaces ignorés autour des séparateurs ',' '-' ':'
            String rule = rawRule.strip().replaceAll("\\s*([,\\-:])\\s*", "$1");
            if (rule.isEmpty()) {
                throw new IllegalArgumentException("empty rule in: " + spec);
            }
            String[] parts = rule.split("\\s+");
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid rule: " + rawRule.strip());
            }
            String selector = parts[0];
            List<Range> ranges = parseRanges(parts[1]);

            Matcher dm = DATE.matcher(selector);
            if (dm.matches()) {
                LocalDate date;
                try {
                    date = LocalDate.of(Integer.parseInt(dm.group(1)),
                            Integer.parseInt(dm.group(2)), Integer.parseInt(dm.group(3)));
                } catch (DateTimeException e) {
                    throw new IllegalArgumentException("invalid date: " + selector, e);
                }
                dated.put(date, ranges); // la dernière règle pour une date l'emporte
            } else {
                for (DayOfWeek d : parseDays(selector)) {
                    weekly.put(d, ranges); // la dernière règle l'emporte (remplacement)
                }
            }
        }
        return new OpeningHours(weekly, dated);
    }

    private static List<DayOfWeek> parseDays(String s) {
        List<DayOfWeek> result = new ArrayList<>();
        for (String item : s.split(",", -1)) {
            if (item.isEmpty()) {
                throw new IllegalArgumentException("empty day in: " + s);
            }
            String[] bounds = item.split("-", -1);
            if (bounds.length == 1) {
                result.add(parseDay(bounds[0]));
            } else if (bounds.length == 2) {
                int from = parseDay(bounds[0]).ordinal();
                int to = parseDay(bounds[1]).ordinal();
                int i = from;
                while (true) {
                    result.add(DayOfWeek.values()[i]);
                    if (i == to) {
                        break;
                    }
                    i = (i + 1) % 7; // passe par dimanche si nécessaire
                }
            } else {
                throw new IllegalArgumentException("invalid day range: " + item);
            }
        }
        return result;
    }

    private static DayOfWeek parseDay(String s) {
        for (int i = 0; i < DAY_NAMES.length; i++) {
            if (DAY_NAMES[i].equals(s)) {
                return DayOfWeek.values()[i];
            }
        }
        throw new IllegalArgumentException("unknown day: " + s);
    }

    private static List<Range> parseRanges(String s) {
        if (s.equals("off")) {
            return Collections.emptyList();
        }
        List<Range> result = new ArrayList<>();
        for (String item : s.split(",", -1)) {
            String[] bounds = item.split("-", -1);
            if (bounds.length != 2) {
                throw new IllegalArgumentException("invalid time range: " + item);
            }
            int start = parseTime(bounds[0], false);
            int end = parseTime(bounds[1], true);
            if (end <= start) {
                end += MINUTES_PER_DAY; // plage de nuit
            }
            result.add(new Range(start, end));
        }
        return Collections.unmodifiableList(result);
    }

    private static int parseTime(String s, boolean isEnd) {
        Matcher m = TIME.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("invalid time: " + s);
        }
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (min > 59) {
            throw new IllegalArgumentException("invalid time: " + s);
        }
        if (h == 24 && min == 0 && isEnd) {
            return MINUTES_PER_DAY;
        }
        if (h > 23) {
            throw new IllegalArgumentException("invalid time: " + s);
        }
        return h * 60 + min;
    }

    // ------------------------------------------------------------------ queries

    /** Plages effectives d'un jour : la règle de date remplace la règle de semaine. */
    private List<Range> rangesFor(LocalDate d) {
        List<Range> r = dated.get(d);
        if (r != null) {
            return r;
        }
        return weekly.getOrDefault(d.getDayOfWeek(), Collections.emptyList());
    }

    private static boolean covers(LocalDate day, Range r, LocalDateTime t) {
        LocalDateTime midnight = day.atStartOfDay();
        LocalDateTime s = midnight.plusMinutes(r.start);
        LocalDateTime e = midnight.plusMinutes(r.end);
        return !t.isBefore(s) && t.isBefore(e);
    }

    public boolean isOpen(LocalDateTime t) {
        if (t == null) {
            throw new NullPointerException("t");
        }
        LocalDate d = t.toLocalDate();
        for (Range r : rangesFor(d)) {
            if (covers(d, r, t)) {
                return true;
            }
        }
        // Débordement des plages de nuit commencées la veille (selon les règles de la veille)
        LocalDate prev = d.minusDays(1);
        for (Range r : rangesFor(prev)) {
            if (r.end > MINUTES_PER_DAY && covers(prev, r, t)) {
                return true;
            }
        }
        return false;
    }

    public Optional<LocalDateTime> nextOpening(LocalDateTime t) {
        if (isOpen(t)) {
            return Optional.of(t);
        }
        // Fermé à t : la prochaine ouverture est forcément le début d'une plage, postérieur à t.
        LocalDate d = t.toLocalDate();
        for (int i = 0; i <= SEARCH_DAYS; i++) {
            LocalDate day = d.plusDays(i);
            LocalDateTime best = null;
            for (Range r : rangesFor(day)) {
                LocalDateTime s = day.atStartOfDay().plusMinutes(r.start);
                if (!s.isBefore(t) && (best == null || s.isBefore(best))) {
                    best = s;
                }
            }
            if (best != null) {
                return Optional.of(best);
            }
        }
        return Optional.empty();
    }
}
