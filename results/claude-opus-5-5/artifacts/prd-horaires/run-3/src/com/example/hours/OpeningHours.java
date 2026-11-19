package com.example.hours;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
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
 * Horaires d'ouverture d'une boutique, en heure locale.
 */
public final class OpeningHours {

    private static final int DAY_MINUTES = 24 * 60;
    private static final int SEARCH_DAYS = 366;

    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern TIME = Pattern.compile("(\\d{2}):(\\d{2})");
    private static final String[] DAY_NAMES = {"Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"};

    /** Plage en minutes depuis minuit du jour de départ ; end > start, end ≤ 2 × 1440. */
    private record Range(int start, int end) { }

    private final Map<DayOfWeek, List<Range>> weekRules;
    private final Map<LocalDate, List<Range>> dateRules;

    private OpeningHours(Map<DayOfWeek, List<Range>> weekRules, Map<LocalDate, List<Range>> dateRules) {
        this.weekRules = weekRules;
        this.dateRules = dateRules;
    }

    // ------------------------------------------------------------------ parsing

    public static OpeningHours parse(String spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spécification nulle");
        }
        if (spec.isBlank()) {
            throw new IllegalArgumentException("spécification vide");
        }
        Map<DayOfWeek, List<Range>> week = new EnumMap<>(DayOfWeek.class);
        Map<LocalDate, List<Range>> dates = new HashMap<>();

        for (String rawRule : spec.split(";", -1)) {
            // Espaces ignorés autour des séparateurs ',' et '-'.
            String rule = rawRule.trim().replaceAll("\\s*([,\\-])\\s*", "$1");
            if (rule.isEmpty()) {
                throw new IllegalArgumentException("règle vide");
            }
            String[] parts = rule.split("\\s+");
            if (parts.length != 2) {
                throw new IllegalArgumentException("règle invalide : '" + rawRule.trim() + "'");
            }
            String selector = parts[0];
            String body = parts[1];
            List<Range> ranges = parseRanges(body);

            Matcher dm = DATE.matcher(selector);
            if (dm.matches()) {
                dates.put(parseDate(selector), ranges);
            } else {
                for (DayOfWeek d : parseDays(selector)) {
                    week.put(d, ranges);
                }
            }
        }
        return new OpeningHours(week, dates);
    }

    private static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date invalide : '" + s + "'", e);
        }
    }

    private static List<DayOfWeek> parseDays(String s) {
        List<DayOfWeek> result = new ArrayList<>();
        for (String item : s.split(",", -1)) {
            if (item.isEmpty()) {
                throw new IllegalArgumentException("jour manquant dans '" + s + "'");
            }
            String[] bounds = item.split("-", -1);
            if (bounds.length == 1) {
                result.add(parseDay(bounds[0]));
            } else if (bounds.length == 2) {
                DayOfWeek from = parseDay(bounds[0]);
                DayOfWeek to = parseDay(bounds[1]);
                DayOfWeek d = from;
                while (true) {
                    result.add(d);
                    if (d == to) {
                        break;
                    }
                    d = d.plus(1);
                }
            } else {
                throw new IllegalArgumentException("intervalle de jours invalide : '" + item + "'");
            }
        }
        return result;
    }

    private static DayOfWeek parseDay(String s) {
        for (int i = 0; i < DAY_NAMES.length; i++) {
            if (DAY_NAMES[i].equals(s)) {
                return DayOfWeek.of(i + 1);
            }
        }
        throw new IllegalArgumentException("jour inconnu : '" + s + "'");
    }

    private static List<Range> parseRanges(String s) {
        if (s.equals("off")) {
            return Collections.emptyList();
        }
        List<Range> ranges = new ArrayList<>();
        for (String item : s.split(",", -1)) {
            String[] bounds = item.split("-", -1);
            if (bounds.length != 2) {
                throw new IllegalArgumentException("plage invalide : '" + item + "'");
            }
            int start = parseTime(bounds[0], false);
            int end = parseTime(bounds[1], true);
            if (end <= start) {
                end += DAY_MINUTES; // plage de nuit
            }
            ranges.add(new Range(start, end));
        }
        return Collections.unmodifiableList(ranges);
    }

    private static int parseTime(String s, boolean allow24) {
        Matcher m = TIME.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("heure invalide : '" + s + "'");
        }
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (allow24 && h == 24 && min == 0) {
            return DAY_MINUTES;
        }
        if (h > 23 || min > 59) {
            throw new IllegalArgumentException("heure impossible : '" + s + "'");
        }
        return h * 60 + min;
    }

    // ------------------------------------------------------------------ requêtes

    private List<Range> rangesFor(LocalDate day) {
        List<Range> r = dateRules.get(day);
        if (r != null) {
            return r;
        }
        return weekRules.getOrDefault(day.getDayOfWeek(), Collections.emptyList());
    }

    public boolean isOpen(LocalDateTime t) {
        LocalDate today = t.toLocalDate();
        for (LocalDate day : new LocalDate[] {today.minusDays(1), today}) {
            LocalDateTime midnight = day.atStartOfDay();
            for (Range r : rangesFor(day)) {
                LocalDateTime start = midnight.plusMinutes(r.start());
                LocalDateTime end = midnight.plusMinutes(r.end());
                if (!t.isBefore(start) && t.isBefore(end)) {
                    return true;
                }
            }
        }
        return false;
    }

    public Optional<LocalDateTime> nextOpening(LocalDateTime t) {
        if (isOpen(t)) {
            return Optional.of(t);
        }
        LocalDateTime limit = t.plusDays(SEARCH_DAYS);
        LocalDate day = t.toLocalDate();
        for (int i = 0; i <= SEARCH_DAYS; i++, day = day.plusDays(1)) {
            LocalDateTime midnight = day.atStartOfDay();
            LocalDateTime best = null;
            for (Range r : rangesFor(day)) {
                LocalDateTime start = midnight.plusMinutes(r.start());
                if (start.isAfter(t) && (best == null || start.isBefore(best))) {
                    best = start;
                }
            }
            if (best != null) {
                // Les débuts de plage d'un jour sont tous dans ce jour : le premier jour trouvé est le bon.
                return best.isAfter(limit) ? Optional.empty() : Optional.of(best);
            }
        }
        return Optional.empty();
    }
}
