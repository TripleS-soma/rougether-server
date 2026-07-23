package com.triples.rougether.domain.notification.policy;

import com.triples.rougether.domain.notification.entity.NotificationSetting;
import com.triples.rougether.domain.notification.entity.NotificationSettingType;
import com.triples.rougether.domain.notification.entity.NotificationType;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// push 발송 게이트 판정. user-api(단건)와 batch(chunk 일괄)가 같은 규칙을 쓰도록 도메인에 둠.
// 설정 행이 없으면 ON 이 기본값이라 off 행만 모아두고 없으면 허용으로 판정함.
public final class NotificationPushPolicy {

    private final Map<Long, Set<NotificationSettingType>> disabledByUserId;

    private NotificationPushPolicy(Map<Long, Set<NotificationSettingType>> disabledByUserId) {
        this.disabledByUserId = disabledByUserId;
    }

    // enabled=true 행이 섞여 들어와도 되도록 여기서 off 만 걸러냄
    public static NotificationPushPolicy of(Collection<NotificationSetting> settings) {
        Map<Long, Set<NotificationSettingType>> disabled = new HashMap<>();
        for (NotificationSetting setting : settings) {
            if (setting.isEnabled()) {
                continue;
            }
            disabled.computeIfAbsent(setting.getUser().getId(),
                            key -> EnumSet.noneOf(NotificationSettingType.class))
                    .add(setting.getType());
        }
        return new NotificationPushPolicy(disabled);
    }

    // 마스터(ALL)가 off면 그룹 설정과 무관하게 차단됨
    public boolean isPushAllowed(Long userId, NotificationType notificationType) {
        Set<NotificationSettingType> disabled = disabledByUserId.get(userId);
        if (disabled == null) {
            return true;
        }
        return !disabled.contains(NotificationSettingType.ALL)
                && !disabled.contains(notificationType.settingType());
    }
}
