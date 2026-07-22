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

    // 검증이 매직 바이트를 대조하므로 픽스처는 실제 포맷 시그니처를 갖춰야 함
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    private static final byte[] WEBP_MAGIC = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = userRepository.save(User.signUp("profile-image-test@rougether.dev")).getId();
    }

    private MockMultipartFile image(String contentType, byte[] content) {
        return new MockMultipartFile("file", "avatar", contentType, content);
    }

    private byte[] png(byte tailMarker) {
        byte[] content = new byte[PNG_MAGIC.length + 1];
        System.arraycopy(PNG_MAGIC, 0, content, 0, PNG_MAGIC.length);
        content[PNG_MAGIC.length] = tailMarker;
        return content;
    }

    @Test
    void 등록하면_발급된_key가_저장되고_내_정보에_노출된다() {
        when(assetStorageService.upload(any(), eq("image/png"), eq("profile")))
                .thenReturn("profile/new.png");

        ProfileImageResponse response =
                memberService.updateProfileImage(userId, image("image/png", png((byte) 1)));

        assertThat(response.profileImageKey()).isEqualTo("profile/new.png");
        assertThat(userRepository.findById(userId).orElseThrow().getProfileImageKey())
                .isEqualTo("profile/new.png");
        assertThat(memberService.getMe(userId).profileImageKey()).isEqualTo("profile/new.png");
    }

    @Test
    void 교체하면_새로_발급된_key로_갱신된다() {
        when(assetStorageService.upload(any(), eq("image/png"), eq("profile")))
                .thenReturn("profile/old.png", "profile/replaced.png");
        memberService.updateProfileImage(userId, image("image/png", png((byte) 1)));

        memberService.updateProfileImage(userId, image("image/png", png((byte) 2)));

        assertThat(userRepository.findById(userId).orElseThrow().getProfileImageKey())
                .isEqualTo("profile/replaced.png");
    }

    @Test
    void 삭제하면_key가_null로_돌아간다() {
        when(assetStorageService.upload(any(), eq("image/webp"), eq("profile")))
                .thenReturn("profile/to-delete.webp");
        memberService.updateProfileImage(userId, image("image/webp", WEBP_MAGIC));

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
    void 정확히_10MB는_허용된다() {
        byte[] boundary = new byte[10 * 1024 * 1024];
        System.arraycopy(JPEG_MAGIC, 0, boundary, 0, JPEG_MAGIC.length);
        when(assetStorageService.upload(any(), eq("image/jpeg"), eq("profile")))
                .thenReturn("profile/boundary.jpg");

        ProfileImageResponse response =
                memberService.updateProfileImage(userId, image("image/jpeg", boundary));

        assertThat(response.profileImageKey()).isEqualTo("profile/boundary.jpg");
    }

    @Test
    void 선언한_형식과_실제_바이트가_다르면_업로드_없이_400_에러다() {
        assertThatThrownBy(() ->
                memberService.updateProfileImage(userId, image("image/png", JPEG_MAGIC)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.MEMBER_PROFILE_IMAGE_INVALID));

        verifyNoInteractions(assetStorageService);
    }

    @Test
    void content_type이_없으면_업로드_없이_400_에러다() {
        assertThatThrownBy(() ->
                memberService.updateProfileImage(userId, image(null, png((byte) 1))))
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
        memberService.updateProfileImage(userId, image("image/png", png((byte) 1)));
        when(assetStorageService.upload(any(), eq("image/png"), eq("profile")))
                .thenThrow(new RuntimeException("S3 장애"));

        assertThatThrownBy(() ->
                memberService.updateProfileImage(userId, image("image/png", png((byte) 2))))
                .isInstanceOf(RuntimeException.class);

        assertThat(userRepository.findById(userId).orElseThrow().getProfileImageKey())
                .isEqualTo("profile/kept.png");
    }
}
