package com.triples.rougether.userapi.notification.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.userapi.notification.error.NotificationErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class NotificationCommandService {

    private final NotificationRepository notificationRepository;

    public void markRead(Long userId, Long notificationId) {
        Notification notification = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));

        notification.markRead();
    }

    public void markAllRead(Long userId) {
        notificationRepository.markAllReadByUserId(userId);
    }
}
