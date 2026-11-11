package com.triples.rougether.userapi.feedback.dto;

import com.triples.rougether.domain.feedback.entity.Suggestion;
import java.time.Instant;

public record SuggestionResponse(Long id, String title, String content, Instant createdAt) {
    public static SuggestionResponse of(Suggestion item) {
        return new SuggestionResponse(item.getId(), item.getTitle(), item.getContent(), item.getCreatedAt());
    }
}
