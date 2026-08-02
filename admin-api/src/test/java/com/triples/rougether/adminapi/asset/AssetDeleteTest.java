package com.triples.rougether.adminapi.asset;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.adminapi.asset.service.AssetDeleteResult;
import com.triples.rougether.adminapi.asset.service.AssetStorageService;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.CharacterPose;
import com.triples.rougether.domain.character.repository.CharacterPoseRepository;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AssetDeleteTest {

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
    void 미등록_캐릭터_에셋은_보관후_삭제한다() throws Exception {
        String key = "characters/unregistered-delete-test.webp";
        String archiveKey = "archive/admin-deleted/20260802T000000000Z/" + key;
        given(storage.exists(key)).willReturn(true);
        given(storage.archiveAndDelete(key)).willReturn(new AssetDeleteResult(key, archiveKey));

        mockMvc.perform(delete("/admin/assets").param("key", key).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(key))
                .andExpect(jsonPath("$.archiveKey").value(archiveKey));

        verify(storage).archiveAndDelete(key);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void DB에_등록된_포즈_에셋은_삭제하지_않는다() throws Exception {
        String key = "characters/registered-delete-test.webp";
        Character character = characterRepository.save(new Character(
                "delete_test_cat", "삭제 테스트", "characters/delete-test-base.webp", 999, true));
        poseRepository.save(new CharacterPose(character, "test", key, 10, true));

        mockMvc.perform(delete("/admin/assets").param("key", key).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void characters_밖의_key는_삭제하지_않는다() throws Exception {
        mockMvc.perform(delete("/admin/assets")
                        .param("key", "items/chair.png")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }
}
