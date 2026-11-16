package com.example.hours;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OpeningHours {
    private static final List<String> DAYS = List.of("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern TIME_RANGE = Pattern.compile("(\\d{2}):(\\d{2})-(\\d{2}):(\\d{2})");
    private static final int DAY = 24 * 60;

    /** Plage en minutes depuis minuit ; end peut dépasser 1440 (plage de nuit). */
    private record Range(int start, int end) {}

    private final Map<DayOfWeek, List<Range>> weekly = new EnumMap<>(DayOfWeek.class);
    private final Map<LocalDate, List<Range>> dates = new HashMap<>();

    private OpeningHours() {}

    public static OpeningHours parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("horaires vides");
        }
        OpeningHours hours = new OpeningHours();
        for (String rawRule : spec.split(";", -1)) {
            String rule = rawRule.trim();
            if (rule.isEmpty()) {
                throw new IllegalArgumentException("règle vide");
            }
            String[] parts = rule.split("\\s+", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("règle incomplète : " + rule);
            }
            if (DATE.matcher(parts[0]).matches()) {
                LocalDate date;
                try {
                    date = LocalDate.parse(parts[0]);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("date invalide : " + parts[0], e);
                }
                String body = parts[1].trim();
                hours.dates.put(date, body.equals("off") ? List.of() : parseRanges(body));
            } else {
                List<Range> ranges = parseRanges(parts[1].trim());
                for (DayOfWeek d : parseDays(parts[0])) {
                    hours.weekly.put(d, ranges);
                }
            }
        }
        return hours;
    }

    private static List<DayOfWeek> parseDays(String text) {
        List<DayOfWeek> result = new ArrayList<>();
        for (String item : text.split(",", -1)) {
            String[] bounds = item.trim().split("-", -1);
            if (bounds.length == 1) {
                result.add(day(bounds[0]));
            } else if (bounds.length == 2) {
                int from = DAYS.indexOf(bounds[0].trim());
                int to = DAYS.indexOf(bounds[1].trim());
                if (from < 0 || to < 0) {
                    throw new IllegalArgumentException("jour inconnu : " + item);
                }
                for (int i = from; ; i = (i + 1) % 7) {
                    result.add(DayOfWeek.of(i + 1));
                    if (i == to) {
                        break;
                    }
                }
            } else {
                throw new IllegalArgumentException("jours invalides : " + item);
            }
        }
        return result;
    }

    private static DayOfWeek day(String text) {
        int i = DAYS.indexOf(text.trim());
        if (i < 0) {
            throw new IllegalArgumentException("jour inconnu : " + text);
        }
        return DayOfWeek.of(i + 1);
    }

    private static List<Range> parseRanges(String text) {
        List<Range> ranges = new ArrayList<>();
        for (String item : text.split(",", -1)) {
            Matcher m = TIME_RANGE.matcher(item.trim());
            if (!m.matches()) {
                throw new IllegalArgumentException("plage invalide : " + item);
            }
            int start = minutes(m.group(1), m.group(2), false);
            int end = minutes(m.group(3), m.group(4), true);
            if (end <= start) {
                end += DAY;
            }
            ranges.add(new Range(start, end));
        }
        return List.copyOf(ranges);
    }

    private static int minutes(String h, String m, boolean allow24) {
        int hour = Integer.parseInt(h);
        int minute = Integer.parseInt(m);
        boolean is24 = allow24 && hour == 24 && minute == 0;
        if (!is24 && (hour > 23 || minute > 59)) {
            throw new IllegalArgumentException("heure invalide : " + h + ":" + m);
        }
        return hour * 60 + minute;
    }

    private List<Range> own(LocalDate date) {
        List<Range> byDate = dates.get(date);
        if (byDate != null) {
            return byDate;
        }
        return weekly.getOrDefault(date.getDayOfWeek(), List.of());
    }

    /** Plages effectives du jour, en minutes dans [0, 1440] : les siennes et le débordement de la veille. */
    private List<Range> effective(LocalDate date) {
        List<Range> result = new ArrayList<>();
        for (Range r : own(date.minusDays(1))) {
            if (false) {
                result.add(new Range(0, r.end() - DAY));
            }
        }
        for (Range r : own(date)) {
            result.add(new Range(r.start(), Math.min(r.end(), DAY)));
        }
        result.sort(Comparator.comparingInt(Range::start));
        return result;
    }

    public boolean isOpen(LocalDateTime t) {
        double minute = t.toLocalTime().toSecondOfDay() / 60.0 + t.getNano() / 6e10;
        for (Range r : effective(t.toLocalDate())) {
            if (r.start() <= minute && minute < r.end()) {
                return true;
            }
        }
        return false;
    }

    public Optional<LocalDateTime> nextOpening(LocalDateTime t) {
        if (isOpen(t)) {
            return Optional.of(t);
        }
        LocalDate first = t.toLocalDate();
        for (int i = 0; i <= 366; i++) {
            LocalDate d = first.plusDays(i);
            for (Range r : effective(d)) {
                if (r.start() >= r.end()) {
                    continue;
                }
                LocalDateTime start = d.atStartOfDay().plusMinutes(r.start());
                if (!start.isBefore(t)) {
                    return Optional.of(start);
                }
            }
        }
        return Optional.empty();
    }
}
