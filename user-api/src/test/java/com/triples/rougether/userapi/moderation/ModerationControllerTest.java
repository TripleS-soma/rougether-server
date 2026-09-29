package com.triples.rougether.userapi.moderation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.moderation.entity.ContentReportReason;
import com.triples.rougether.domain.moderation.entity.ContentReportStatus;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.moderation.dto.BlockedUserPageResponse;
import com.triples.rougether.userapi.moderation.dto.ContentReportRequest;
import com.triples.rougether.userapi.moderation.dto.ContentReportResponse;
import com.triples.rougether.userapi.moderation.error.ModerationErrorCode;
import com.triples.rougether.userapi.moderation.service.ContentReportService;
import com.triples.rougether.userapi.moderation.service.UserBlockService;
import com.triples.rougether.userapi.moderation.web.ContentReportController;
import com.triples.rougether.userapi.moderation.web.UserBlockController;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 신고·차단(#399) 요청/응답 계약과 입력 검증.
@WebMvcTest({ContentReportController.class, UserBlockController.class})
@AutoConfigureMockMvc(addFilters = false)
class ModerationControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ContentReportService reports;
    @MockitoBean private UserBlockService blocks;
    @MockitoBean private CurrentUserArgumentResolver currentUserArgumentResolver;
    @MockitoBean private TokenService tokenService;

    @BeforeEach
    void authenticate() {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(7L, null));
    }

    @Test
    void 세_종류_대상_신고는_201과_신고_ID_상태를_돌려준다() throws Exception {
        ContentReportResponse received = new ContentReportResponse(12L, ContentReportStatus.RECEIVED);
        ContentReportRequest request = new ContentReportRequest(ContentReportReason.ABUSE, "욕설");
        when(reports.reportFeedPost(7L, 3L, request)).thenReturn(received);
        when(reports.reportFeedComment(7L, 3L, 5L, request)).thenReturn(received);
        when(reports.reportMarketAsset(7L, 9L, request)).thenReturn(received);
        String body = "{\"reason\":\"ABUSE\",\"detail\":\"욕설\"}";

        for (String path : List.of("/api/v1/feed/posts/3/reports", "/api/v1/feed/posts/3/comments/5/reports",
                "/api/v1/market/assets/9/reports")) {
            mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.reportId").value(12))
                    .andExpect(jsonPath("$.status").value("RECEIVED"));
        }
    }

    @Test
    void 사유_누락_허용값_밖_설명_500자_초과는_400이다() throws Exception {
        String path = "/api/v1/feed/posts/3/reports";
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"BORING\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"OTHER\",\"detail\":\"" + "가".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verify(reports, never()).reportFeedPost(anyLong(), anyLong(), any());
    }

    @Test
    void 내_콘텐츠_신고는_REPORT_SELF_TARGET이다() throws Exception {
        when(reports.reportFeedPost(eq(7L), eq(3L), any()))
                .thenThrow(new BusinessException(ModerationErrorCode.REPORT_SELF_TARGET));
        mockMvc.perform(post("/api/v1/feed/posts/3/reports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"SPAM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_SELF_TARGET"));
    }

    @Test
    void 차단은_204이고_자기_차단은_BLOCK_SELF다() throws Exception {
        mockMvc.perform(put("/api/v1/users/8/block")).andExpect(status().isNoContent());
        verify(blocks).block(7L, 8L);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/users/8/block"))
                .andExpect(status().isNoContent());
        verify(blocks).unblock(7L, 8L);

        doThrow(new BusinessException(ModerationErrorCode.BLOCK_SELF)).when(blocks).block(7L, 7L);
        mockMvc.perform(put("/api/v1/users/7/block"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BLOCK_SELF"));
    }

    @Test
    void 차단_목록은_크기_범위를_검증한다() throws Exception {
        when(blocks.list(7L, null, 20)).thenReturn(new BlockedUserPageResponse(List.of(), null, false));
        mockMvc.perform(get("/api/v1/me/blocks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));
        mockMvc.perform(get("/api/v1/me/blocks").param("size", "51")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/me/blocks").param("cursor", "0")).andExpect(status().isBadRequest());
        verify(blocks, never()).list(anyLong(), anyLong(), anyInt());
    }
}
