package dev.ledgerlab.shared;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Immutable simulated contract calendar; all dates use Toronto and the maintained 2026–2027 range. */
@Component
public final class BusinessCalendar {
    private static final ZoneId ZONE = ZoneId.of("America/Toronto");
    private static final int FIRST_YEAR = 2026;
    private static final int LAST_YEAR = 2027;
    private static final String RESOURCE = "calendar/business-holidays.csv";

    private final Set<LocalDate> holidays;

    public BusinessCalendar() {
        holidays = loadHolidays();
    }

    /** Returns the local calendar day, including weekends and holidays. */
    public LocalDate businessDateOf(Instant instant) {
        LocalDate date = instant.atZone(ZONE).toLocalDate();
        requireCovered(date);
        return date;
    }

    public boolean isBusinessDay(LocalDate date) {
        requireCovered(date);
        return date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY
                && !holidays.contains(date);
    }

    /** Counts strictly after the start date, then returns midnight after the nth business day. */
    public Instant dueAt(Instant start, int businessDays) {
        if (businessDays <= 0) {
            throw new IllegalArgumentException("Due date requires a positive number of business days");
        }
        return startOfDay(addBusinessDays(businessDateOf(start), businessDays).plusDays(1));
    }

    /** Counts strictly after date; zero returns date unchanged, even if it is not a business day. */
    public LocalDate addBusinessDays(LocalDate date, int businessDays) {
        requireCovered(date);
        if (businessDays < 0) {
            throw new IllegalArgumentException("Business days must not be negative");
        }
        LocalDate result = date;
        int remaining = businessDays;
        while (remaining > 0) {
            result = result.plusDays(1);
            if (isBusinessDay(result)) {
                remaining--;
            }
        }
        return result;
    }

    public Instant startOfDay(LocalDate date) {
        requireCovered(date);
        return date.atStartOfDay(ZONE).toInstant();
    }

    private static void requireCovered(LocalDate date) {
        if (date.getYear() < FIRST_YEAR || date.getYear() > LAST_YEAR) {
            throw new IllegalArgumentException("Business calendar covers only 2026–2027: " + date);
        }
    }

    private static Set<LocalDate> loadHolidays() {
        try (var reader = new BufferedReader(
                new InputStreamReader(new ClassPathResource(RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
            var lines = reader.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
            if (lines.isEmpty() || !lines.getFirst().equals("date,name")) {
                throw new IllegalStateException("Invalid calendar CSV header: " + RESOURCE);
            }
            Set<LocalDate> dates = new HashSet<>();
            for (String line : lines.subList(1, lines.size())) {
                String[] fields = line.split(",", -1);
                if (fields.length != 2 || fields[1].isBlank()) {
                    throw new IllegalStateException("Invalid calendar CSV row: " + line);
                }
                LocalDate date = LocalDate.parse(fields[0]);
                requireCovered(date);
                if (!dates.add(date)) {
                    throw new IllegalStateException("Duplicate calendar holiday: " + date);
                }
            }
            return Set.copyOf(dates);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load business calendar: " + RESOURCE, e);
        }
    }
}
