package com.triples.rougether.userapi.christmas;

import static org.assertj.core.api.Assertions.assertThat;
import com.triples.rougether.userapi.christmas.service.ChristmasEventPolicy;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChristmasEventPolicyTest {
    @Test void KST시작과마지막날을포함하고_기간밖과flagOFF를거부한다() {
        var enabled=new ChristmasEventPolicy(true);
        assertThat(enabled.isOpen(Instant.parse("2026-11-08T14:59:59.999999Z"))).isFalse();
        assertThat(enabled.isOpen(Instant.parse("2026-11-08T15:00:00Z"))).isTrue();
        assertThat(enabled.isOpen(Instant.parse("2026-12-25T14:59:59.999999Z"))).isTrue();
        assertThat(enabled.isOpen(Instant.parse("2026-12-25T15:00:00Z"))).isFalse();
        assertThat(new ChristmasEventPolicy(false).isOpen(Instant.parse("2026-11-09T00:00:00Z"))).isFalse();
    }
}
