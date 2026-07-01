package com.triples.rougether.userapi.todo.controller;

import static org.mockito.ArgumentMatchers.any;
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
                new TodoResponse(10L, "장보기", "우유", 3L, LocalDate.of(2026, 7, 1),
                        TodoStatus.PENDING, null))));

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
                .thenReturn(new TodoResponse(5L, "장보기", null, null, null,
                        TodoStatus.PENDING, null));

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
                        Instant.parse("2026-06-30T07:00:00Z"), CurrencyType.COIN, 5));

        mockMvc.perform(post("/api/v1/todos/7/complete"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.rewardAmount").value(5));
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
                .thenReturn(new TodoResponse(7L, "장보기", null, null, null,
                        TodoStatus.PENDING, null));

        mockMvc.perform(delete("/api/v1/todos/7/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void 당일이_아닌_완료_취소는_409와_TODO_NOT_CANCELABLE을_응답한다() throws Exception {
        when(todoService.cancelComplete(1L, 7L))
                .thenThrow(new BusinessException(TodoErrorCode.TODO_NOT_CANCELABLE));

        mockMvc.perform(delete("/api/v1/todos/7/complete"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TODO_NOT_CANCELABLE"));
    }

    @Test
    void 미완료_투두_취소는_409와_TODO_NOT_COMPLETED를_응답한다() throws Exception {
        when(todoService.cancelComplete(1L, 7L))
                .thenThrow(new BusinessException(TodoErrorCode.TODO_NOT_COMPLETED));

        mockMvc.perform(delete("/api/v1/todos/7/complete"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TODO_NOT_COMPLETED"));
    }
}
