package com.triples.rougether.userapi.notification.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.DevicePlatform;
import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.userapi.notification.error.DeviceTokenErrorCode;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class DeviceTokenService {

    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final UserRepository userRepository;

    // 멱등: 같은 token 재등록 시 updatedAt만 갱신. 다른 user가 등록한 token이면 소유자 이전(기기 재로그인 케이스).
    public void register(Long userId, String token, DevicePlatform platform) {
        Instant now = Instant.now();
        User user = userRepository.getReferenceById(userId);

        userDeviceTokenRepository.findByToken(token)
                .ifPresentOrElse(
                        existing -> existing.reassign(user, platform, now),
                        () -> userDeviceTokenRepository.save(UserDeviceToken.register(user, token, platform, now))
                );
    }

    // 본인 소유 token만 삭제. 존재하지 않거나 타인 소유면 404로 통일(존재 노출 회피).
    public void delete(Long userId, String token) {
        UserDeviceToken deviceToken = userDeviceTokenRepository.findByToken(token)
                .filter(dt -> dt.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(DeviceTokenErrorCode.DEVICE_TOKEN_NOT_FOUND));

        userDeviceTokenRepository.delete(deviceToken);
    }
}
