package com.triples.rougether.adminapi.feedback.dto;

import com.triples.rougether.domain.feedback.entity.Suggestion;
import java.time.Instant;

public record SuggestionResponse(Long id, String title, String content, Instant createdAt, Long userId, String nickname) {
    public static SuggestionResponse of(Suggestion item) {
        return new SuggestionResponse(item.getId(), item.getTitle(), item.getContent(), item.getCreatedAt(), item.getUser().getId(), item.getUser().getNickname());
    }
}
