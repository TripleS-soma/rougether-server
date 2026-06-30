package com.triples.rougether.userapi.category.controller;

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
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.userapi.category.dto.CategoryCreateRequest;
import com.triples.rougether.userapi.category.dto.CategoryListResponse;
import com.triples.rougether.userapi.category.dto.CategoryResponse;
import com.triples.rougether.userapi.category.dto.CategoryUpdateRequest;
import com.triples.rougether.userapi.category.error.CategoryErrorCode;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.category.service.CategoryService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.global.security.MemberRole;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CategoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class CategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CategoryService categoryService;
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
        when(categoryService.list(1L)).thenReturn(new CategoryListResponse(List.of(
                new CategoryResponse(10L, "운동", "#FFAA00", "icon/run", 0, PrivacyScope.PRIVATE))));

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(10))
                .andExpect(jsonPath("$.items[0].name").value("운동"))
                .andExpect(jsonPath("$.items[0].colorHex").value("#FFAA00"))
                .andExpect(jsonPath("$.items[0].iconKey").value("icon/run"))
                .andExpect(jsonPath("$.items[0].sortOrder").value(0))
                .andExpect(jsonPath("$.items[0].visibility").value("PRIVATE"));
    }

    @Test
    void 등록은_201과_생성된_카테고리를_응답한다() throws Exception {
        when(categoryService.create(eq(1L), any(CategoryCreateRequest.class)))
                .thenReturn(new CategoryResponse(5L, "공부", null, null, 2, PrivacyScope.HOUSE));

        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"공부\",\"visibility\":\"HOUSE\",\"sortOrder\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.name").value("공부"))
                .andExpect(jsonPath("$.visibility").value("HOUSE"));
    }

    @Test
    void name이_비면_400과_VALIDATION_FAILED를_응답한다() throws Exception {
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
    }

    @Test
    void 수정은_200과_수정된_카테고리를_응답한다() throws Exception {
        when(categoryService.update(eq(1L), eq(7L), any(CategoryUpdateRequest.class)))
                .thenReturn(new CategoryResponse(7L, "변경됨", null, null, 0, PrivacyScope.PRIVATE));

        mockMvc.perform(put("/api/v1/categories/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"변경됨\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("변경됨"));
    }

    @Test
    void 없는_카테고리_수정은_404와_CATEGORY_NOT_FOUND를_응답한다() throws Exception {
        when(categoryService.update(eq(1L), eq(99L), any(CategoryUpdateRequest.class)))
                .thenThrow(new BusinessException(CategoryErrorCode.CATEGORY_NOT_FOUND));

        mockMvc.perform(put("/api/v1/categories/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    void 삭제는_204이고_서비스를_호출한다() throws Exception {
        mockMvc.perform(delete("/api/v1/categories/7"))
                .andExpect(status().isNoContent());

        verify(categoryService).delete(1L, 7L);
    }
}
