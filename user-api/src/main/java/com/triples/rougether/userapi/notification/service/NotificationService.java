package com.triples.rougether.userapi.notification.service;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 알림 공용 진입점. 알림 내역 저장(동기)과 FCM push(비동기)를 분리함 — push 실패해도 내역은 남음(best-effort).
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final FcmPushExecutor fcmPushExecutor;

    public void send(Long userId, NotificationType type, String title, String body) {
        User user = userRepository.getReferenceById(userId);
        notificationRepository.save(Notification.create(user, type, title, body, null));

        fcmPushExecutor.push(userId, title, body);
    }
}
