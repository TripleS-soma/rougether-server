package com.triples.rougether.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import com.triples.rougether.domain.chat.entity.ChatImage;
import com.triples.rougether.domain.member.entity.User;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChatImageRetentionTest {
    @Test
    void 전송_시각부터_30일을_저장하고_파일_삭제_완료를_기록한다() {
        Instant sentAt = Instant.parse("2026-11-15T00:00:00Z");
        var image = ChatImage.reserve(1L, User.signUp("retention@test.dev"), "private/chat/example.jpg", 48, 32,
                sentAt.minusSeconds(3600), sentAt.plusSeconds(3600));
        image.complete();
        image.attach(1L, 0, sentAt);
        Instant until = sentAt.plus(ChatImage.SENT_RETENTION);
        assertThat(image.getExpiresAt()).isEqualTo(until);
        image.markPurged(sentAt);
        assertThat(image.getPurgedAt()).isEqualTo(sentAt);
    }
}
