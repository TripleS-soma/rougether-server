package com.triples.rougether.userapi.furniture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.*;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.furniture.dto.FurnitureGenerationResponse;
import com.triples.rougether.userapi.furniture.service.FurnitureGenerationService;
import com.triples.rougether.userapi.furniture.web.FurnitureGenerationController;
import com.triples.rougether.userapi.global.security.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FurnitureGenerationController.class)
@AutoConfigureMockMvc(addFilters = false)
class FurnitureGenerationControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean FurnitureGenerationService service;
    @MockitoBean TokenService tokenService;
    @MockitoBean CurrentUserArgumentResolver currentUserArgumentResolver;
    private final String id = UUID.randomUUID().toString();

    @BeforeEach void setup() throws Exception {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any())).thenReturn(new AuthUser(7L, null));
        var time = Instant.parse("2026-09-06T00:00:00Z");
        when(service.submit(eq(7L), any(), anyString(), any())).thenReturn(new FurnitureGenerationResponse(id,
                Status.QUEUED, Action.GENERATE, null, null, 0, 0, null, null, null,
                time.plusSeconds(86400), time, time));
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
        verifyNoInteractions(service);
    }

    @Test void 피드백은_멱등ID와_내용이_필수() throws Exception {
        mvc.perform(post("/api/v1/me/furniture-generations/" + id + "/feedback")
                .contentType(MediaType.APPLICATION_JSON).content("{\"feedback\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
