package com.triples.rougether.userapi.onboarding.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import com.triples.rougether.userapi.onboarding.dto.OnboardingCharacterRequest;
import com.triples.rougether.userapi.onboarding.dto.OnboardingCharacterResponse;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsRequest;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsResponse;
import com.triples.rougether.userapi.onboarding.service.OnboardingCharacterService;
import com.triples.rougether.userapi.onboarding.service.OnboardingGoalService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OnboardingController.class)
@AutoConfigureMockMvc(addFilters = false)
class OnboardingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OnboardingGoalService onboardingGoalService;
    @MockitoBean
    private OnboardingCharacterService onboardingCharacterService;
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
    void 목표_저장은_200과_저장된_상태를_응답한다() throws Exception {
        when(onboardingGoalService.replaceGoals(eq(1L), any(OnboardingGoalsRequest.class)))
                .thenReturn(new OnboardingGoalsResponse(
                        List.of(new OnboardingGoalsResponse.GoalSelection(10L, "wake_up", "일찍 일어나기")), 10L));

        mockMvc.perform(put("/api/v1/onboarding/goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goalIds\":[10],\"primaryGoalId\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goals[0].goalId").value(10))
                .andExpect(jsonPath("$.goals[0].code").value("wake_up"))
                .andExpect(jsonPath("$.primaryGoalId").value(10));
    }

    @Test
    void 목표가_비면_400과_GOAL_REQUIRED를_응답한다() throws Exception {
        when(onboardingGoalService.replaceGoals(eq(1L), any(OnboardingGoalsRequest.class)))
                .thenThrow(new BusinessException(MemberErrorCode.GOAL_REQUIRED));

        mockMvc.perform(put("/api/v1/onboarding/goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goalIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GOAL_REQUIRED"));
    }

    @Test
    void 비활성_목표는_400과_INVALID_GOAL을_응답한다() throws Exception {
        when(onboardingGoalService.replaceGoals(eq(1L), any(OnboardingGoalsRequest.class)))
                .thenThrow(new BusinessException(MemberErrorCode.INVALID_GOAL));

        mockMvc.perform(put("/api/v1/onboarding/goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goalIds\":[10,999]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GOAL"));
    }

    @Test
    void 대표가_선택에_없으면_400과_PRIMARY_GOAL_NOT_IN_SELECTION을_응답한다() throws Exception {
        when(onboardingGoalService.replaceGoals(eq(1L), any(OnboardingGoalsRequest.class)))
                .thenThrow(new BusinessException(MemberErrorCode.PRIMARY_GOAL_NOT_IN_SELECTION));

        mockMvc.perform(put("/api/v1/onboarding/goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goalIds\":[10],\"primaryGoalId\":20}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRIMARY_GOAL_NOT_IN_SELECTION"));
    }

    @Test
    void 캐릭터_선택은_200과_선택된_캐릭터를_응답한다() throws Exception {
        when(onboardingCharacterService.selectCharacter(eq(1L), eq(5L)))
                .thenReturn(new OnboardingCharacterResponse(5L));

        mockMvc.perform(put("/api/v1/onboarding/character")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterId\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedCharacterId").value(5));
    }

    @Test
    void 비존재_캐릭터는_404와_CHARACTER_NOT_FOUND를_응답한다() throws Exception {
        when(onboardingCharacterService.selectCharacter(eq(1L), eq(999L)))
                .thenThrow(new BusinessException(MemberErrorCode.CHARACTER_NOT_FOUND));

        mockMvc.perform(put("/api/v1/onboarding/character")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterId\":999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHARACTER_NOT_FOUND"));
    }

    @Test
    void 미보유_캐릭터는_409와_CHARACTER_NOT_OWNED를_응답한다() throws Exception {
        when(onboardingCharacterService.selectCharacter(eq(1L), eq(7L)))
                .thenThrow(new BusinessException(MemberErrorCode.CHARACTER_NOT_OWNED));

        mockMvc.perform(put("/api/v1/onboarding/character")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterId\":7}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARACTER_NOT_OWNED"));
    }
}
