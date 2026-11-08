package com.triples.rougether.domain.logingift.entity;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.shop.entity.UserItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "login_gift_participations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoginGiftParticipation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private LoginGiftEvent event;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(name = "eligible_at", nullable = false)
    private Instant eligibleAt;
    @Column(name = "granted_at")
    private Instant grantedAt;
    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reward_user_item_id")
    private UserItem rewardUserItem;

    public static LoginGiftParticipation record(LoginGiftEvent event, User user, Instant at) {
        if (!event.accepts(at)) throw new IllegalArgumentException("이벤트 기간 밖의 접속입니다");
        var participation = new LoginGiftParticipation();
        participation.event = event;
        participation.user = user;
        participation.eligibleAt = at;
        return participation;
    }

    public void grant(UserItem reward, Instant at) {
        if (grantedAt != null) throw new IllegalStateException("이미 지급된 선물입니다");
        rewardUserItem = reward;
        grantedAt = at;
    }

    public void acknowledge(Instant at) {
        if (grantedAt != null && acknowledgedAt == null) acknowledgedAt = at;
    }
}
