package com.triples.rougether.userapi.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.global.storage.AssetStorageService;
import com.triples.rougether.userapi.member.dto.ProfileImageResponse;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class MemberProfileImageServiceTest {

    @Autowired
    private MemberService memberService;
    @Autowired
    private UserRepository userRepository;
    @MockitoBean
    private AssetStorageService assetStorageService;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = userRepository.save(User.signUp("profile-image-test@rougether.dev")).getId();
    }

    private MockMultipartFile image(String contentType, byte[] content) {
        return new MockMultipartFile("file", "avatar", contentType, content);
    }

    @Test
    void 등록하면_발급된_key가_저장되고_내_정보에_노출된다() {
        when(assetStorageService.upload(any(), eq("image/png"), eq("profile")))
                .thenReturn("profile/new.png");

        ProfileImageResponse response =
                memberService.updateProfileImage(userId, image("image/png", new byte[]{1, 2, 3}));

        assertThat(response.profileImageKey()).isEqualTo("profile/new.png");
        assertThat(userRepository.findById(userId).orElseThrow().getProfileImageKey())
                .isEqualTo("profile/new.png");
        assertThat(memberService.getMe(userId).profileImageKey()).isEqualTo("profile/new.png");
    }

    @Test
    void 교체하면_새로_발급된_key로_갱신된다() {
        when(assetStorageService.upload(any(), eq("image/png"), eq("profile")))
                .thenReturn("profile/old.png", "profile/replaced.png");
        memberService.updateProfileImage(userId, image("image/png", new byte[]{1}));

        memberService.updateProfileImage(userId, image("image/png", new byte[]{2}));

        assertThat(userRepository.findById(userId).orElseThrow().getProfileImageKey())
                .isEqualTo("profile/replaced.png");
    }

    @Test
    void 삭제하면_key가_null로_돌아간다() {
        when(assetStorageService.upload(any(), eq("image/webp"), eq("profile")))
                .thenReturn("profile/to-delete.webp");
        memberService.updateProfileImage(userId, image("image/webp", new byte[]{1}));

        memberService.deleteProfileImage(userId);

        assertThat(userRepository.findById(userId).orElseThrow().getProfileImageKey()).isNull();
        assertThat(memberService.getMe(userId).profileImageKey()).isNull();
    }

    @Test
    void 허용되지_않은_형식이면_업로드_없이_400_에러다() {
        assertThatThrownBy(() ->
                memberService.updateProfileImage(userId, image("image/gif", new byte[]{1})))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.MEMBER_PROFILE_IMAGE_INVALID));

        verifyNoInteractions(assetStorageService);
    }

    @Test
    void 크기가_10MB를_초과하면_업로드_없이_400_에러다() {
        byte[] oversized = new byte[10 * 1024 * 1024 + 1];

        assertThatThrownBy(() ->
                memberService.updateProfileImage(userId, image("image/jpeg", oversized)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.MEMBER_PROFILE_IMAGE_INVALID));

        verifyNoInteractions(assetStorageService);
    }

    @Test
    void 빈_파일이면_업로드_없이_400_에러다() {
        assertThatThrownBy(() ->
                memberService.updateProfileImage(userId, image("image/png", new byte[0])))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.MEMBER_PROFILE_IMAGE_INVALID));

        verifyNoInteractions(assetStorageService);
    }

    @Test
    void S3_업로드가_실패하면_기존_key가_유지된다() {
        when(assetStorageService.upload(any(), eq("image/png"), eq("profile")))
                .thenReturn("profile/kept.png");
        memberService.updateProfileImage(userId, image("image/png", new byte[]{1}));
        when(assetStorageService.upload(any(), eq("image/png"), eq("profile")))
                .thenThrow(new RuntimeException("S3 장애"));

        assertThatThrownBy(() ->
                memberService.updateProfileImage(userId, image("image/png", new byte[]{2})))
                .isInstanceOf(RuntimeException.class);

        assertThat(userRepository.findById(userId).orElseThrow().getProfileImageKey())
                .isEqualTo("profile/kept.png");
    }
}
