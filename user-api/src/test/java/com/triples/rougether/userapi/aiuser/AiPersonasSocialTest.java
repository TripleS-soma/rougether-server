package com.triples.rougether.userapi.aiuser;

import static org.assertj.core.api.Assertions.*;
import com.triples.rougether.userapi.aiuser.service.AiPersonas;
import java.time.*;
import org.junit.jupiter.api.Test;

class AiPersonasSocialTest {
    @Test void personaVisitsHaveDistinctFrequencyAndRespectAwakeHours() {
        for (var persona:AiPersonas.all()) {
            int expected=persona.key().startsWith("social-")?4:persona.key().startsWith("observe-")?1:2;
            assertThat(AiPersonas.socialVisits(persona.key())).hasSize(expected);
            assertThat(AiPersonas.socialVisits(persona.key())).allSatisfy(time ->
                    assertThat(AiPersonas.isAwake(persona.key(),time.getHour())).isTrue());
        }
    }
    @Test void nextVisitMovesForwardAcrossMidnightWithoutCatchupOrDuplication() {
        var after=Instant.parse("2026-10-17T14:59:00Z");
        var first=AiPersonas.nextSocialVisit("social-evening",234,after);
        assertThat(first.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()).isEqualTo(LocalDate.of(2026,10,18));
        assertThat(first).isAfter(after);
        assertThat(AiPersonas.nextSocialVisit("social-evening",234,first)).isAfter(first);
        assertThat(AiPersonas.nextSocialVisit("social-evening",233,after)).isNotEqualTo(first);
    }
}
