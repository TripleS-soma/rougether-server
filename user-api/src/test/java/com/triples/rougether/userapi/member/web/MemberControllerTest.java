package com.triples.rougether.userapi.member.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.member.dto.MeResponse;
import com.triples.rougether.userapi.member.dto.ProfileImageResponse;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import com.triples.rougether.userapi.member.service.MemberService;
import com.triples.rougether.userapi.onboarding.dto.OnboardingSummary;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MemberController.class)
@AutoConfigureMockMvc(addFilters = false)
class MemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberService memberService;
    @MockitoBean
    private CurrentUserArgumentResolver currentUserArgumentResolver;
    @MockitoBean
    private TokenService tokenService;

    @BeforeEach
    void stubCurrentUser() throws Exception {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(1L, MemberRole.NORMAL));
    }

    @Test
    void 내_정보는_온보딩_요약을_포함해_응답한다() throws Exception {
        when(memberService.getMe(1L)).thenReturn(new MeResponse(
                1L, "루티니", null, null, Instant.parse("2026-07-05T03:34:56Z"),
                new OnboardingSummary(true, 3L, 5L)));

        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.nickname").value("루티니"))
                // lastLoginAt → lastAccessedAt rename (breaking) 반영 확인
                .andExpect(jsonPath("$.lastAccessedAt").value("2026-07-05T03:34:56Z"))
                .andExpect(jsonPath("$.lastLoginAt").doesNotExist())
                .andExpect(jsonPath("$.onboarding.completed").value(true))
                .andExpect(jsonPath("$.onboarding.primaryGoalId").value(3))
                .andExpect(jsonPath("$.onboarding.selectedCharacterId").value(5));
    }

    @Test
    void 프로필_사진을_multipart로_올리면_인증_사용자_본인_key가_발급된다() throws Exception {
        when(memberService.updateProfileImage(eq(1L), any()))
                .thenReturn(new ProfileImageResponse("profile/issued.png"));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/me/profile-image")
                        .file(new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1, 2, 3})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageKey").value("profile/issued.png"));

        verify(memberService).updateProfileImage(eq(1L), any());
    }

    @Test
    void 프로필_사진_검증_실패는_400과_에러코드로_응답한다() throws Exception {
        when(memberService.updateProfileImage(eq(1L), any()))
                .thenThrow(new BusinessException(MemberErrorCode.MEMBER_PROFILE_IMAGE_INVALID));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/me/profile-image")
                        .file(new MockMultipartFile("file", "avatar.gif", "image/gif", new byte[]{1})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_PROFILE_IMAGE_INVALID"));
    }

    @Test
    void 프로필_사진_file_파트가_누락되면_400으로_응답한다() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/me/profile-image"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 프로필_사진_삭제는_인증_사용자_본인_기준으로_204를_반환한다() throws Exception {
        mockMvc.perform(delete("/api/v1/me/profile-image"))
                .andExpect(status().isNoContent());

        verify(memberService).deleteProfileImage(1L);
    }
}
