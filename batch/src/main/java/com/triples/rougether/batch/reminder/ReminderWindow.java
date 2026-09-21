package com.triples.rougether.batch.reminder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

record ReminderWindow(String timeZone, LocalDate date, LocalTime time, Instant start, Instant end) {
    static ReminderWindow at(Instant instant, String timeZone) {
        var local = instant.atZone(ZoneId.of(timeZone));
        var date = local.toLocalDate();
        return new ReminderWindow(timeZone, date, local.toLocalTime(),
                date.atStartOfDay(local.getZone()).toInstant(), date.plusDays(1).atStartOfDay(local.getZone()).toInstant());
    }
}
