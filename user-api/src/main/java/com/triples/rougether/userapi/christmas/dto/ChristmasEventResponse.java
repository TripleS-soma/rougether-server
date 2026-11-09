package com.triples.rougether.userapi.christmas.dto;

import java.time.LocalDate;

public record ChristmasEventResponse(String code, String title, boolean active,
        LocalDate startsOn, LocalDate endsOn, Long gachaId, Long attendanceEventId,
        boolean popupDismissed) {
}
