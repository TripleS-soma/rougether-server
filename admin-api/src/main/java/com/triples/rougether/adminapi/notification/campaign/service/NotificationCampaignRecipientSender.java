package com.triples.rougether.adminapi.notification.campaign.service;

import com.triples.rougether.adminapi.notification.AdminNotificationService;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.campaign.entity.*;
import com.triples.rougether.domain.notification.campaign.repository.*;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class NotificationCampaignRecipientSender {
    private final NotificationCampaignRecipientRepository recipientRepository;
    private final NotificationCampaignRepository campaignRepository;
    private final UserRepository userRepository;
    private final AdminNotificationService notificationService;

    public void process(Long recipientId) {
        NotificationCampaignRecipient recipient = recipientRepository.findByIdForUpdate(recipientId).orElse(null);
        if (recipient == null || recipient.getStatus() != NotificationCampaignRecipient.Status.PENDING) {
            return;
        }
        NotificationCampaign campaign = recipient.getCampaign();
        if (campaign.getStatus() != NotificationCampaign.Status.SENDING) {
            return;
        }
        User user = userRepository.findByIdForUpdate(recipient.getUserId()).orElse(null);
        if (user == null || user.isDeleted() || !user.isHuman()) {
            recipient.skip();
            return;
        }
        // 알림 생성과 처리 완료를 같은 트랜잭션에 둠. 실패/재시작 시 둘 다 롤백됨.
        Long notificationId = notificationService.sendNotice(user.getId(), campaign.getTitle(), campaign.getBody(),
                campaign.isPushEnabled());
        recipient.created(notificationId);
    }

    public void recordFailure(Long recipientId) {
        recipientRepository.findByIdForUpdate(recipientId)
                .filter(r -> r.getStatus() == NotificationCampaignRecipient.Status.PENDING)
                .ifPresent(NotificationCampaignRecipient::failAttempt);
    }

    public void finish(Long campaignId) {
        NotificationCampaign campaign = campaignRepository.findByIdForUpdate(campaignId).orElse(null);
        if (campaign != null && recipientRepository.countByCampaignIdAndStatus(
                campaignId, NotificationCampaignRecipient.Status.PENDING) == 0) {
            campaign.complete(Instant.now());
        }
    }
}
