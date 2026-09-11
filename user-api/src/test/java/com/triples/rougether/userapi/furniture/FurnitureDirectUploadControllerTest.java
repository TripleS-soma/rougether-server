package com.triples.rougether.userapi.furniture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.*;
import com.triples.rougether.furniture.dto.FurnitureGenerationResponse;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.furniture.service.FurnitureDirectUploadService;
import com.triples.rougether.userapi.furniture.web.FurnitureDirectUploadController;
import com.triples.rougether.userapi.global.security.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = FurnitureDirectUploadController.class, properties = "furniture.upload.enabled=true")
@AutoConfigureMockMvc(addFilters = false)
@Import(CurrentUserArgumentResolver.class)
class FurnitureDirectUploadControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean FurnitureDirectUploadService service;
    @MockitoBean TokenService tokenService;
    final String id = UUID.randomUUID().toString();
    final String request = """
            {"requestId":"%s","sha256":"%s","bytes":123,"contentType":"image/jpeg","targetHint":"앞 의자"}
            """.formatted(UUID.randomUUID(), "a".repeat(64));

    @BeforeEach void setup() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthUser(7L, null), null, List.of()));
        Instant time = Instant.parse("2026-09-11T00:00:00Z");
        var job = new FurnitureGenerationResponse(id, Status.UPLOADING, Action.EXTRACT, null, null, 0, 0,
                null, null, null, time.plusSeconds(86400), time, time);
        when(service.start(eq(7L), any(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(new FurnitureDirectUploadService.Response(job, "https://test-bucket.s3.amazonaws.com/signed",
                        Map.of("if-none-match", List.of("*")), time.plusSeconds(300)));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void 인증된_사용자의_메타데이터로_예약하고_서명URL은_캐시하지_않음() throws Exception {
        mvc.perform(post("/api/v1/me/furniture-generations/uploads").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Location", "/api/v1/me/furniture-generations/" + id))
                .andExpect(jsonPath("$.job.status").value("UPLOADING"))
                .andExpect(jsonPath("$.headers.if-none-match[0]").value("*"));
        verify(service).start(eq(7L), any(), eq("a".repeat(64)), eq(123L), eq("image/jpeg"), eq("앞 의자"));
    }
    @Test void 잘못된_크기_형식_해시는_예약하지_않음() throws Exception {
        for (var body : List.of(request.replace("123", "10485761"), request.replace("123", "0"),
                request.replace("image/jpeg", "image/gif"), request.replace("a".repeat(64), "abc"))) {
            mvc.perform(post("/api/v1/me/furniture-generations/uploads").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void 인증_주체가_없으면_예약하지_않음() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(post("/api/v1/me/furniture-generations/uploads").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
