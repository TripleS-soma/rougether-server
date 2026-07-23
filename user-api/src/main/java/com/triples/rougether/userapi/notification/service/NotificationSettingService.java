package com.triples.rougether.userapi.notification.service;

import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.NotificationSetting;
import com.triples.rougether.domain.notification.entity.NotificationSettingType;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationSettingRepository;
import com.triples.rougether.userapi.notification.dto.NotificationSettingResponse;
import com.triples.rougether.userapi.notification.dto.NotificationSettingUpdateRequest;
import java.util.EnumMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 알림 설정. 행이 없으면 ON 이 기본값이라 저장된 행만 예외로 취급함.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationSettingService {

    private final NotificationSettingRepository notificationSettingRepository;
    private final UserRepository userRepository;

    public NotificationSettingResponse getSettings(Long userId) {
        return toResponse(enabledByType(userId));
    }

    @Transactional
    public NotificationSettingResponse updateSettings(Long userId, NotificationSettingUpdateRequest request) {
        apply(userId, NotificationSettingType.ALL, request.all());
        apply(userId, NotificationSettingType.REMINDER, request.reminder());
        apply(userId, NotificationSettingType.HOUSE, request.house());
        return toResponse(enabledByType(userId));
    }

    // push 발송 게이트. 마스터(ALL)가 off면 그룹 설정과 무관하게 차단됨.
    public boolean isPushAllowed(Long userId, NotificationType notificationType) {
        Map<NotificationSettingType, Boolean> enabled = enabledByType(userId);
        return isEnabled(enabled, NotificationSettingType.ALL)
                && isEnabled(enabled, notificationType.settingType());
    }

    private void apply(Long userId, NotificationSettingType type, Boolean value) {
        if (value == null) {
            return;
        }
        notificationSettingRepository.findByUserIdAndType(userId, type)
                .ifPresentOrElse(setting -> setting.changeEnabled(value), () -> {
                    // 행 없음 = 이미 ON 이라 on 요청은 저장할 게 없음. off 일 때만 행을 만든다.
                    if (!value) {
                        notificationSettingRepository.save(NotificationSetting.create(
                                userRepository.getReferenceById(userId), type, false));
                    }
                });
    }

    private Map<NotificationSettingType, Boolean> enabledByType(Long userId) {
        Map<NotificationSettingType, Boolean> enabled = new EnumMap<>(NotificationSettingType.class);
        for (NotificationSetting setting : notificationSettingRepository.findAllByUserId(userId)) {
            enabled.put(setting.getType(), setting.isEnabled());
        }
        return enabled;
    }

    private boolean isEnabled(Map<NotificationSettingType, Boolean> enabled, NotificationSettingType type) {
        return enabled.getOrDefault(type, true);
    }

    private NotificationSettingResponse toResponse(Map<NotificationSettingType, Boolean> enabled) {
        return new NotificationSettingResponse(
                isEnabled(enabled, NotificationSettingType.ALL),
                isEnabled(enabled, NotificationSettingType.REMINDER),
                isEnabled(enabled, NotificationSettingType.HOUSE));
    }
}
