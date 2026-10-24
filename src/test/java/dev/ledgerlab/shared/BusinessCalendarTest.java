package dev.ledgerlab.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class BusinessCalendarTest {
    private final BusinessCalendar calendar = new BusinessCalendar();

    @Test
    void ac_5_7_p5IsOverdueAtTheSampleCutoff() {
        Instant due = calendar.dueAt(Instant.parse("2026-11-05T17:00:00Z"), 2);
        assertThat(due).isEqualTo(Instant.parse("2026-11-10T05:00:00Z"));
        assertThat(due).isBefore(Instant.parse("2026-11-11T05:00:00Z"));
    }

    @Test
    void ac_5_7_p4SkipsRemembranceDay() {
        assertThat(calendar.dueAt(Instant.parse("2026-11-11T04:50:00Z"), 2))
                .isEqualTo(Instant.parse("2026-11-14T05:00:00Z"));
    }

    @ParameterizedTest
    @CsvSource({
        "2026-11-06,1,2026-11-09", "2026-11-07,1,2026-11-09",
        "2026-11-08,1,2026-11-09", "2026-11-11,1,2026-11-12",
        "2026-12-24,1,2026-12-29", "2027-12-24,1,2027-12-29",
        "2026-12-31,1,2027-01-04", "2026-11-07,0,2026-11-07"
    })
    void ac_5_11_addBusinessDaysExcludesTheStartingDay(String start, int days, String expected) {
        assertThat(calendar.addBusinessDays(LocalDate.parse(start), days)).isEqualTo(LocalDate.parse(expected));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2026-01-01", "2026-04-03", "2026-05-18", "2026-07-01", "2026-09-07",
                "2026-09-30", "2026-10-12", "2026-11-11", "2026-12-25", "2026-12-28",
                "2027-01-01", "2027-03-26", "2027-05-24", "2027-07-01", "2027-09-06",
                "2027-09-30", "2027-10-11", "2027-11-11", "2027-12-27", "2027-12-28",
                "2026-11-07", "2026-11-08"
            })
    void ac_5_11_weekendsAndObservedFederalHolidaysAreNotBusinessDays(String date) {
        assertThat(calendar.isBusinessDay(LocalDate.parse(date))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-02-16", "2026-04-06", "2026-08-03", "2027-02-15", "2027-03-29", "2027-08-02"})
    void ac_5_11_otherProvincialAndSchoolHolidaysRemainBusinessDays(String date) {
        assertThat(calendar.isBusinessDay(LocalDate.parse(date))).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"2026-03-08,2026-03-08T05:00:00Z,23", "2026-11-01,2026-11-01T04:00:00Z,25"})
    void ac_5_11_midnightAndBusinessDatesFollowDaylightSavingTime(String date, String midnight, long hours) {
        LocalDate day = LocalDate.parse(date);
        Instant start = calendar.startOfDay(day);
        Instant end = calendar.startOfDay(day.plusDays(1));
        assertThat(start).isEqualTo(Instant.parse(midnight));
        assertThat(Duration.between(start, end)).isEqualTo(Duration.ofHours(hours));
        assertThat(calendar.businessDateOf(start.minusNanos(1))).isEqualTo(day.minusDays(1));
        assertThat(calendar.businessDateOf(start)).isEqualTo(day);
        assertThat(calendar.businessDateOf(end.minusNanos(1))).isEqualTo(day);
        assertThat(calendar.businessDateOf(end)).isEqualTo(day.plusDays(1));
    }

    @ParameterizedTest
    @CsvSource({"2026-03-06T17:00:00Z,2026-03-10T04:00:00Z", "2026-10-30T16:00:00Z,2026-11-03T05:00:00Z"})
    void ac_5_11_dueDatesCrossDaylightSavingWeekends(String start, String expected) {
        assertThat(calendar.dueAt(Instant.parse(start), 1)).isEqualTo(Instant.parse(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-12-31", "2028-01-01", "2028-01-02"})
    void ac_5_11_allDateOperationsRejectUncoveredYearsEvenOnWeekends(String date) {
        LocalDate day = LocalDate.parse(date);
        assertThatThrownBy(() -> calendar.isBusinessDay(day)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calendar.startOfDay(day)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calendar.addBusinessDays(day, 0)).isInstanceOf(IllegalArgumentException.class);
        Instant instant = Instant.parse(date + "T17:00:00Z");
        assertThatThrownBy(() -> calendar.businessDateOf(instant)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calendar.dueAt(instant, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ac_5_11_coverageUsesTorontoYearAndRejectsCalculationsLeavingCoverage() {
        assertThat(calendar.businessDateOf(Instant.parse("2028-01-01T04:59:59Z")))
                .isEqualTo(LocalDate.parse("2027-12-31"));
        assertThatThrownBy(() -> calendar.businessDateOf(Instant.parse("2026-01-01T04:59:59Z")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calendar.addBusinessDays(LocalDate.parse("2027-12-31"), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calendar.dueAt(Instant.parse("2027-12-30T17:00:00Z"), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ac_5_11_invalidDayCountsFailExplicitly() {
        LocalDate day = LocalDate.parse("2026-11-05");
        assertThatThrownBy(() -> calendar.addBusinessDays(day, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calendar.dueAt(calendar.startOfDay(day), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calendar.dueAt(calendar.startOfDay(day), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ac_5_11_calendarIsDiscoveredAsASpringBean() {
        try (var context = new AnnotationConfigApplicationContext("dev.ledgerlab.shared")) {
            assertThat(context.getBean(BusinessCalendar.class).isBusinessDay(LocalDate.parse("2026-11-11")))
                    .isFalse();
        }
    }
}
