package com.triples.rougether.adminapi.asset;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @CsvSource({
            "characters/anim_guard_cat/animations/idle.webp, true",
            "characters/anim_guard_cat/animations/idle.webp, false",
            "characters/anim_guard_cat/animations/pose-cycle.webp, false",
            "characters/anim_guard_cat/animations/wave.webp, false"
    })
    @WithMockUser(roles = "ADMIN")
    void 등록된_캐릭터의_애니메이션은_활성_여부와_관계없이_삭제하지_않는다(String key, boolean active)
            throws Exception {
        characterRepository.save(new Character(
                "anim_guard_cat", "애니메이션 가드", "characters/anim-guard-base.webp", 996, active));

        mockMvc.perform(delete("/admin/assets").param("key", key).with(csrf()))
                .andExpect(status().isConflict());

        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "characters/no_such_cat/animations/idle.webp",
            "characters/no_such_cat/animations/pose-cycle.webp",
            "characters/no_such_cat/animations/wave.webp"
    })
    @WithMockUser(roles = "ADMIN")
    void 미등록_캐릭터_code의_애니메이션_key는_삭제할_수_있다(String key) throws Exception {
        String archiveKey = "archive/admin-deleted/20260809T000000000Z/" + key;
        given(storage.exists(key)).willReturn(true);
        given(storage.archiveAndDelete(key)).willReturn(new AssetDeleteResult(key, archiveKey));

        mockMvc.perform(delete("/admin/assets").param("key", key).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(key));

        verify(storage).archiveAndDelete(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "characters/cat/../animations/idle.webp",
            "characters/cat//animations/idle.webp"
    })
    @WithMockUser(roles = "ADMIN")
    void 경로이동이나_빈_경로가_있는_key는_S3_접근_전에_거부한다(String key) throws Exception {
        mockMvc.perform(delete("/admin/assets").param("key", key).with(csrf()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(storage);
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
