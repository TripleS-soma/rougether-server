package com.triples.rougether.userapi.feed.dto;

import com.triples.rougether.domain.feed.entity.HouseFeedNotificationSetting;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "집 피드 새 글 푸시의 집별 설정. 한 번도 바꾸지 않았으면 기본값(자유글 true, 인증글 false)")
public record HouseFeedNotificationResponse(Long houseId, boolean freePostPush, boolean verificationPostPush) {
    public static HouseFeedNotificationResponse of(HouseFeedNotificationSetting setting) {
        return new HouseFeedNotificationResponse(setting.getHouseId(), setting.isFreePostPush(), setting.isVerificationPostPush());
    }
    public static HouseFeedNotificationResponse defaults(Long houseId) {
        return new HouseFeedNotificationResponse(houseId, HouseFeedNotificationSetting.DEFAULT_FREE_POST_PUSH,
                HouseFeedNotificationSetting.DEFAULT_VERIFICATION_POST_PUSH);
    }
}
