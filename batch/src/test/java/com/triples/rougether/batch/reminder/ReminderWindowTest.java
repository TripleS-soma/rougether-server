package com.triples.rougether.batch.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReminderWindowTest {
    @Test
    void 서머타임_전환일은_23시간과_25시간_중복방지_구간을_쓴다() {
        var spring = ReminderWindow.at(Instant.parse("2026-03-08T07:00:00Z"), "America/New_York");
        var autumn = ReminderWindow.at(Instant.parse("2026-11-01T06:30:00Z"), "America/New_York");
        assertThat(Duration.between(spring.start(), spring.end()).toHours()).isEqualTo(23);
        assertThat(Duration.between(autumn.start(), autumn.end()).toHours()).isEqualTo(25);
        var first = ReminderWindow.at(Instant.parse("2026-11-01T05:30:00Z"), "America/New_York");
        assertThat(autumn).isEqualTo(first);
    }

    @Test
    void 반시간과_45분_시간대도_정확히_변환한다() {
        var india = ReminderWindow.at(Instant.parse("2026-09-21T03:30:00Z"), "Asia/Kolkata");
        var nepal = ReminderWindow.at(Instant.parse("2026-09-21T03:15:00Z"), "Asia/Kathmandu");
        assertThat(india.time().toString()).isEqualTo("09:00");
        assertThat(nepal.time().toString()).isEqualTo("09:00");
    }
}
