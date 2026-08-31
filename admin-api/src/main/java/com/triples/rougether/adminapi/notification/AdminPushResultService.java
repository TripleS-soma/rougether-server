package com.triples.rougether.adminapi.notification;

import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.infra.fcm.FcmSendResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// push 결과의 DB 반영. 실행기·리스너가 트랜잭션 밖에서 돌므로 여기서 자체 트랜잭션으로 커밋까지 책임진다
// (user-api NotificationPushStatusService + DeviceTokenService 무효 토큰 정리를 합친 미러).
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdminPushResultService {

    private final NotificationRepository notificationRepository;
    private final UserDeviceTokenRepository userDeviceTokenRepository;

    public void record(Long notificationId, Long userId, FcmSendResult result, int tokenCount) {
        if (!result.invalidTokens().isEmpty()) {
            // 발송 시점 소유자 조건으로 스코프 - 발송~응답 사이 소유권이 이전된 토큰을 지우지 않기 위함
            userDeviceTokenRepository.deleteAllByTokenInAndUserId(result.invalidTokens(), userId);
        }
        if (result.successCount() > 0) {
            markSent(notificationId);
            return;
        }
        log.warn("FCM 발송 실패 - notificationId={}, userId={}, tokenCount={}, invalidTokenCount={}",
                notificationId, userId, tokenCount, result.invalidTokens().size());
        markFailed(notificationId);
    }

    public void markSent(Long notificationId) {
        notificationRepository.findById(notificationId).ifPresent(Notification::markPushSent);
    }

    public void markFailed(Long notificationId) {
        notificationRepository.findById(notificationId).ifPresent(Notification::markPushFailed);
    }

    public void markBlocked(Long notificationId) {
        notificationRepository.findById(notificationId).ifPresent(Notification::markPushBlocked);
    }
}
