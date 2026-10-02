package com.triples.rougether.userapi.feed.dto;

import com.triples.rougether.domain.feed.entity.FeedBoardType;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record FeedCreateRequest(@NotNull UUID clientPostId, @Size(max = 2000) String content,
                                @Size(max = 10) List<@NotNull @Positive Long> imageIds, FeedBoardType boardType) {
    public FeedCreateRequest(UUID clientPostId, String content, List<Long> imageIds) {
        this(clientPostId, content, imageIds, null);
    }
}
