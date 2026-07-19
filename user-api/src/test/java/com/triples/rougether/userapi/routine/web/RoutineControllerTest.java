package com.triples.rougether.userapi.routine.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.routine.dto.RepeatDays;
import com.triples.rougether.userapi.routine.dto.RoutineCreateRequest;
import com.triples.rougether.userapi.routine.dto.RoutineListResponse;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import com.triples.rougether.userapi.routine.dto.RoutineLogResponse;
import com.triples.rougether.userapi.routine.dto.RoutineResponse;
import com.triples.rougether.userapi.routine.dto.RoutineUpdateRequest;
import com.triples.rougether.userapi.routine.dto.StreakSummaryResponse;
import com.triples.rougether.userapi.routine.error.RoutineErrorCode;
import com.triples.rougether.userapi.routine.error.RoutineLogErrorCode;
import com.triples.rougether.userapi.routine.service.RoutineLogService;
import com.triples.rougether.userapi.routine.service.RoutineService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RoutineController.class)
@AutoConfigureMockMvc(addFilters = false)
class RoutineControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoutineService routineService;
    @MockitoBean
    private RoutineLogService routineLogService;
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
    void 목록은_items_배열로_감싸_응답한다() throws Exception {
        when(routineService.list(1L, null, null)).thenReturn(new RoutineListResponse(List.of(
                new RoutineResponse(10L, "아침 운동", 3L,
                        AuthType.PHOTO, RoutineStatus.ACTIVE,
                        "WEEKLY", new RepeatDays(List.of("MON")), null, null, null, 10L))));

        mockMvc.perform(get("/api/v1/routines"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(10))
                .andExpect(jsonPath("$.items[0].title").value("아침 운동"))
                .andExpect(jsonPath("$.items[0].categoryId").value(3))
                .andExpect(jsonPath("$.items[0].category").doesNotExist())
                .andExpect(jsonPath("$.items[0].authType").value("PHOTO"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].repeatType").value("WEEKLY"));
    }

    @Test
    void 등록은_201과_생성된_루틴을_응답한다() throws Exception {
        when(routineService.create(eq(1L), any(RoutineCreateRequest.class)))
                .thenReturn(new RoutineResponse(5L, "물 마시기", null, AuthType.CHECK,
                        RoutineStatus.ACTIVE, null, null, null, null, null, 5L));

        mockMvc.perform(post("/api/v1/routines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"물 마시기\",\"authType\":\"CHECK\",\"repeatType\":\"DAILY\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.title").value("물 마시기"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void repeatDays를_객체로_보내면_201로_등록된다() throws Exception {
        when(routineService.create(eq(1L), any(RoutineCreateRequest.class)))
                .thenReturn(new RoutineResponse(5L, "아침 운동", null, AuthType.CHECK,
                        RoutineStatus.ACTIVE, "WEEKLY", new RepeatDays(List.of("MON", "WED")),
                        null, null, null, 5L));

        mockMvc.perform(post("/api/v1/routines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"아침 운동\",\"authType\":\"CHECK\",\"repeatType\":\"WEEKLY\","
                                + "\"repeatDays\":{\"daysOfWeek\":[\"MON\",\"WED\"]}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repeatDays.daysOfWeek[0]").value("MON"))
                .andExpect(jsonPath("$.repeatDays.daysOfWeek[1]").value("WED"));
    }

    @Test
    void 본문이_깨지면_400과_MALFORMED_REQUEST를_응답한다() throws Exception {
        mockMvc.perform(post("/api/v1/routines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"authType\":\"CHECK\",\"repeatDays\":\"문자열\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void title이_비면_400과_VALIDATION_FAILED를_응답한다() throws Exception {
        mockMvc.perform(post("/api/v1/routines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \",\"authType\":\"CHECK\",\"repeatType\":\"DAILY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("title"));
    }

    @Test
    void authType이_없으면_400과_VALIDATION_FAILED를_응답한다() throws Exception {
        mockMvc.perform(post("/api/v1/routines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"물 마시기\",\"repeatType\":\"DAILY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("authType"));
    }

    @Test
    void scheduledTime이_5분_단위가_아니면_400과_VALIDATION_FAILED를_응답한다() throws Exception {
        mockMvc.perform(post("/api/v1/routines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"물 마시기\",\"authType\":\"CHECK\",\"repeatType\":\"DAILY\","
                                + "\"scheduledTime\":\"07:02:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("scheduledTime"));
    }

    @Test
    void repeatType이_없으면_400과_VALIDATION_FAILED를_응답한다() throws Exception {
        mockMvc.perform(post("/api/v1/routines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"물 마시기\",\"authType\":\"CHECK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("repeatType"));
    }

    @Test
    void repeatType이_허용값이_아니면_400과_VALIDATION_FAILED를_응답한다() throws Exception {
        mockMvc.perform(post("/api/v1/routines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"물 마시기\",\"authType\":\"CHECK\",\"repeatType\":\"HOURLY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("repeatType"));
    }

    @Test
    void repeatType이_BIWEEKLY_MONTHLY_YEARLY면_201로_등록된다() throws Exception {
        when(routineService.create(eq(1L), any(RoutineCreateRequest.class)))
                .thenReturn(new RoutineResponse(6L, "격주 운동", null, AuthType.CHECK,
                        RoutineStatus.ACTIVE, "BIWEEKLY", new RepeatDays(List.of("MON")),
                        null, LocalDate.of(2026, 7, 13), null, 6L));

        mockMvc.perform(post("/api/v1/routines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"격주 운동\",\"authType\":\"CHECK\",\"repeatType\":\"BIWEEKLY\","
                                + "\"repeatDays\":{\"daysOfWeek\":[\"MON\"]},\"startsOn\":\"2026-07-13\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repeatType").value("BIWEEKLY"));
    }

    @Test
    void 없는_루틴_조회는_404와_ROUTINE_NOT_FOUND를_응답한다() throws Exception {
        when(routineService.get(eq(1L), eq(99L)))
                .thenThrow(new BusinessException(RoutineErrorCode.ROUTINE_NOT_FOUND));

        mockMvc.perform(get("/api/v1/routines/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTINE_NOT_FOUND"));
    }

    @Test
    void 없는_루틴_수정은_404와_ROUTINE_NOT_FOUND를_응답한다() throws Exception {
        when(routineService.update(eq(1L), eq(99L), any(RoutineUpdateRequest.class)))
                .thenThrow(new BusinessException(RoutineErrorCode.ROUTINE_NOT_FOUND));

        mockMvc.perform(put("/api/v1/routines/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTINE_NOT_FOUND"));
    }

    @Test
    void scheduledTime과_endsOn에_null을_보내면_null로_바인딩된다() throws Exception {
        mockMvc.perform(put("/api/v1/routines/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"scheduledTime\":null,\"endsOn\":null}"))
                .andExpect(status().isOk());

        ArgumentCaptor<RoutineUpdateRequest> captor = ArgumentCaptor.forClass(RoutineUpdateRequest.class);
        verify(routineService).update(eq(1L), eq(7L), captor.capture());
        assertThat(captor.getValue().scheduledTime()).isNull();
        assertThat(captor.getValue().endsOn()).isNull();
    }

    @Test
    void 삭제는_204이고_서비스를_호출한다() throws Exception {
        mockMvc.perform(delete("/api/v1/routines/7"))
                .andExpect(status().isNoContent());

        verify(routineService).delete(1L, 7L);
    }

    @Test
    void 없는_루틴_삭제는_404와_ROUTINE_NOT_FOUND를_응답한다() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(RoutineErrorCode.ROUTINE_NOT_FOUND))
                .when(routineService).delete(1L, 99L);

        mockMvc.perform(delete("/api/v1/routines/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTINE_NOT_FOUND"));
    }

    @Test
    void 완료는_201과_streak_요약을_포함해_응답한다() throws Exception {
        when(routineLogService.complete(eq(1L), eq(7L), any(RoutineLogCreateRequest.class)))
                .thenReturn(new RoutineLogResponse(100L, LocalDate.of(2026, 6, 29),
                        RoutineLogStatus.COMPLETED, Instant.parse("2026-06-29T07:00:00Z"),
                        CurrencyType.COIN, 10, new StreakSummaryResponse(3, 10, LocalDate.of(2026, 6, 29))));

        mockMvc.perform(post("/api/v1/routines/7/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.rewardAmount").value(10))
                .andExpect(jsonPath("$.streak.currentCount").value(3))
                .andExpect(jsonPath("$.streak.longestCount").value(10));
    }

    @Test
    void 중복_완료는_409와_ALREADY_COMPLETED를_응답한다() throws Exception {
        when(routineLogService.complete(eq(1L), eq(7L), any(RoutineLogCreateRequest.class)))
                .thenThrow(new BusinessException(RoutineLogErrorCode.ALREADY_COMPLETED));

        mockMvc.perform(post("/api/v1/routines/7/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_COMPLETED"));
    }

    @Test
    void 완료_취소는_200과_갱신된_streak_요약을_응답한다() throws Exception {
        when(routineLogService.cancel(eq(1L), eq(7L), any(LocalDate.class)))
                .thenReturn(new StreakSummaryResponse(2, 10, LocalDate.of(2026, 6, 28)));

        mockMvc.perform(delete("/api/v1/routines/7/logs").param("date", "2026-06-29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentCount").value(2))
                .andExpect(jsonPath("$.lastSuccessDate").value("2026-06-28"));
    }

    @Test
    void 당일이_아닌_날짜_취소는_409와_LOG_NOT_CANCELABLE을_응답한다() throws Exception {
        when(routineLogService.cancel(eq(1L), eq(7L), any(LocalDate.class)))
                .thenThrow(new BusinessException(RoutineLogErrorCode.LOG_NOT_CANCELABLE));

        mockMvc.perform(delete("/api/v1/routines/7/logs").param("date", "2026-06-28"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOG_NOT_CANCELABLE"));
    }
}
