package com.triples.rougether.userapi.notification.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.userapi.notification.error.NotificationErrorCode;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class NotificationCommandService {

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    // 읽음은 삭제 여부와 무관하게 본인 알림이면 멱등 204(지운 알림의 지연 push 를 탭해도 에러 없음).
    public void markRead(Long userId, Long notificationId) {
        findOwned(userId, notificationId).markRead();
    }

    public void markAllRead(Long userId) {
        notificationRepository.markAllReadByUserId(userId);
    }

    // 알림함 개별 삭제(soft delete). 이미 삭제된 본인 알림에 다시 호출해도 204(멱등, 최초 삭제 시각 유지) -
    // 네트워크 재시도가 성공한 삭제를 실패로 보이게 하지 않음. 본인 소유라 존재 여부가 새로 노출되지도 않음.
    public void delete(Long userId, Long notificationId) {
        findOwned(userId, notificationId).softDelete(Instant.now(clock));
    }

    // 알림함 전체 삭제 - 미삭제 알림에 deleted_at 을 일괄로 찍음. 읽음 여부 무관, 비어 있어도 에러 없음.
    public void deleteAll(Long userId) {
        notificationRepository.softDeleteAllByUserId(userId, Instant.now(clock));
    }

    // 소유권 guard: 존재하지 않거나 타인 소유면 404 로 통일(존재 여부 노출 회피).
    private Notification findOwned(Long userId, Long notificationId) {
        return notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
    }
}
