package com.triples.rougether.adminapi.attendance.dto;

import com.triples.rougether.domain.attendance.entity.AttendanceEvent;
import java.time.LocalDate;

public record AttendanceEventCreateResponse(
        Long id,
        String code,
        String title,
        LocalDate startsOn,
        LocalDate endsOn,
        int targetDays,
        int dailyCoinAmount,
        int bonusDay,
        int bonusCoinAmount,
        Long rewardItemId,
        int generationCreditAmount) {

    public AttendanceEventCreateResponse(Long id, String code, String title, LocalDate startsOn,
            LocalDate endsOn, int targetDays, int dailyCoinAmount, int bonusDay, int bonusCoinAmount, Long rewardItemId) {
        this(id, code, title, startsOn, endsOn, targetDays, dailyCoinAmount, bonusDay, bonusCoinAmount, rewardItemId, 0);
    }

    public static AttendanceEventCreateResponse of(AttendanceEvent event) {
        return new AttendanceEventCreateResponse(
                event.getId(), event.getCode(), event.getTitle(), event.getStartsOn(), event.getEndsOn(),
                event.getTargetDays(), event.getDailyCoinAmount(), event.getBonusDay(),
                event.getBonusCoinAmount(), event.getRewardItem() == null ? null : event.getRewardItem().getId(),
                event.getGenerationCreditAmount());
    }
}
