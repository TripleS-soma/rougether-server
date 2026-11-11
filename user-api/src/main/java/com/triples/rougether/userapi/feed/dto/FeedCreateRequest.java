package com.triples.rougether.userapi.feed.dto;

import com.triples.rougether.domain.feed.entity.FeedBoardType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record FeedCreateRequest(@NotNull UUID clientPostId, @Size(max = 2000) String content,
                                @Size(max = 10) List<@NotNull @Positive Long> imageIds, FeedBoardType boardType,
                                @Valid FeedRoutineCompletionRequest routineCompletion,
                                @Schema(description = "자유글에 연결할 미니게임 기록(선택). FREE 전용이며 routineCompletion과 함께 보낼 수 없음. "
                                        + "boardType을 생략하고 이 값을 보내면 FREE로 등록함", nullable = true)
                                @Valid FeedMinigameRecordRequest minigameRecord,
                                @Schema(description = "글을 올릴 집 ID(선택). 지정하면 그 집 구성원만 보는 집 글로 등록하고, "
                                        + "생략하면 공개 글로 등록함. 등록 뒤에는 바꿀 수 없음", nullable = true)
                                @Positive Long houseId) {
    public FeedCreateRequest(UUID clientPostId, String content, List<Long> imageIds) {
        this(clientPostId, content, imageIds, null, null, null, null);
    }
    public FeedCreateRequest(UUID clientPostId, String content, List<Long> imageIds, FeedBoardType boardType) {
        this(clientPostId, content, imageIds, boardType, null, null, null);
    }
    public FeedCreateRequest(UUID clientPostId, String content, List<Long> imageIds, FeedBoardType boardType,
                             FeedRoutineCompletionRequest routineCompletion) {
        this(clientPostId, content, imageIds, boardType, routineCompletion, null, null);
    }
    public FeedCreateRequest(UUID clientPostId, String content, List<Long> imageIds, FeedBoardType boardType,
                             FeedRoutineCompletionRequest routineCompletion, FeedMinigameRecordRequest minigameRecord) {
        this(clientPostId, content, imageIds, boardType, routineCompletion, minigameRecord, null);
    }
}
