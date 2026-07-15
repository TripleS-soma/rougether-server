package com.triples.rougether.userapi.notification.service;

import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// FcmPushExecutor가 @Async 스레드(주변 트랜잭션 없음)에서 호출 - 별도 @Transactional 경로로 커밋까지 스스로 책임진다.
// (DeviceTokenService와 동일한 패턴)
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationPushStatusService {

    private final NotificationRepository notificationRepository;

    public void markSent(Long notificationId) {
        notificationRepository.findById(notificationId).ifPresent(Notification::markPushSent);
    }

    public void markFailed(Long notificationId) {
        notificationRepository.findById(notificationId).ifPresent(Notification::markPushFailed);
    }
}
