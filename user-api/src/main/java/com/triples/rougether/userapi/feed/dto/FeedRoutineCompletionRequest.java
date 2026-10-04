package com.triples.rougether.userapi.feed.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

// 인증글에 연결할 루틴 완료 기록. date는 Asia/Seoul 달력 날짜(YYYY-MM-DD)임.
public record FeedRoutineCompletionRequest(@NotNull @Positive Long routineId, @NotNull LocalDate date) { }
