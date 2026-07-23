package com.triples.rougether.adminapi.moderation.dto;

import com.triples.rougether.domain.moderation.entity.BannedWord;
import java.time.Instant;

public record BannedWordResponse(Long id, String word, Instant createdAt) {

    public static BannedWordResponse of(BannedWord bannedWord) {
        return new BannedWordResponse(bannedWord.getId(), bannedWord.getWord(), bannedWord.getCreatedAt());
    }
}
