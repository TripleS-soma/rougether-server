package com.triples.rougether.adminapi.characterpose;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.adminapi.asset.service.AssetStorageService;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.repository.CharacterPoseRepository;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CharacterPoseAdminTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    CharacterRepository characterRepository;

    @Autowired
    CharacterPoseRepository poseRepository;

    @MockitoBean
    AssetStorageService storage;

    @Test
    @WithMockUser(roles = "ADMIN")
    void S3_캐릭터_에셋을_포즈로_등록하고_해제한다() throws Exception {
        characterRepository.save(new Character(
                "pose_admin_cat", "포즈 테스트", "characters/pose-admin-base.webp", 998, true));
        String key = "characters/pose-admin-temp1.webp";
        given(storage.exists(key)).willReturn(true);

        mockMvc.perform(post("/admin/character-poses")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "characterCode": "pose_admin_cat",
                                  "code": "temp1",
                                  "assetKey": "characters/pose-admin-temp1.webp",
                                  "sortOrder": 10,
                                  "active": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.characterCode").value("pose_admin_cat"))
                .andExpect(jsonPath("$.code").value("temp1"))
                .andExpect(jsonPath("$.assetKey").value(key))
                .andExpect(jsonPath("$.active").value(true));

        var pose = poseRepository.findAllWithCharacter().stream()
                .filter(candidate -> candidate.getAssetKey().equals(key))
                .findFirst()
                .orElseThrow();

        mockMvc.perform(delete("/admin/character-poses/{poseId}", pose.getId()).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void S3에_없는_파일은_포즈로_등록하지_않는다() throws Exception {
        characterRepository.save(new Character(
                "pose_missing_cat", "없는 포즈 테스트", "characters/pose-missing-base.webp", 997, true));

        mockMvc.perform(post("/admin/character-poses")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "characterCode": "pose_missing_cat",
                                  "code": "temp1",
                                  "assetKey": "characters/missing.webp",
                                  "sortOrder": 10,
                                  "active": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHARACTER_POSE_INVALID"));
    }
}
