package com.triples.rougether.adminapi.notification.campaign.service;

import com.triples.rougether.adminapi.notification.campaign.dto.NotificationCampaignResponse;
import com.triples.rougether.adminapi.notification.campaign.error.NotificationCampaignException;
import com.triples.rougether.domain.notification.campaign.entity.NotificationCampaign;
import com.triples.rougether.domain.notification.campaign.entity.NotificationCampaignRecipient.Status;
import com.triples.rougether.domain.notification.campaign.repository.*;
import com.triples.rougether.domain.notification.entity.PushStatus;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationCampaignQueryService {
    private final NotificationCampaignRepository campaignRepository;
    private final NotificationCampaignRecipientRepository recipientRepository;

    public List<NotificationCampaignResponse> list() {
        return campaignRepository.findRecent(PageRequest.of(0, 50)).stream().map(this::response).toList();
    }

    public NotificationCampaignResponse get(Long id) {
        return response(campaignRepository.findById(id).orElseThrow(NotificationCampaignQueryService::notFound));
    }

    public NotificationCampaignResponse response(NotificationCampaign campaign) {
        Map<Status, Long> recipients = new EnumMap<>(Status.class);
        for (Object[] row : recipientRepository.countByStatus(campaign.getId())) {
            recipients.put((Status) row[0], (Long) row[1]);
        }
        Map<PushStatus, Long> pushes = new EnumMap<>(PushStatus.class);
        for (Object[] row : recipientRepository.countPushByStatus(campaign.getId())) {
            pushes.put((PushStatus) row[0], (Long) row[1]);
        }
        return new NotificationCampaignResponse(campaign.getId(), campaign.getTitle(), campaign.getBody(),
                campaign.getAudience(), campaign.isPushEnabled(), campaign.getStatus(), campaign.getRecipientCount(),
                recipients.getOrDefault(Status.CREATED, 0L),
                pushes.getOrDefault(PushStatus.SENT, 0L), pushes.getOrDefault(PushStatus.FAILED, 0L),
                pushes.getOrDefault(PushStatus.BLOCKED, 0L), pushes.getOrDefault(PushStatus.PENDING, 0L),
                pushes.getOrDefault(PushStatus.NOT_REQUESTED, 0L),
                recipients.getOrDefault(Status.SKIPPED, 0L), recipients.getOrDefault(Status.FAILED, 0L),
                campaign.getCreatedAt(), campaign.getCreatedBy().getUsername(),
                campaign.getApprovedByUsername(), campaign.getStartedAt());
    }

    static NotificationCampaignException notFound() {
        return new NotificationCampaignException("NOTIFICATION_CAMPAIGN_NOT_FOUND", "존재하지 않는 알림 발송입니다.", 404);
    }
}
