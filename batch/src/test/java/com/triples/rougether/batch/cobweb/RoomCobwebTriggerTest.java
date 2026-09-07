package com.triples.rougether.batch.cobweb;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.triples.rougether.domain.notification.repository.NotificationRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RoomCobwebTriggerTest {
    @Test
    void 한_방의_적재가_실패해도_다음_방과_미발송_알림을_처리한다() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RoomCobwebActivationService service = mock(RoomCobwebActivationService.class);
        NotificationRepository notifications = mock(NotificationRepository.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), any(), any(), any(), any()))
                .thenReturn(List.of(1L, 2L), List.of());
        doThrow(new IllegalStateException("적재 실패")).when(service).activate(eq(1L), any());
        new RoomCobwebTrigger(jdbc, service, notifications).activateDueCobwebs();
        verify(service).activate(eq(2L), any());
        verify(notifications).findByTypeInAndPushStatusAndIdGreaterThanOrderByIdAsc(any(), any(), eq(0L), any());
    }
}
