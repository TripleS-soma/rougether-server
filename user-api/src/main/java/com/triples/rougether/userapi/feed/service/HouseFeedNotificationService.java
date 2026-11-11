package com.triples.rougether.userapi.feed.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.feed.entity.HouseFeedNotificationSetting;
import com.triples.rougether.domain.feed.repository.HouseFeedNotificationSettingRepository;
import com.triples.rougether.userapi.feed.dto.HouseFeedNotificationRequest;
import com.triples.rougether.userapi.feed.dto.HouseFeedNotificationResponse;
import com.triples.rougether.userapi.feed.error.FeedErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 집 피드 새 글 푸시의 집별 설정(#530). 그 집의 ACTIVE 구성원만 조회·변경함.
@Service
@RequiredArgsConstructor
public class HouseFeedNotificationService {
    private final FeedAccess access;
    private final HouseFeedNotificationSettingRepository settings;

    @Transactional(readOnly = true)
    public HouseFeedNotificationResponse get(Long userId, Long houseId) {
        access.active(userId);
        access.requireHouseParticipant(houseId, userId);
        return settings.findByHouseIdAndUserId(houseId, userId).map(HouseFeedNotificationResponse::of)
                .orElseGet(() -> HouseFeedNotificationResponse.defaults(houseId));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public HouseFeedNotificationResponse change(Long userId, Long houseId, HouseFeedNotificationRequest request) {
        if (request == null || (request.freePostPush() == null && request.verificationPostPush() == null))
            throw new BusinessException(FeedErrorCode.FEED_INPUT_INVALID);
        // 회원 행 잠금으로 같은 사용자의 동시 최초 저장을 직렬화해 (집, 회원) 중복 행을 만들지 않음.
        access.lockActive(userId);
        access.requireHouseParticipant(houseId, userId);
        HouseFeedNotificationSetting setting = settings.findByHouseIdAndUserId(houseId, userId)
                .orElseGet(() -> settings.save(HouseFeedNotificationSetting.defaults(houseId, userId)));
        setting.change(request.freePostPush(), request.verificationPostPush());
        return HouseFeedNotificationResponse.of(setting);
    }
}
