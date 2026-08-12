package com.triples.rougether.batch.cobweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class RoomCobwebTriggerTest {

    @Mock private JdbcTemplate jdbcTemplate;

    @Test
    void 미접속_방_신규_생성과_청소후_재활성화를_한번씩_수행한다() {
        when(jdbcTemplate.update(any(String.class), any(), any(), any())).thenReturn(2);
        RoomCobwebTrigger trigger = new RoomCobwebTrigger(jdbcTemplate);

        trigger.activateDueCobwebs();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).update(sql.capture(), any(), any(), any());
        assertThat(sql.getAllValues().get(0)).contains("INSERT INTO room_cobwebs")
                .contains("NOT EXISTS");
        assertThat(sql.getAllValues().get(1)).contains("UPDATE room_cobwebs")
                .contains("c.cleaned_at IS NOT NULL")
                .contains("u.last_accessed_at, u.created_at");
    }

    @Test
    void 판정_기준은_실행시각보다_정확히_이틀_전이다() {
        when(jdbcTemplate.update(any(String.class), any(), any(), any())).thenReturn(0);
        RoomCobwebTrigger trigger = new RoomCobwebTrigger(jdbcTemplate);
        Instant before = Instant.now();

        trigger.activateDueCobwebs();

        verify(jdbcTemplate, times(2)).update(any(String.class), any(), any(),
                argThat(value -> {
                    Instant cutoff = ((Timestamp) value).toInstant();
                    Instant expected = before.minus(Duration.ofDays(2));
                    return !cutoff.isBefore(expected) && cutoff.isBefore(expected.plusSeconds(2));
                }));
    }
}
