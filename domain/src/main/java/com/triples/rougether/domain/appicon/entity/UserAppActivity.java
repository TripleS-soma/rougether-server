package com.triples.rougether.domain.appicon.entity;

import com.triples.rougether.domain.appicon.AppIconState;
import com.triples.rougether.domain.member.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "user_app_activity")
public class UserAppActivity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "last_foreground_at", nullable = false)
    private Instant lastForegroundAt;

    @Column(name = "last_notified_stage", nullable = false)
    private int lastNotifiedStage;

    // 최신 알림 id가 이전 미접속 회차의 잔존 PENDING을 무효화함. 수신함 삭제와 독립인 논리 참조임.
    @Column(name = "last_notification_id")
    private Long lastNotificationId;

    private UserAppActivity(User user, Instant now) {
        this.user = user;
        this.lastForegroundAt = now;
    }

    public static UserAppActivity start(User user, Instant now) {
        return new UserAppActivity(user, now);
    }

    // 모든 쓰기는 사용자 행 잠금 뒤 수행함. 늦게 처리된 요청이 최신 활동을 과거로 돌리지 않음.
    public void recordForeground(Instant now) {
        if (now.isAfter(lastForegroundAt)) {
            lastForegroundAt = now;
        }
        lastNotifiedStage = 0;
        lastNotificationId = null;
    }

    public void recordNotification(AppIconState state, Long notificationId) {
        if (state.inactivityStage() <= lastNotifiedStage || notificationId == null) {
            throw new IllegalArgumentException("새 미접속 단계와 알림 id가 필요함");
        }
        lastNotifiedStage = state.inactivityStage();
        lastNotificationId = notificationId;
    }
}
