package com.triples.rougether.userapi.furniture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.*;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.furniture.dto.FurnitureGenerationResponse;
import com.triples.rougether.furniture.error.FurnitureGenerationErrorCode;
import com.triples.rougether.userapi.furniture.service.FurnitureGenerationService;
import com.triples.rougether.userapi.furniture.service.FurnitureGenerationQueryService;
import com.triples.rougether.userapi.furniture.web.FurnitureGenerationController;
import com.triples.rougether.userapi.global.security.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FurnitureGenerationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(CurrentUserArgumentResolver.class)
class FurnitureGenerationControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean FurnitureGenerationService service;
    @MockitoBean FurnitureGenerationQueryService queries;
    @MockitoBean TokenService tokenService;
    private final String id = "00000000-0000-4000-8000-000000000001";
    private static final String SUCCEEDED_JSON = """
            {
              "id": "00000000-0000-4000-8000-000000000001",
              "status": "SUCCEEDED",
              "action": "REVIEW",
              "assetKey": "items/photo-furniture/chair.png",
              "userItemId": 31,
              "imageAttempts": 2,
              "reviewAttempts": 3,
              "decision": "ACCEPT",
              "reason": "형태와 색상 일치",
              "failureCode": null,
              "expiresAt": "2026-09-07T00:00:00Z",
              "createdAt": "2026-09-06T00:00:00Z",
              "updatedAt": "2026-09-06T00:01:00Z"
            }
            """;
    private static final String QUEUED_JSON = """
            {
              "id": "00000000-0000-4000-8000-000000000002",
              "status": "QUEUED",
              "action": "EXTRACT",
              "assetKey": null,
              "userItemId": null,
              "imageAttempts": 0,
              "reviewAttempts": 0,
              "decision": null,
              "reason": null,
              "failureCode": null,
              "expiresAt": "2026-09-06T00:00:00Z",
              "createdAt": "2026-09-05T00:00:00Z",
              "updatedAt": "2026-09-05T00:00:00Z"
            }
            """;

    @BeforeEach void setup() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthUser(7L, null), null, List.of()));
        var time = Instant.parse("2026-09-06T00:00:00Z");
        when(service.submit(eq(7L), any(), anyString(), any())).thenReturn(new FurnitureGenerationResponse(id,
                Status.QUEUED, Action.GENERATE, null, null, 0, 0, null, null, null,
                time.plusSeconds(86400), time, time));
    }

    @AfterEach void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test void 접수는_202와_조회위치를_반환하고_원본key는_노출하지_않음() throws Exception {
        mvc.perform(multipart("/api/v1/me/furniture-generations")
                .file(new MockMultipartFile("photo", "chair.png", "image/png", new byte[]{1}))
                .param("requestId", UUID.randomUUID().toString()).param("targetHint", "앞 의자"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/me/furniture-generations/" + id))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.sourceKey").doesNotExist())
                .andExpect(jsonPath("$.candidateKey").doesNotExist());
    }

    @Test void 요청ID와_사진_누락_또는_길이_초과는_400() throws Exception {
        mvc.perform(multipart("/api/v1/me/furniture-generations").param("requestId", "bad"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/me/furniture-generations")
                .file(new MockMultipartFile("photo", new byte[]{1}))
                .param("requestId", UUID.randomUUID().toString()).param("targetHint", "가".repeat(121)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, queries);
    }

    @Test void 피드백은_멱등ID와_내용이_필수() throws Exception {
        mvc.perform(post("/api/v1/me/furniture-generations/" + id + "/feedback")
                .contentType(MediaType.APPLICATION_JSON).content("{\"feedback\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, queries);
    }

    @Test void 상세는_공개_13필드와_시간형식을_유지하고_로그인_사용자로_조회함() throws Exception {
        when(queries.get(7L, id)).thenReturn(succeeded());

        mvc.perform(get("/api/v1/me/furniture-generations/{id}", id).param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json(SUCCEEDED_JSON, JsonCompareMode.STRICT));

        verify(queries).get(7L, id);
    }

    @Test void 상세는_아직_없는_결과와_검수정보도_null_필드로_반환함() throws Exception {
        var response = queued();
        when(queries.get(7L, response.id())).thenReturn(response);

        mvc.perform(get("/api/v1/me/furniture-generations/{id}", response.id()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json(QUEUED_JSON, JsonCompareMode.STRICT));

        verify(queries).get(7L, response.id());
    }

    @Test void 목록은_items_배열에_조회순서대로_담고_로그인_사용자로_조회함() throws Exception {
        when(queries.list(7L)).thenReturn(List.of(succeeded(), queued()));

        mvc.perform(get("/api/v1/me/furniture-generations").param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"items\":[" + SUCCEEDED_JSON + "," + QUEUED_JSON + "]}",
                        JsonCompareMode.STRICT));

        verify(queries).list(7L);
    }

    @Test void 작업이_없으면_items는_빈_배열임() throws Exception {
        when(queries.list(7L)).thenReturn(List.of());

        mvc.perform(get("/api/v1/me/furniture-generations"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"items\":[]}", JsonCompareMode.STRICT));

        verify(queries).list(7L);
    }

    @Test void 상세_ID가_UUID_형식이_아니면_조회하지_않고_400을_반환함() throws Exception {
        mvc.perform(get("/api/v1/me/furniture-generations/not-a-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service, queries);
    }

    @Test void 조회할_수_없는_작업은_404와_오류코드를_반환함() throws Exception {
        when(queries.get(7L, id)).thenThrow(new BusinessException(
                FurnitureGenerationErrorCode.FURNITURE_JOB_NOT_FOUND));

        mvc.perform(get("/api/v1/me/furniture-generations/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FURNITURE_JOB_NOT_FOUND"));
    }

    @Test void 인증_주체가_없으면_상세와_목록을_조회하지_않음() throws Exception {
        SecurityContextHolder.clearContext();

        mvc.perform(get("/api/v1/me/furniture-generations/{id}", id))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
        mvc.perform(get("/api/v1/me/furniture-generations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));

        verifyNoInteractions(service, queries);
    }

    private FurnitureGenerationResponse succeeded() {
        var time = Instant.parse("2026-09-06T00:00:00Z");
        return new FurnitureGenerationResponse(id, Status.SUCCEEDED, Action.REVIEW,
                "items/photo-furniture/chair.png", 31L, 2, 3, "ACCEPT", "형태와 색상 일치", null,
                time.plusSeconds(86400), time, time.plusSeconds(60));
    }

    private FurnitureGenerationResponse queued() {
        var time = Instant.parse("2026-09-05T00:00:00Z");
        return new FurnitureGenerationResponse("00000000-0000-4000-8000-000000000002",
                Status.QUEUED, Action.EXTRACT, null, null, 0, 0, null, null, null,
                time.plusSeconds(86400), time, time);
    }
}
