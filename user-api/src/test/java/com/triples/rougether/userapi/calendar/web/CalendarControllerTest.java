package com.triples.rougether.userapi.calendar.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.calendar.dto.CalendarDayResponse;
import com.triples.rougether.userapi.calendar.service.CalendarService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.today.dto.TodayCategoryGroup;
import com.triples.rougether.userapi.today.dto.TodayRoutineItem;
import com.triples.rougether.userapi.today.dto.TodaySummary;
import com.triples.rougether.userapi.today.dto.TodayTodoItem;
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

@WebMvcTest(CalendarController.class)
@AutoConfigureMockMvc(addFilters = false)
class CalendarControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CalendarService calendarService;
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
    void 응답은_date_categories_summary_구조로_반환한다() throws Exception {
        CalendarDayResponse response = new CalendarDayResponse(LocalDate.of(2026, 6, 29),
                List.of(new TodayCategoryGroup(3L,
                        List.of(new TodayRoutineItem(10L, "아침 운동", LocalTime.of(7, 0),
                                AuthType.CHECK, true)),
                        List.of(new TodayTodoItem(20L, "장보기", LocalDate.of(2026, 6, 29),
                                TodoStatus.PENDING, null)))),
                new TodaySummary(1, 1, 0.5));
        when(calendarService.day(eq(1L), eq(LocalDate.of(2026, 6, 29)))).thenReturn(response);

        mockMvc.perform(get("/api/v1/calendar").param("date", "2026-06-29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-06-29"))
                .andExpect(jsonPath("$.categories[0].categoryId").value(3))
                .andExpect(jsonPath("$.categories[0].routines[0].id").value(10))
                .andExpect(jsonPath("$.categories[0].routines[0].completed").value(true))
                .andExpect(jsonPath("$.categories[0].todos[0].status").value("PENDING"))
                .andExpect(jsonPath("$.summary.completedCount").value(1))
                .andExpect(jsonPath("$.summary.progressRate").value(0.5));
    }

    @Test
    void 과거_날짜도_동일한_계약으로_반환한다() throws Exception {
        // 과거는 완료 log 기반이라 노출 루틴은 모두 completed=true, categoryId는 삭제된 카테고리를 가리킬 수 있음
        CalendarDayResponse response = new CalendarDayResponse(LocalDate.of(2026, 6, 29),
                List.of(new TodayCategoryGroup(99L,
                        List.of(new TodayRoutineItem(10L, "삭제된 루틴", LocalTime.of(7, 0),
                                AuthType.CHECK, true)),
                        List.of())),
                new TodaySummary(1, 0, 1.0));
        when(calendarService.day(eq(1L), eq(LocalDate.of(2026, 6, 29)))).thenReturn(response);

        mockMvc.perform(get("/api/v1/calendar").param("date", "2026-06-29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].categoryId").value(99))
                .andExpect(jsonPath("$.categories[0].routines[0].completed").value(true))
                .andExpect(jsonPath("$.summary.progressRate").value(1.0));
    }

    @Test
    void date_파라미터가_없으면_400() throws Exception {
        mockMvc.perform(get("/api/v1/calendar"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void date_형식이_잘못되면_400() throws Exception {
        mockMvc.perform(get("/api/v1/calendar").param("date", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 인증된_사용자_id로_서비스를_호출한다() throws Exception {
        when(calendarService.day(eq(1L), eq(LocalDate.of(2026, 7, 1))))
                .thenReturn(new CalendarDayResponse(LocalDate.of(2026, 7, 1), List.of(),
                        new TodaySummary(0, 0, 0.0)));

        mockMvc.perform(get("/api/v1/calendar").param("date", "2026-07-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-07-01"))
                .andExpect(jsonPath("$.categories").isEmpty());
    }
}
