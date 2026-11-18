package com.triples.rougether.userapi.todo.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.routine.dto.RepeatDays;
import com.triples.rougether.userapi.routine.dto.RoutineResponse;
import com.triples.rougether.userapi.todo.dto.TodoConvertRequest;
import com.triples.rougether.userapi.todo.error.TodoErrorCode;
import com.triples.rougether.userapi.todo.service.TodoConversionService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TodoConversionController.class)
@AutoConfigureMockMvc(addFilters = false)
class TodoConversionControllerTest {

    private static final String WEEKLY_BODY = """
            {"title":"헬스장 가기","categoryId":3,"authType":"CHECK","repeatType":"WEEKLY",
             "repeatDays":{"daysOfWeek":["MON","WED"]},"scheduledTime":"07:00:00","startsOn":"2026-11-18"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TodoConversionService todoConversionService;
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
    void 전환은_201과_만들어진_루틴을_응답하고_요청을_그대로_넘긴다() throws Exception {
        when(todoConversionService.convertToRoutine(eq(1L), eq(10L), any(TodoConvertRequest.class)))
                .thenReturn(new RoutineResponse(77L, "헬스장 가기", 3L, AuthType.CHECK, RoutineStatus.ACTIVE,
                        "WEEKLY", new RepeatDays(List.of("MON", "WED")), LocalTime.of(7, 0),
                        LocalDate.of(2026, 11, 18), null, 77L, null, null, null));

        mockMvc.perform(post("/api/v1/todos/10/convert-to-routine")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WEEKLY_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(77))
                .andExpect(jsonPath("$.repeatType").value("WEEKLY"))
                .andExpect(jsonPath("$.repeatDays.daysOfWeek[1]").value("WED"));

        verify(todoConversionService).convertToRoutine(eq(1L), eq(10L), argThat(r ->
                r.title().equals("헬스장 가기") && r.categoryId() == 3L && r.authType() == AuthType.CHECK
                        && r.repeatDays().daysOfWeek().equals(List.of("MON", "WED"))
                        && r.startsOn().equals(LocalDate.of(2026, 11, 18))));
    }

    @Test
    void 반복_유형이_허용값이_아니면_400이고_서비스를_부르지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/todos/10/convert-to-routine")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"독서\",\"authType\":\"CHECK\",\"repeatType\":\"HOURLY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(todoConversionService);
    }

    @Test
    void 제목이_비면_400이다() throws Exception {
        mockMvc.perform(post("/api/v1/todos/10/convert-to-routine")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\" \",\"authType\":\"CHECK\",\"repeatType\":\"DAILY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(todoConversionService);
    }

    @Test
    void 완료한_투두면_409와_TODO_COMPLETED_NOT_CONVERTIBLE을_응답한다() throws Exception {
        when(todoConversionService.convertToRoutine(eq(1L), eq(10L), any(TodoConvertRequest.class)))
                .thenThrow(new BusinessException(TodoErrorCode.TODO_COMPLETED_NOT_CONVERTIBLE));

        mockMvc.perform(post("/api/v1/todos/10/convert-to-routine")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WEEKLY_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TODO_COMPLETED_NOT_CONVERTIBLE"));
    }
}
