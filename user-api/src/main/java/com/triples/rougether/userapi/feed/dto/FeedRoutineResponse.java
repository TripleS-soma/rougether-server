package com.triples.rougether.userapi.feed.dto;

import java.time.LocalDate;

// 인증글이 연결한 루틴 완료 기록. title은 연결 시점 제목 스냅샷임.
public record FeedRoutineResponse(Long routineId, String title, LocalDate date) { }
