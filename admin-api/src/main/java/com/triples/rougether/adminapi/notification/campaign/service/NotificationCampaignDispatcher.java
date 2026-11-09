package com.triples.rougether.adminapi.notification.campaign.service;

import com.triples.rougether.domain.notification.campaign.entity.NotificationCampaign;
import com.triples.rougether.domain.notification.campaign.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCampaignDispatcher {
    private final NotificationCampaignRepository campaignRepository;
    private final NotificationCampaignRecipientRepository recipientRepository;
    private final NotificationCampaignRecipientSender sender;

    // 승인된 초안만 처리함. 재기동 후에도 저장된 대상과 처리 상태에서 이어감.
    @Scheduled(fixedDelayString = "${admin.notification-campaigns.dispatch-delay-ms:1000}")
    public void dispatchBatch() {
        for (Long campaignId : campaignRepository.findIdsByStatus(
                NotificationCampaign.Status.SENDING, PageRequest.of(0, 10))) {
            for (Long recipientId : recipientRepository.findPendingIds(campaignId, PageRequest.of(0, 25))) {
                try {
                    sender.process(recipientId);
                } catch (Exception exception) {
                    log.warn("운영자 알림 내역 생성 실패 - campaignId={}, recipientId={}", campaignId, recipientId,
                            exception);
                    sender.recordFailure(recipientId);
                }
            }
            sender.finish(campaignId);
        }
    }
}
