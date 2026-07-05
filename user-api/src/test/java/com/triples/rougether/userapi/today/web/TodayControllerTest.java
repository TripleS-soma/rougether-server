package com.triples.rougether.userapi.today.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.today.dto.TodayCategoryGroup;
import com.triples.rougether.userapi.today.dto.TodayResponse;
import com.triples.rougether.userapi.today.dto.TodayRoutineItem;
import com.triples.rougether.userapi.today.dto.TodayStreak;
import com.triples.rougether.userapi.today.dto.TodaySummary;
import com.triples.rougether.userapi.today.dto.TodayTodoItem;
import com.triples.rougether.userapi.today.service.TodayService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TodayController.class)
@AutoConfigureMockMvc(addFilters = false)
class TodayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TodayService todayService;
    @MockitoBean
    private CurrentUserArgumentResolver currentUserArgumentResolver;
    // JwtAuthenticationFilter가 슬라이스에 로드되며 요구함.
    @MockitoBean
    private TokenService tokenService;

    @BeforeEach
    void stubCurrentUser() throws Exception {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(1L, MemberRole.NORMAL));
    }

    @Test
    void 응답은_categories_summary_streak_구조로_반환한다() throws Exception {
        TodayResponse response = new TodayResponse(LocalDate.of(2026, 6, 29),
                List.of(new TodayCategoryGroup(3L,
                        List.of(new TodayRoutineItem(10L, "아침 운동", LocalTime.of(7, 0),
                                AuthType.CHECK, true)),
                        List.of(new TodayTodoItem(20L, "장보기", LocalDate.of(2026, 6, 29),
                                TodoStatus.PENDING, null)))),
                new TodaySummary(1, 1, 0.5),
                new TodayStreak(5, 9, LocalDate.of(2026, 6, 29)));
        when(todayService.today(eq(1L), any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-06-29"))
                .andExpect(jsonPath("$.categories[0].categoryId").value(3))
                .andExpect(jsonPath("$.categories[0].routines[0].id").value(10))
                .andExpect(jsonPath("$.categories[0].routines[0].completed").value(true))
                .andExpect(jsonPath("$.categories[0].todos[0].status").value("PENDING"))
                .andExpect(jsonPath("$.summary.completedCount").value(1))
                .andExpect(jsonPath("$.summary.progressRate").value(0.5))
                .andExpect(jsonPath("$.streak.currentCount").value(5));
    }

    @Test
    void date_쿼리를_서비스로_전달한다() throws Exception {
        when(todayService.today(eq(1L), eq(LocalDate.of(2026, 7, 1))))
                .thenReturn(new TodayResponse(LocalDate.of(2026, 7, 1), List.of(),
                        new TodaySummary(0, 0, 0.0), new TodayStreak(0, 0, null)));

        mockMvc.perform(get("/api/v1/today").param("date", "2026-07-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-07-01"));
    }

    @Test
    void 빈_상태면_progressRate는_0이다() throws Exception {
        when(todayService.today(eq(1L), any()))
                .thenReturn(new TodayResponse(LocalDate.of(2026, 6, 29), List.of(),
                        new TodaySummary(0, 0, 0.0), new TodayStreak(0, 0, null)));

        mockMvc.perform(get("/api/v1/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.progressRate").value(0.0))
                .andExpect(jsonPath("$.categories").isEmpty());
    }
}
