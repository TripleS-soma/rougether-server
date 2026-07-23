package com.triples.rougether.userapi.member.service;

import com.triples.rougether.userapi.member.dto.MeResponse;
import com.triples.rougether.userapi.member.dto.MemberUpdateRequest;
import com.triples.rougether.userapi.member.dto.ProfileImageResponse;
import com.triples.rougether.userapi.member.error.MemberErrorCode;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import com.triples.rougether.userapi.global.storage.AssetStorageService;
import com.triples.rougether.userapi.onboarding.service.OnboardingQueryService;
import java.io.IOException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class MemberService {

    private static final Set<String> ALLOWED_IMAGE_CONTENT_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp");
    private static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024 * 1024;
    private static final String PROFILE_IMAGE_KIND = "profile";

    private final UserRepository userRepository;
    private final OnboardingQueryService onboardingQueryService;
    private final AssetStorageService assetStorageService;

    @Transactional(readOnly = true)
    public MeResponse getMe(Long userId) {
        return toMeResponse(findUser(userId));
    }

    @Transactional
    public MeResponse updateMe(Long userId, MemberUpdateRequest request) {
        User user = findUser(userId);
        user.changeNickname(request.nickname().trim());
        if (request.bio() != null) {
            user.changeBio(request.bio().trim());
        }
        return toMeResponse(user);
    }

    // S3 업로드가 key 갱신보다 먼저라 업로드 실패 시 key 는 그대로 남는다.
    // 커밋 실패 시 남는 S3 orphan 은 지우지 않는다(교체·삭제 시에도 동일, 정리는 후속).
    @Transactional
    public ProfileImageResponse updateProfileImage(Long userId, MultipartFile file) {
        validateProfileImage(file);
        User user = findUser(userId);
        String key = assetStorageService.upload(readBytes(file), file.getContentType(), PROFILE_IMAGE_KIND);
        user.changeProfileImage(key);
        return new ProfileImageResponse(key);
    }

    @Transactional
    public void deleteProfileImage(Long userId) {
        findUser(userId).removeProfileImage();
    }

    private void validateProfileImage(MultipartFile file) {
        // contentType null 가드 필수 — Set.of().contains(null)은 NPE라 곧장 contains 못 함
        String contentType = file == null ? null : file.getContentType();
        if (file == null || file.isEmpty() || contentType == null
                || !ALLOWED_IMAGE_CONTENT_TYPES.contains(contentType)
                || file.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new BusinessException(MemberErrorCode.MEMBER_PROFILE_IMAGE_INVALID);
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            // 전송 중 끊긴 multipart 등 읽기 실패도 클라이언트 입력 문제로 취급함
            throw new BusinessException(MemberErrorCode.MEMBER_PROFILE_IMAGE_INVALID);
        }
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));
    }

    private MeResponse toMeResponse(User user) {
        return new MeResponse(user.getId(), user.getNickname(), user.getBio(), user.getProfileImageKey(),
                user.getLastAccessedAt(), onboardingQueryService.getSummary(user.getId()));
    }
}
