package dev.ledgerlab.sim.bank;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Independent copy of the simulator contract calendar, with no application dependency. */
public final class BankCalendar {
    private final Set<LocalDate> holidays;

    public BankCalendar() {
        try (var reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(BankCalendar.class.getResourceAsStream("/calendar/business-holidays.csv")),
                StandardCharsets.UTF_8))) {
            holidays = reader.lines()
                    .filter(line -> line.matches("\\d{4}-.*"))
                    .map(line -> LocalDate.parse(line.substring(0, 10)))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read bank calendar", error);
        }
    }

    public LocalDate addBusinessDays(LocalDate start, long days) {
        check(start);
        if (days < 0 || days > 730) throw new IllegalArgumentException("Invalid business day count");
        LocalDate date = start;
        while (days > 0) {
            date = date.plusDays(1);
            check(date);
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != DayOfWeek.SUNDAY
                    && !holidays.contains(date)) days--;
        }
        return date;
    }

    public static void check(LocalDate date) {
        if (date.getYear() < 2026 || date.getYear() > 2027)
            throw new IllegalArgumentException("Bank calendar covers 2026-2027");
    }
}
