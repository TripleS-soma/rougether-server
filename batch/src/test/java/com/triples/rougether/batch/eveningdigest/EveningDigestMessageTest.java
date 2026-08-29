package com.triples.rougether.batch.eveningdigest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EveningDigestMessageTest {

    @Test
    void 전체_미완료_수와_루틴_투두_수를_본문에_포함한다() {
        assertThat(EveningDigestMessage.TITLE).isEqualTo("오늘의 루틴을 마무리해 볼까요?");
        assertThat(EveningDigestMessage.body(2, 1))
                .isEqualTo("오늘 아직 3개가 남았어요. 루틴 2개 · 투두 1개를 마무리해볼까요?");
    }
}
