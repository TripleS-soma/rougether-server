package com.triples.rougether.domain.notification.entity;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.support.BaseEntity;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 사용자별 알림 설정. 행이 없으면 ON 이 기본값이라, off 로 바꿀 때만 행이 생긴다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notification_setting",
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_setting_user_type",
                columnNames = {"user_id", "type"}))
public class NotificationSetting extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 30, nullable = false)
    private NotificationSettingType type;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    private NotificationSetting(User user, NotificationSettingType type, boolean enabled) {
        this.user = user;
        this.type = type;
        this.enabled = enabled;
    }

    public static NotificationSetting create(User user, NotificationSettingType type, boolean enabled) {
        return new NotificationSetting(user, type, enabled);
    }

    public void changeEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
