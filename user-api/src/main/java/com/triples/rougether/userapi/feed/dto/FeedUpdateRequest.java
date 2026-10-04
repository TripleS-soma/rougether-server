package com.triples.rougether.userapi.feed.dto;

import com.triples.rougether.domain.feed.entity.FeedBoardType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

// 생략한 필드는 기존 값을 유지함. 구버전 앱의 { content } 요청은 본문만 바꿈.
public record FeedUpdateRequest(@Size(max = 2000) String content, FeedBoardType boardType,
                                @Valid FeedRoutineCompletionRequest routineCompletion) {
    public FeedUpdateRequest(String content) { this(content, null, null); }
}
