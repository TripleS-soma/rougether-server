package com.triples.rougether.userapi.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.userapi.notification.dto.NotificationListResponse;
import com.triples.rougether.userapi.notification.service.NotificationQueryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class NotificationQueryServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @InjectMocks private NotificationQueryService notificationQueryService;

    private Notification notificationWithId(long id) {
        Notification notification = mock(Notification.class);
        lenient().when(notification.getId()).thenReturn(id);
        lenient().when(notification.getType()).thenReturn(NotificationType.ROUTINE_REMINDER);
        lenient().when(notification.getTitle()).thenReturn("제목");
        lenient().when(notification.getBody()).thenReturn("내용");
        lenient().when(notification.isRead()).thenReturn(false);
        lenient().when(notification.getCreatedAt()).thenReturn(Instant.parse("2026-07-05T00:00:00Z"));
        return notification;
    }

    @Test
    void size보다_많이_조회되면_hasNext_true_이고_nextCursor는_페이지_마지막_id() {
        List<Notification> found = List.of(notificationWithId(5L), notificationWithId(4L), notificationWithId(3L));
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(notificationRepository.findPageByCursor(eq(1L), isNull(), pageable.capture())).thenReturn(found);

        NotificationListResponse response = notificationQueryService.getNotifications(1L, null, 2);

        assertThat(pageable.getValue().getPageSize()).isEqualTo(3); // hasNext 판정용 size+1 조회
        assertThat(response.hasNext()).isTrue();
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).notificationId()).isEqualTo(5L);
        assertThat(response.nextCursor()).isEqualTo(4L);
    }

    @Test
    void 정확히_size만큼이면_hasNext_false_이고_nextCursor는_null() {
        List<Notification> found = List.of(notificationWithId(3L), notificationWithId(2L));
        when(notificationRepository.findPageByCursor(eq(1L), eq(4L), any(Pageable.class)))
                .thenReturn(found);

        NotificationListResponse response = notificationQueryService.getNotifications(1L, 4L, 2);

        assertThat(response.hasNext()).isFalse();
        assertThat(response.items()).hasSize(2);
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void 알림이_없으면_빈_목록() {
        when(notificationRepository.findPageByCursor(eq(1L), isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        NotificationListResponse response = notificationQueryService.getNotifications(1L, null, 20);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }
}
