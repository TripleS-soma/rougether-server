package com.triples.rougether.adminapi.assetfoundry;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.assetpipeline.repository.AssetPipelineJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AssetFoundryAdminTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AssetPipelineJobRepository jobRepository;

    @BeforeEach
    void cleanUp() {
        jobRepository.deleteAll();
    }

    @Test
    @WithMockUser(username = "operator", roles = "ADMIN")
    void 작업_생성부터_QA_검증까지_순서대로_진행한다() throws Exception {
        String created = mockMvc.perform(post("/admin/asset-foundry/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJobJson("planet-mobile-v2"))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("planet-mobile-v2"))
                .andExpect(jsonPath("$.kind").value("ANIMATED_FURNITURE"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.revision").value(0))
                .andReturn().getResponse().getContentAsString();

        long jobId = JsonTestSupport.longValue(created, "id");

        mockMvc.perform(put("/admin/asset-foundry/jobs/{jobId}/status", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision": 0,
                                  "targetStatus": "BUILT",
                                  "note": "24프레임 WebP 빌드 완료"
                                }
                                """)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BUILT"))
                .andExpect(jsonPath("$.revision").value(1));

        mockMvc.perform(put("/admin/asset-foundry/jobs/{jobId}/qa", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision": 1,
                                  "checks": [
                                    {
                                      "code": "FRAME_COUNT",
                                      "label": "프레임 수",
                                      "result": "PASS",
                                      "score": 100,
                                      "required": true,
                                      "message": "24 / 24"
                                    },
                                    {
                                      "code": "RN_86PX_MOTION",
                                      "label": "86px 움직임 가시성",
                                      "result": "PASS",
                                      "score": 92,
                                      "required": true,
                                      "message": "visible delta 4.8%"
                                    }
                                  ]
                                }
                                """)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qaPassed").value(true))
                .andExpect(jsonPath("$.qualityScore").value(96))
                .andExpect(jsonPath("$.checks.length()").value(2))
                .andExpect(jsonPath("$.revision").value(2));

        mockMvc.perform(put("/admin/asset-foundry/jobs/{jobId}/status", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision": 2,
                                  "targetStatus": "VALIDATED",
                                  "note": "필수 QA 통과"
                                }
                                """)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.revision").value(3));

        mockMvc.perform(get("/admin/asset-foundry/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.counts.VALIDATED").value(1));
    }

    @Test
    @WithMockUser(username = "operator", roles = "ADMIN")
    void 필수_QA_없이_VALIDATED로_건너뛸_수_없다() throws Exception {
        String created = mockMvc.perform(post("/admin/asset-foundry/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJobJson("rainy-window-v2"))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long jobId = JsonTestSupport.longValue(created, "id");

        mockMvc.perform(put("/admin/asset-foundry/jobs/{jobId}/status", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision": 0,
                                  "targetStatus": "VALIDATED",
                                  "note": "단계 건너뛰기"
                                }
                                """)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSET_PIPELINE_TRANSITION_INVALID"));
    }

    @Test
    @WithMockUser(username = "operator", roles = "ADMIN")
    void 동일한_QA_check_code를_새_리포트로_교체할_수_있다() throws Exception {
        String created = mockMvc.perform(post("/admin/asset-foundry/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJobJson("qa-reimport"))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long jobId = JsonTestSupport.longValue(created, "id");

        mockMvc.perform(put("/admin/asset-foundry/jobs/{jobId}/status", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision": 0,
                                  "targetStatus": "BUILT",
                                  "note": "빌드 완료"
                                }
                                """)
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(put("/admin/asset-foundry/jobs/{jobId}/qa", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qaJson(1, 80, "첫 결과"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qualityScore").value(80));

        mockMvc.perform(put("/admin/asset-foundry/jobs/{jobId}/qa", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qaJson(2, 96, "재측정 결과"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qualityScore").value(96))
                .andExpect(jsonPath("$.checks.length()").value(1))
                .andExpect(jsonPath("$.checks[0].message").value("재측정 결과"));
    }

    @Test
    @WithMockUser(username = "operator", roles = "ADMIN")
    void 오래된_revision으로_단계를_덮어쓸_수_없다() throws Exception {
        String created = mockMvc.perform(post("/admin/asset-foundry/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJobJson("revision-conflict"))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long jobId = JsonTestSupport.longValue(created, "id");

        String transition = """
                {
                  "expectedRevision": 0,
                  "targetStatus": "BUILT",
                  "note": "빌드 완료"
                }
                """;
        mockMvc.perform(put("/admin/asset-foundry/jobs/{jobId}/status", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transition)
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(put("/admin/asset-foundry/jobs/{jobId}/status", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transition)
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSET_PIPELINE_REVISION_CONFLICT"));
    }

    @Test
    @WithMockUser(username = "operator", roles = "ADMIN")
    void 상태_변경은_CSRF를_요구한다() throws Exception {
        mockMvc.perform(post("/admin/asset-foundry/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJobJson("csrf-check")))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "operator", roles = "ADMIN")
    void Asset_Foundry_페이지가_렌더링된다() throws Exception {
        mockMvc.perform(get("/asset-foundry"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Rougether Asset Foundry")))
                .andExpect(content().string(containsString("디지털 트윈")))
                .andExpect(content().string(containsString("품질 게이트")));
    }

    private static String createJobJson(String slug) {
        return """
                {
                  "name": "행성 모빌 테이블 v2",
                  "slug": "%s",
                  "kind": "ANIMATED_FURNITURE",
                  "themeCode": "cozy-space",
                  "sourceAssetKey": "items/cozy-space/furniture/cozy-space-planet-mobile-table.png",
                  "outputAssetKey": "items/cozy-space/furniture/cozy-space-planet-mobile-table-animated-v2.webp",
                  "previewAssetKey": "items/cozy-space/furniture/cozy-space-planet-mobile-table-animated-v2.webp",
                  "targetItemId": 47,
                  "expectedOldAssetKey": "items/cozy-space/furniture/cozy-space-planet-mobile-table-animated-v1.webp"
                }
                """.formatted(slug);
    }

    private static String qaJson(long revision, int score, String message) {
        return """
                {
                  "expectedRevision": %d,
                  "checks": [
                    {
                      "code": "CANVAS",
                      "label": "캔버스 규격",
                      "result": "PASS",
                      "score": %d,
                      "required": true,
                      "message": "%s"
                    }
                  ]
                }
                """.formatted(revision, score, message);
    }
}
