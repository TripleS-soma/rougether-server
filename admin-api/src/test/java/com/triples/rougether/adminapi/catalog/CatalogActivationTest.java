package com.triples.rougether.adminapi.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.adminapi.asset.service.AssetStorageService;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
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
class CatalogActivationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ThemeRepository themeRepository;

    @Autowired
    ItemRepository itemRepository;

    @Autowired
    CharacterRepository characterRepository;

    @MockitoBean
    AssetStorageService storage;

    @Test
    @WithMockUser(roles = "ADMIN")
    void 아이템을_사용으로_전환하면_즉시_활성화된다() throws Exception {
        Item item = saveInactiveItem("activation_item_theme", "items/activation/sofa.png");
        given(storage.exists("items/activation/sofa.png")).willReturn(true);

        mockMvc.perform(put("/admin/catalog/items/{itemId}/active", item.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        assertThat(itemRepository.findById(item.getId()).orElseThrow().isActive()).isTrue();

        mockMvc.perform(put("/admin/catalog/items/{itemId}/active", item.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        assertThat(itemRepository.findById(item.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void S3에_에셋이_없는_아이템은_활성화하지_않는다() throws Exception {
        Item item = saveInactiveItem("activation_missing_theme", "items/activation/missing.png");
        given(storage.exists(anyString())).willReturn(false);

        mockMvc.perform(put("/admin/catalog/items/{itemId}/active", item.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATALOG_ACTIVATION_INVALID"));

        assertThat(itemRepository.findById(item.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 애니메이션_3종이_모두_있어야_캐릭터를_활성화한다() throws Exception {
        Character character = characterRepository.save(new Character(
                "activation_cat", "활성화 고양이", "characters/activation-cat.webp", 999, false));
        given(storage.exists("characters/activation-cat.webp")).willReturn(true);
        given(storage.exists("characters/activation_cat/animations/idle.webp")).willReturn(true);
        given(storage.exists("characters/activation_cat/animations/pose-cycle.webp")).willReturn(true);
        given(storage.exists("characters/activation_cat/animations/wave.webp")).willReturn(false);

        // wave.webp 가 없으면 활성화 거부 — 프론트 애니메이션 404 를 사전에 막는다.
        mockMvc.perform(put("/admin/catalog/characters/{characterId}/active", character.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATALOG_ACTIVATION_INVALID"))
                .andExpect(jsonPath("$.message",
                        containsString("characters/activation_cat/animations/wave.webp")));

        given(storage.exists("characters/activation_cat/animations/wave.webp")).willReturn(true);

        mockMvc.perform(put("/admin/catalog/characters/{characterId}/active", character.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        assertThat(characterRepository.findById(character.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 캐릭터_비활성화는_S3_검증_없이_즉시_반영된다() throws Exception {
        Character character = characterRepository.save(new Character(
                "deactivation_cat", "비활성화 고양이", "characters/deactivation-cat.webp", 998, true));
        given(storage.exists(anyString())).willReturn(false);

        mockMvc.perform(put("/admin/catalog/characters/{characterId}/active", character.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        assertThat(characterRepository.findById(character.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 카탈로그_목록은_비활성_아이템과_캐릭터를_포함한다() throws Exception {
        Item item = saveInactiveItem("activation_list_theme", "items/activation/list.png");
        Character character = characterRepository.save(new Character(
                "activation_list_cat", "목록 고양이", "characters/activation-list-cat.webp", 997, false));

        mockMvc.perform(get("/admin/catalog/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == %d)].active".formatted(item.getId()))
                        .value(false));

        mockMvc.perform(get("/admin/catalog/characters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.characters[?(@.id == %d)].code".formatted(character.getId()))
                        .value("activation_list_cat"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 토글은_CSRF_토큰_없이_거부된다() throws Exception {
        Item item = saveInactiveItem("activation_csrf_theme", "items/activation/csrf.png");
        given(storage.exists(anyString())).willReturn(true);

        mockMvc.perform(put("/admin/catalog/items/{itemId}/active", item.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": true}"))
                .andExpect(status().isForbidden());

        assertThat(itemRepository.findById(item.getId()).orElseThrow().isActive()).isFalse();
    }

    // 적재(import) 경로는 seed 스크립트·admin-asset-mcp 가 세션 쿠키만으로 curl 호출하므로
    // CSRF 예외가 유지되어야 한다. 403 이 아니라 컨트롤러/서비스 검증까지 도달하는지로 계약을 고정한다.
    @Test
    @WithMockUser(roles = "ADMIN")
    void 적재_경로는_CSRF_토큰_없이_컨트롤러까지_도달한다() throws Exception {
        mockMvc.perform(post("/admin/catalog/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"themes\": [], \"characters\": [], \"items\": []}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/catalog/character-accessories/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHARACTER_ACCESSORY_CATALOG_IMPORT_INVALID"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 없는_아이템은_404를_반환한다() throws Exception {
        mockMvc.perform(put("/admin/catalog/items/{itemId}/active", 999_999_999L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATALOG_TARGET_NOT_FOUND"));
    }

    private Item saveInactiveItem(String themeCode, String assetKey) {
        Theme theme = themeRepository.save(new Theme(themeCode, "활성화 테스트 테마", null, true));
        return itemRepository.save(new Item(theme, "furniture", "positioned", null, null,
                "활성화 테스트 아이템", null, null, assetKey, false, false));
    }
}
