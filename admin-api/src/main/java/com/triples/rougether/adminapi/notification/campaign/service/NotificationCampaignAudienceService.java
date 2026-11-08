package com.triples.rougether.adminapi.notification.campaign.service;

import com.triples.rougether.adminapi.notification.campaign.dto.NotificationCampaignPreviewResponse;
import com.triples.rougether.adminapi.notification.campaign.error.NotificationCampaignException;
import com.triples.rougether.domain.notification.campaign.entity.NotificationCampaign.Audience;
import com.triples.rougether.domain.notification.campaign.repository.NotificationCampaignAudienceRepository;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.policy.NotificationPushPolicy;
import com.triples.rougether.domain.notification.repository.NotificationSettingRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationCampaignAudienceService {
    private static final int MAX_ALL_RECIPIENTS = 10_000;
    private static final int PAGE_SIZE = 500;
    private final NotificationCampaignAudienceRepository audienceRepository;
    private final NotificationSettingRepository settingRepository;

    public List<Long> resolve(Audience audience, List<Long> suppliedIds) {
        List<Long> ids = normalizeIds(suppliedIds);
        if (audience == null) {
            throw NotificationCampaignException.invalid("발송 대상을 선택해 주세요.");
        }
        if (audience == Audience.SELECTED) {
            if (ids.isEmpty()) {
                throw NotificationCampaignException.invalid("선택 발송할 사용자 ID를 입력해 주세요.");
            }
            List<Long> eligible = audienceRepository.findEligibleIds(ids);
            if (eligible.size() != ids.size()) {
                throw NotificationCampaignException.invalid("존재하지 않거나 알림 대상이 아닌 사용자 ID가 포함되어 있습니다.");
            }
            return eligible;
        }
        if (!ids.isEmpty()) {
            throw NotificationCampaignException.invalid("전체 발송에는 사용자 ID를 함께 입력할 수 없습니다.");
        }
        List<Long> result = new ArrayList<>();
        long after = 0;
        while (true) {
            List<Long> page = audienceRepository.findEligibleAfter(after, PageRequest.of(0, PAGE_SIZE));
            result.addAll(page);
            if (result.size() > MAX_ALL_RECIPIENTS) {
                throw NotificationCampaignException.invalid("한 번에 발송할 수 있는 대상은 최대 10000명입니다.");
            }
            if (page.size() < PAGE_SIZE) {
                return List.copyOf(result);
            }
            after = page.getLast();
        }
    }

    public NotificationCampaignPreviewResponse preview(Audience audience, List<Long> ids, boolean pushEnabled) {
        List<Long> recipients = resolve(audience, ids);
        if (!pushEnabled) {
            return new NotificationCampaignPreviewResponse(recipients.size(), 0, 0, 0, recipients.size());
        }
        int allowed = 0, blocked = 0, tokenless = 0;
        // IN 절과 설정 조회를 고정 크기로 나눠 미리보기가 대량 바인딩을 만들지 않게 함.
        for (int start = 0; start < recipients.size(); start += PAGE_SIZE) {
            List<Long> chunk = recipients.subList(start, Math.min(start + PAGE_SIZE, recipients.size()));
            NotificationPushPolicy policy = NotificationPushPolicy.of(settingRepository.findAllByUserIdIn(chunk));
            Set<Long> withTokens = new HashSet<>(audienceRepository.findUsersWithTokens(chunk));
            for (Long id : chunk) {
                if (!policy.isPushAllowed(id, NotificationType.ADMIN_NOTICE)) {
                    blocked++;
                } else if (!withTokens.contains(id)) {
                    tokenless++;
                } else {
                    allowed++;
                }
            }
        }
        return new NotificationCampaignPreviewResponse(recipients.size(), allowed, blocked, tokenless, 0);
    }

    public static List<Long> normalizeIds(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        if (ids.size() > 1000 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw NotificationCampaignException.invalid("사용자 ID는 양수이며 최대 1000개까지 입력할 수 있습니다.");
        }
        return ids.stream().distinct().sorted().toList();
    }
}
