package com.triples.rougether.userapi.chat.dto;

import com.triples.rougether.domain.chat.entity.ChatMessageReaction;
import com.triples.rougether.domain.chat.entity.ChatReactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// 한 메시지의 공감을 유형별로 묶은 것. 반응이 있는 유형만 내보냄.
public record ChatReactionResponse(
        @Schema(description = "공감 유형. 허용값: HEART(하트), LIKE(좋아요), CHECK(확인), LAUGH(웃음), SURPRISE(놀람), SAD(슬픔)",
                example = "HEART")
        ChatReactionType type,
        @Schema(description = "이 유형으로 공감한 회원 ID 목록(누른 순서). 탈퇴한 회원은 빠짐", example = "[7, 8]")
        List<Long> userIds) {
    // rows는 누른 순서로 받음. 유형은 enum 선언 순서로 내보냄.
    public static List<ChatReactionResponse> of(List<ChatMessageReaction> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        Map<ChatReactionType, List<Long>> byType = new EnumMap<>(ChatReactionType.class);
        for (ChatMessageReaction row : rows) {
            byType.computeIfAbsent(row.getReactionType(), type -> new ArrayList<>()).add(row.getUserId());
        }
        return byType.entrySet().stream()
                .map(entry -> new ChatReactionResponse(entry.getKey(), List.copyOf(entry.getValue()))).toList();
    }
}
