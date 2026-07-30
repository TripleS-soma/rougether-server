package com.triples.rougether.domain.gacha;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.shared.CurrencyType;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class GachaAvailabilityTest {

    private static final Instant NOW = Instant.parse("2026-07-30T07:00:00Z");

    @Test
    void 활성이고_운영_기간_안이면_이용할_수_있다() {
        Gacha gacha = gacha(true, NOW.minusSeconds(1), NOW.plusSeconds(1));

        assertThat(gacha.isAvailableAt(NOW)).isTrue();
    }

    @Test
    void 비활성이거나_운영_기간_밖이면_이용할_수_없다() {
        assertThat(gacha(false, null, null).isAvailableAt(NOW)).isFalse();
        assertThat(gacha(true, NOW.plusSeconds(1), null).isAvailableAt(NOW)).isFalse();
        assertThat(gacha(true, null, NOW.minusSeconds(1)).isAvailableAt(NOW)).isFalse();
    }

    @Test
    void 시작과_종료_시각은_운영_기간에_포함한다() {
        assertThat(gacha(true, NOW, NOW.plusSeconds(1)).isAvailableAt(NOW)).isTrue();
        assertThat(gacha(true, NOW.minusSeconds(1), NOW).isAvailableAt(NOW)).isTrue();
    }

    private Gacha gacha(boolean active, Instant startsAt, Instant endsAt) {
        Gacha gacha = new Gacha("test", "테스트 뽑기", CurrencyType.COIN, 25, 1, null, active);
        ReflectionTestUtils.setField(gacha, "startsAt", startsAt);
        ReflectionTestUtils.setField(gacha, "endsAt", endsAt);
        return gacha;
    }
}
