package com.triples.rougether.userapi.notification.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.notification.entity.DevicePlatform;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.userapi.notification.error.DeviceTokenErrorCode;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class DeviceTokenService {

    private final UserDeviceTokenRepository userDeviceTokenRepository;

    public void register(Long userId, String token, DevicePlatform platform) {
        userDeviceTokenRepository.upsert(userId, token, platform.name(), Instant.now());
    }

    public void delete(Long userId, String token) {
        int deleted = userDeviceTokenRepository.deleteByTokenAndUserId(token, userId);
        if (deleted == 0) {
            throw new BusinessException(DeviceTokenErrorCode.DEVICE_TOKEN_NOT_FOUND);
        }
    }

    public void deleteAllByToken(Long userId, List<String> tokens) {
        userDeviceTokenRepository.deleteAllByTokenInAndUserId(tokens, userId);
    }
}
