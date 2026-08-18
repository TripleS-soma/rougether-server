package com.triples.rougether.userapi.todo.web;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.todo.dto.TodoCompleteResponse;
import com.triples.rougether.userapi.todo.dto.TodoCreateRequest;
import com.triples.rougether.userapi.todo.dto.TodoListResponse;
import com.triples.rougether.userapi.todo.dto.TodoResponse;
import com.triples.rougether.userapi.todo.error.TodoErrorCode;
import com.triples.rougether.userapi.todo.service.TodoService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TodoController.class)
@AutoConfigureMockMvc(addFilters = false)
class TodoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TodoService todoService;
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
        when(todoService.list(1L, null, null, null)).thenReturn(new TodoListResponse(List.of(
                new TodoResponse(10L, "장보기", "우유", 3L, LocalDate.of(2026, 7, 1), null,
                        TodoStatus.PENDING, null, null, null))));

        mockMvc.perform(get("/api/v1/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(10))
                .andExpect(jsonPath("$.items[0].title").value("장보기"))
                .andExpect(jsonPath("$.items[0].categoryId").value(3))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"));
    }

    @Test
    void 등록은_201과_생성된_투두를_응답한다() throws Exception {
        when(todoService.create(eq(1L), any(TodoCreateRequest.class)))
                .thenReturn(new TodoResponse(5L, "장보기", null, null, null, null,
                        TodoStatus.PENDING, null, null, null));

        mockMvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"장보기\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.title").value("장보기"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void title이_비면_400과_VALIDATION_FAILED를_응답한다() throws Exception {
        mockMvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("title"));
    }

    @Test
    void dueTime이_5분_단위가_아니면_400과_VALIDATION_FAILED를_응답한다() throws Exception {
        mockMvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"장보기\",\"dueDate\":\"2026-07-01\",\"dueTime\":\"18:03:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("dueTime"));
    }

    @Test
    void 없는_투두_조회는_404와_TODO_NOT_FOUND를_응답한다() throws Exception {
        when(todoService.get(eq(1L), eq(99L)))
                .thenThrow(new BusinessException(TodoErrorCode.TODO_NOT_FOUND));

        mockMvc.perform(get("/api/v1/todos/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TODO_NOT_FOUND"));
    }

    @Test
    void 삭제는_204이고_서비스를_호출한다() throws Exception {
        mockMvc.perform(delete("/api/v1/todos/7"))
                .andExpect(status().isNoContent());

        verify(todoService).delete(1L, 7L);
    }

    @Test
    void 완료는_201과_보상을_포함해_응답한다() throws Exception {
        when(todoService.complete(1L, 7L))
                .thenReturn(new TodoCompleteResponse(7L, TodoStatus.COMPLETED,
                        Instant.parse("2026-06-30T07:00:00Z"), CurrencyType.COIN, 10));

        mockMvc.perform(post("/api/v1/todos/7/complete"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.rewardAmount").value(10));
    }

    @Test
    void 재완료는_409와_TODO_ALREADY_COMPLETED를_응답한다() throws Exception {
        when(todoService.complete(1L, 7L))
                .thenThrow(new BusinessException(TodoErrorCode.TODO_ALREADY_COMPLETED));

        mockMvc.perform(post("/api/v1/todos/7/complete"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TODO_ALREADY_COMPLETED"));
    }

    @Test
    void 완료_취소는_200과_되돌린_투두를_응답한다() throws Exception {
        when(todoService.cancelComplete(1L, 7L))
                .thenReturn(new TodoResponse(7L, "장보기", null, null, null, null,
                        TodoStatus.PENDING, null, null, null));

        mockMvc.perform(delete("/api/v1/todos/7/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void 미완료_투두_취소는_409와_TODO_NOT_COMPLETED를_응답한다() throws Exception {
        when(todoService.cancelComplete(1L, 7L))
                .thenThrow(new BusinessException(TodoErrorCode.TODO_NOT_COMPLETED));

        mockMvc.perform(delete("/api/v1/todos/7/complete"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TODO_NOT_COMPLETED"));
    }

    // --- 기기 캘린더 임포트 외부 참조 (#299) ---

    @Test
    void 등록_요청의_externalSource_externalId가_서비스에_전달되고_응답에_노출된다() throws Exception {
        when(todoService.create(eq(1L), argThat(req -> req != null
                && "GOOGLE_CALENDAR".equals(req.externalSource()) && "evt-1".equals(req.externalId()))))
                .thenReturn(new TodoResponse(5L, "팀 회의", null, null, LocalDate.of(2026, 9, 1), null,
                        TodoStatus.PENDING, null, "GOOGLE_CALENDAR", "evt-1"));

        mockMvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"팀 회의\",\"dueDate\":\"2026-09-01\","
                                + "\"externalSource\":\"GOOGLE_CALENDAR\",\"externalId\":\"evt-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.externalSource").value("GOOGLE_CALENDAR"))
                .andExpect(jsonPath("$.externalId").value("evt-1"));
    }

    @Test
    void externalSource가_대문자_스네이크가_아니면_400과_VALIDATION_FAILED를_응답한다() throws Exception {
        mockMvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"팀 회의\",\"externalSource\":\"google-calendar\",\"externalId\":\"evt-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("externalSource"));
    }

    @Test
    void externalId가_공백뿐이면_400과_VALIDATION_FAILED를_응답한다() throws Exception {
        mockMvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"팀 회의\",\"externalSource\":\"GOOGLE_CALENDAR\",\"externalId\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("externalId"));
    }

    @Test
    void externalSource는_30자까지_허용하고_31자면_400이다() throws Exception {
        when(todoService.create(eq(1L), any(TodoCreateRequest.class)))
                .thenReturn(new TodoResponse(5L, "팀 회의", null, null, null, null,
                        TodoStatus.PENDING, null, "A".repeat(30), "evt-1"));

        mockMvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"팀 회의\",\"externalSource\":\"" + "A".repeat(30) + "\",\"externalId\":\"evt-1\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"팀 회의\",\"externalSource\":\"" + "A".repeat(31) + "\",\"externalId\":\"evt-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("externalSource"));
    }

    @Test
    void externalId는_255자까지_허용하고_256자면_400이다() throws Exception {
        when(todoService.create(eq(1L), any(TodoCreateRequest.class)))
                .thenReturn(new TodoResponse(5L, "팀 회의", null, null, null, null,
                        TodoStatus.PENDING, null, "GOOGLE_CALENDAR", "e".repeat(255)));

        mockMvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"팀 회의\",\"externalSource\":\"GOOGLE_CALENDAR\",\"externalId\":\"" + "e".repeat(255) + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"팀 회의\",\"externalSource\":\"GOOGLE_CALENDAR\",\"externalId\":\"" + "e".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("externalId"));
    }

    @Test
    void 이미_가져온_일정이면_409와_TODO_EXTERNAL_DUPLICATE를_응답한다() throws Exception {
        when(todoService.create(eq(1L), any(TodoCreateRequest.class)))
                .thenThrow(new BusinessException(TodoErrorCode.TODO_EXTERNAL_DUPLICATE));

        mockMvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"팀 회의\",\"externalSource\":\"GOOGLE_CALENDAR\",\"externalId\":\"evt-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TODO_EXTERNAL_DUPLICATE"));
    }

    @Test
    void 외부_참조를_한쪽만_보내면_400과_TODO_EXTERNAL_REF_INCOMPLETE를_응답한다() throws Exception {
        when(todoService.create(eq(1L), any(TodoCreateRequest.class)))
                .thenThrow(new BusinessException(TodoErrorCode.TODO_EXTERNAL_REF_INCOMPLETE));

        mockMvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"팀 회의\",\"externalSource\":\"GOOGLE_CALENDAR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TODO_EXTERNAL_REF_INCOMPLETE"));
    }

    @Test
    void 일반_투두_응답의_externalSource_externalId는_null이다() throws Exception {
        when(todoService.get(eq(1L), eq(5L)))
                .thenReturn(new TodoResponse(5L, "장보기", null, null, null, null,
                        TodoStatus.PENDING, null, null, null));

        mockMvc.perform(get("/api/v1/todos/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalSource").value(nullValue()))
                .andExpect(jsonPath("$.externalId").value(nullValue()));
    }
}
