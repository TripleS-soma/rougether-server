package com.triples.rougether.domain.notification.entity;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.support.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

// FCM 발송 대상 디바이스 토큰. 사용자당 N개 허용(멀티디바이스).
// updatedAt은 @LastModifiedDate가 아니라 수동 관리함 — 같은 token 재등록(멱등) 시
// 필드값이 그대로면 Hibernate가 dirty로 보지 않아 auditing이 갱신을 트리거하지 않기 때문.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "user_device_token")
public class UserDeviceToken extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token", length = 255, nullable = false, unique = true)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", length = 20, nullable = false)
    private DevicePlatform platform;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private UserDeviceToken(User user, String token, DevicePlatform platform, Instant now) {
        this.user = user;
        this.token = token;
        this.platform = platform;
        this.updatedAt = now;
    }

    public static UserDeviceToken register(User user, String token, DevicePlatform platform, Instant now) {
        return new UserDeviceToken(user, token, platform, now);
    }

    public boolean isOwnedBy(Long userId) {
        return this.user.getId().equals(userId);
    }
}
