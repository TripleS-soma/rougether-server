package com.triples.rougether.adminapi.notification.campaign.service;

import com.triples.rougether.adminapi.notification.campaign.dto.*;
import com.triples.rougether.adminapi.notification.campaign.error.NotificationCampaignException;
import com.triples.rougether.domain.admin.entity.AdminUser;
import com.triples.rougether.domain.notification.campaign.entity.*;
import com.triples.rougether.domain.notification.campaign.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class NotificationCampaignCommandService {
    private final NotificationCampaignRepository campaignRepository;
    private final NotificationCampaignRecipientRepository recipientRepository;
    private final NotificationCampaignAudienceService audienceService;
    private final NotificationCampaignQueryService queryService;

    public NotificationCampaignResponse create(NotificationCampaignCreateRequest request, String username) {
        AdminUser admin = campaignRepository.lockAdmin(username).orElseThrow(() ->
                new NotificationCampaignException("ADMIN_NOT_FOUND", "운영자 계정을 찾을 수 없습니다.", 401));
        String title = request.title().strip(), body = request.body().strip();
        validateContent(title, body);
        List<Long> requestedIds = NotificationCampaignAudienceService.normalizeIds(request.userIds());
        String fingerprint = fingerprint(title, body, request.audience(), request.pushEnabled(), requestedIds);
        NotificationCampaign existing = campaignRepository
                .findByCreatedByIdAndRequestId(admin.getId(), request.requestId().toString()).orElse(null);
        if (existing != null) {
            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                throw new NotificationCampaignException("NOTIFICATION_REQUEST_CONFLICT",
                        "같은 요청 ID에 다른 내용이 사용되었습니다. 새 요청으로 작성해 주세요.", 409);
            }
            return queryService.response(existing);
        }
        List<Long> targets = audienceService.resolve(request.audience(), requestedIds);
        if (targets.isEmpty()) {
            throw NotificationCampaignException.invalid("발송 가능한 사용자가 없습니다.");
        }
        NotificationCampaign campaign = campaignRepository.save(NotificationCampaign.draft(admin,
                request.requestId().toString(), fingerprint, title, body, request.audience(),
                request.pushEnabled(), targets.size()));
        recipientRepository.saveAll(targets.stream().map(id ->
                NotificationCampaignRecipient.snapshot(campaign, id)).toList());
        recipientRepository.flush();
        return queryService.response(campaign);
    }

    public NotificationCampaignResponse send(Long id, NotificationCampaignSendRequest request, String username) {
        if (!Boolean.TRUE.equals(request.confirmed())) {
            throw NotificationCampaignException.invalid("발송 확인이 필요합니다.");
        }
        NotificationCampaign campaign = campaignRepository.findByIdForUpdate(id)
                .orElseThrow(NotificationCampaignQueryService::notFound);
        AdminUser approver = campaignRepository.lockAdmin(username).orElseThrow(() ->
                new NotificationCampaignException("ADMIN_NOT_FOUND", "운영자 계정을 찾을 수 없습니다.", 401));
        if (campaign.getRecipientCount() != request.expectedRecipientCount()) {
            throw new NotificationCampaignException("NOTIFICATION_RECIPIENT_COUNT_CHANGED",
                    "확인한 대상 수와 저장된 대상 수가 다릅니다. 초안을 다시 확인해 주세요.", 409);
        }
        // 이미 승인된 발송은 다시 시작하지 않음.
        campaign.start(Instant.now(), approver.getUsername());
        return queryService.response(campaign);
    }

    public static void validateContent(String title, String body) {
        if (title == null || body == null || title.isBlank() || body.isBlank()
                || title.length() > 255 || body.length() > 1000) {
            throw NotificationCampaignException.invalid("제목은 1~255자, 내용은 1~1000자로 입력해 주세요.");
        }
    }

    private static String fingerprint(String title, String body, Object audience, boolean push, List<Long> ids) {
        try {
            // 길이를 포함해 문자열 경계가 다른 입력이 같은 지문으로 합쳐지지 않게 함.
            String canonical = title.length() + ":" + title + body.length() + ":" + body
                    + "|" + audience + "|" + push + "|" + ids;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
