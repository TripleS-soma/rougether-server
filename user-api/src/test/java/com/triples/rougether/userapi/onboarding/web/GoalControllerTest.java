package com.triples.rougether.userapi.onboarding.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.onboarding.dto.GoalListResponse;
import com.triples.rougether.userapi.onboarding.service.OnboardingQueryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GoalController.class)
@AutoConfigureMockMvc(addFilters = false)
class GoalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OnboardingQueryService onboardingQueryService;

    @MockitoBean
    private TokenService tokenService;

    @Test
    void 목표_목록_응답_계약() throws Exception {
        when(onboardingQueryService.getGoals()).thenReturn(new GoalListResponse(
                List.of(new GoalListResponse.GoalItem(1L, "wake_up", "일찍 일어나기", 0))));

        mockMvc.perform(get("/api/v1/goals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.items[0].code").value("wake_up"))
                .andExpect(jsonPath("$.items[0].name").value("일찍 일어나기"))
                .andExpect(jsonPath("$.items[0].sortOrder").value(0));
    }
}
