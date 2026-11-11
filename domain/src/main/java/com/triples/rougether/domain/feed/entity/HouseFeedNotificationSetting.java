package com.triples.rougether.domain.feed.entity;

import com.triples.rougether.domain.support.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 집 피드 새 글 푸시의 집별 설정(#530). 행이 없는 구성원은 기본값으로 봄.
@Getter
@Entity
@Table(name = "house_feed_notification_settings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HouseFeedNotificationSetting extends BaseEntity {
    public static final boolean DEFAULT_FREE_POST_PUSH = true;
    public static final boolean DEFAULT_VERIFICATION_POST_PUSH = false;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "house_id", nullable = false, updatable = false) private Long houseId;
    @Column(name = "user_id", nullable = false, updatable = false) private Long userId;
    @Column(name = "free_post_push", nullable = false) private boolean freePostPush;
    @Column(name = "verification_post_push", nullable = false) private boolean verificationPostPush;

    public static HouseFeedNotificationSetting defaults(Long houseId, Long userId) {
        HouseFeedNotificationSetting setting = new HouseFeedNotificationSetting();
        setting.houseId = houseId; setting.userId = userId;
        setting.freePostPush = DEFAULT_FREE_POST_PUSH; setting.verificationPostPush = DEFAULT_VERIFICATION_POST_PUSH;
        return setting;
    }
    // 생략(null)한 쪽은 유지함.
    public void change(Boolean freePostPush, Boolean verificationPostPush) {
        if (freePostPush != null) this.freePostPush = freePostPush;
        if (verificationPostPush != null) this.verificationPostPush = verificationPostPush;
    }
    public boolean allows(FeedBoardType boardType) {
        return boardType == FeedBoardType.VERIFICATION ? verificationPostPush : freePostPush;
    }
    public static boolean allowsByDefault(FeedBoardType boardType) {
        return boardType == FeedBoardType.VERIFICATION ? DEFAULT_VERIFICATION_POST_PUSH : DEFAULT_FREE_POST_PUSH;
    }
}
