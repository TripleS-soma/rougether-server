package com.triples.rougether.userapi.feedback.dto;

import com.triples.rougether.domain.feedback.entity.AppUpdate;
import java.time.Instant;

public record AppUpdateResponse(Long id, String title, String content, Instant createdAt) {
    public static AppUpdateResponse of(AppUpdate item) {
        return new AppUpdateResponse(item.getId(), item.getTitle(), item.getContent(), item.getCreatedAt());
    }
}
