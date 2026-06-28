package com.triples.rougether.userapi.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.PlacementType;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.MemberRole;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    TokenService tokenService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    ThemeRepository themeRepository;
    @Autowired
    ItemRepository itemRepository;
    @Autowired
    UserItemRepository userItemRepository;
    @Autowired
    CharacterRepository characterRepository;

    @Test
    void 프론트가_콘텐츠_asset_key를_조회할_수_있다() throws Exception {
        User user = userRepository.save(User.signUp());
        String accessToken = tokenService.issueAccessToken(user.getId(), MemberRole.NORMAL);

        Theme activeTheme = themeRepository.save(
                Theme.create("front_theme_content", "프론트 테마", "themes/front.png", true));
        Theme activeCharacterTheme = themeRepository.save(
                Theme.create("front_character_theme_content", "프론트 캐릭터 테마", "themes/front-character.png", true));
        Theme inactiveTheme = themeRepository.save(
                Theme.create("hidden_theme_content", "숨김 테마", "themes/hidden.png", false));

        Item ownedItem = itemRepository.save(Item.create(
                activeTheme,
                "bed",
                PlacementType.SURFACE,
                "BED",
                null,
                "프론트 침대",
                CurrencyType.DIAMOND,
                100,
                "items/front-bed.png",
                false,
                true));
        itemRepository.save(Item.create(
                activeCharacterTheme,
                "hat",
                PlacementType.CHARACTER,
                null,
                "HAT",
                "프론트 모자",
                null,
                null,
                "items/front-hat.png",
                false,
                true));
        itemRepository.save(Item.create(
                activeTheme,
                "desk",
                PlacementType.SURFACE,
                "DESK",
                null,
                "비활성 책상",
                CurrencyType.DIAMOND,
                80,
                "items/inactive-desk.png",
                false,
                false));
        itemRepository.save(Item.create(
                inactiveTheme,
                "chair",
                PlacementType.SURFACE,
                "CHAIR",
                null,
                "숨김 테마 의자",
                CurrencyType.DIAMOND,
                70,
                "items/hidden-chair.png",
                false,
                true));
        userItemRepository.save(UserItem.acquire(user, ownedItem, Instant.now()));

        characterRepository.save(Character.create(
                "front_bear_content",
                "프론트 곰돌이",
                "characters/front-bear.png",
                1,
                true));
        characterRepository.save(Character.create(
                "hidden_bear_content",
                "숨김 곰돌이",
                "characters/hidden-bear.png",
                2,
                false));

        mockMvc.perform(get("/api/v1/themes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].code").value("front_theme_content"))
                .andExpect(jsonPath("$.items[0].coverImageKey").value("themes/front.png"))
                .andExpect(jsonPath("$.items[1].code").value("front_character_theme_content"))
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(get("/api/v1/items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("프론트 침대"))
                .andExpect(jsonPath("$.items[0].assetKey").value("items/front-bed.png"))
                .andExpect(jsonPath("$.items[0].theme.coverImageKey").value("themes/front.png"))
                .andExpect(jsonPath("$.items[0].owned").value(true))
                .andExpect(jsonPath("$.items[1].name").value("프론트 모자"))
                .andExpect(jsonPath("$.items[1].placementType").value("CHARACTER"))
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(get("/api/v1/items")
                        .param("themeId", String.valueOf(activeTheme.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("프론트 침대"))
                .andExpect(jsonPath("$.items.length()").value(1));

        mockMvc.perform(get("/api/v1/items")
                        .param("placementType", "CHARACTER")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("프론트 모자"))
                .andExpect(jsonPath("$.items[0].placementType").value("CHARACTER"))
                .andExpect(jsonPath("$.items.length()").value(1));

        mockMvc.perform(get("/api/v1/characters")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].code").value("front_bear_content"))
                .andExpect(jsonPath("$.items[0].baseAssetKey").value("characters/front-bear.png"))
                .andExpect(jsonPath("$.items.length()").value(1));
    }
}
