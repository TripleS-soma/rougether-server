package com.triples.rougether.adminapi.accessoryrender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.repository.CharacterAccessoryRenderProfileRepository;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CharacterAccessoryRenderProfileAdminTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ThemeRepository themeRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private CharacterAccessoryRenderProfileRepository renderProfileRepository;

    private Item sunglasses;

    @BeforeEach
    void setUp() {
        Theme theme = themeRepository.save(new Theme(
                "accessory_render_theme", "악세사리 렌더", null, true));
        sunglasses = itemRepository.save(new Item(
                theme, "character_accessory", "character", null, "eyewear",
                "선글라스", null, null,
                "items/character-accessories/eyewear/cat-sunglasses/thumbnail.png",
                false, true));
        characterRepository.save(new Character(
                "cat_render", "고양이", "characters/cat.png", 1, true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 프로필을_asset_key와_캐릭터_코드로_멱등_적재하고_조회한다() throws Exception {
        mockMvc.perform(post("/admin/character-accessory-render-profiles/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("0.31000"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(0));

        mockMvc.perform(post("/admin/character-accessory-render-profiles/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("0.32000"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(1));

        var stored = renderProfileRepository.findByItemIdAndCharacterIdAndRenderState(
                sunglasses.getId(),
                characterRepository.findByCode("cat_render").orElseThrow().getId(),
                "default").orElseThrow();
        assertThat(stored.getPositionY()).isEqualByComparingTo(new BigDecimal("0.32000"));

        mockMvc.perform(get("/admin/character-accessory-render-profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].itemAssetKey")
                        .value("items/character-accessories/eyewear/cat-sunglasses/thumbnail.png"))
                .andExpect(jsonPath("$.items[0].characterCode").value("cat_render"))
                .andExpect(jsonPath("$.items[0].characterName").value("고양이"))
                .andExpect(jsonPath("$.items[0].characterAssetKey")
                        .value("characters/cat.png"))
                .andExpect(jsonPath("$.items[0].renderState").value("default"))
                .andExpect(jsonPath("$.items[0].canvasWidth").value(180))
                .andExpect(jsonPath("$.items[0].canvasHeight").value(172))
                .andExpect(jsonPath("$.items[0].assetWidth").value(320))
                .andExpect(jsonPath("$.items[0].assetHeight").value(160))
                .andExpect(jsonPath("$.items[0].positionY").value(0.32))
                .andExpect(jsonPath("$.items[0].widthRatio").value(0.52));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 좌표와_너비_범위를_벗어난_프로필은_거부한다() throws Exception {
        String body = profileBody("1.10000");

        mockMvc.perform(post("/admin/character-accessory-render-profiles/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHARACTER_ACCESSORY_RENDER_PROFILE_INVALID"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 캔버스와_악세사리_원본_크기는_양수여야_한다() throws Exception {
        String body = profileBody("0.31000")
                .replace("\"canvasWidth\": 180", "\"canvasWidth\": 0");

        mockMvc.perform(post("/admin/character-accessory-render-profiles/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHARACTER_ACCESSORY_RENDER_PROFILE_INVALID"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 전체_CDN_URL은_렌더_프로필_asset_key로_저장할_수_없다() throws Exception {
        String body = profileBody("0.31000")
                .replace(
                        "\"assetKey\": \"items/character-accessories/eyewear/cat-sunglasses/thumbnail.png\"",
                        "\"assetKey\": \"https://cdn.example.com/cat-sunglasses.png\"");

        mockMvc.perform(post("/admin/character-accessory-render-profiles/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHARACTER_ACCESSORY_RENDER_PROFILE_INVALID"));

        assertThat(renderProfileRepository.count()).isZero();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 기존_프로필의_transform만_단건_수정한다() throws Exception {
        Long profileId = createProfile();

        mockMvc.perform(put("/admin/character-accessory-render-profiles/{profileId}", profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transformBody())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(profileId))
                .andExpect(jsonPath("$.positionX").value(0.51235))
                .andExpect(jsonPath("$.positionY").value(0.62346))
                .andExpect(jsonPath("$.widthRatio").value(0.3334))
                .andExpect(jsonPath("$.rotationDeg").value(-15))
                .andExpect(jsonPath("$.zIndex").value(25))
                .andExpect(jsonPath("$.assetKey")
                        .value("items/character-accessories/eyewear/cat-sunglasses/thumbnail.png"))
                .andExpect(jsonPath("$.canvasWidth").value(180))
                .andExpect(jsonPath("$.assetWidth").value(320));

        var stored = renderProfileRepository.findById(profileId).orElseThrow();
        assertThat(stored.getPositionX()).isEqualByComparingTo("0.51235");
        assertThat(stored.getPositionY()).isEqualByComparingTo("0.62346");
        assertThat(stored.getWidthRatio()).isEqualByComparingTo("0.3334");
        assertThat(stored.getRenderState()).isEqualTo("default");
        assertThat(stored.getAssetKey())
                .isEqualTo("items/character-accessories/eyewear/cat-sunglasses/thumbnail.png");
        assertThat(stored.getCanvasHeight()).isEqualTo(172);
        assertThat(stored.getAssetHeight()).isEqualTo(160);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 단건_수정의_필수값_누락은_거부한다() throws Exception {
        Long profileId = createProfile();

        mockMvc.perform(put("/admin/character-accessory-render-profiles/{profileId}", profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "positionX": 0.50000,
                                  "widthRatio": 0.5200,
                                  "rotationDeg": 0,
                                  "zIndex": 20
                                }
                                """)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("CHARACTER_ACCESSORY_RENDER_PROFILE_INVALID"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 단건_수정의_z_index_누락은_거부한다() throws Exception {
        Long profileId = createProfile();

        mockMvc.perform(put("/admin/character-accessory-render-profiles/{profileId}", profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "positionX": 0.50000,
                                  "positionY": 0.31000,
                                  "widthRatio": 0.5200,
                                  "rotationDeg": 0
                                }
                                """)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("CHARACTER_ACCESSORY_RENDER_PROFILE_INVALID"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 단건_수정의_범위_위반은_거부하고_기존값을_유지한다() throws Exception {
        Long profileId = createProfile();

        mockMvc.perform(put("/admin/character-accessory-render-profiles/{profileId}", profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transformBody().replace(
                                "\"positionX\": 0.5123456",
                                "\"positionX\": -0.10000"))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("CHARACTER_ACCESSORY_RENDER_PROFILE_INVALID"));

        var stored = renderProfileRepository.findById(profileId).orElseThrow();
        assertThat(stored.getPositionX()).isEqualByComparingTo("0.50000");
        assertThat(stored.getPositionY()).isEqualByComparingTo("0.31000");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 없는_프로필_단건_수정은_404를_반환한다() throws Exception {
        mockMvc.perform(put("/admin/character-accessory-render-profiles/{profileId}", 999999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transformBody())
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("CHARACTER_ACCESSORY_RENDER_PROFILE_NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 단건_수정은_csrf_토큰이_필요하다() throws Exception {
        Long profileId = createProfile();

        mockMvc.perform(put("/admin/character-accessory-render-profiles/{profileId}", profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transformBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 단건_수정은_로그인이_필요하다() throws Exception {
        mockMvc.perform(put("/admin/character-accessory-render-profiles/{profileId}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transformBody())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void 단건_수정은_admin_권한이_필요하다() throws Exception {
        mockMvc.perform(put("/admin/character-accessory-render-profiles/{profileId}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transformBody())
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 렌더_프로필_관리_화면은_로그인이_필요하다() throws Exception {
        mockMvc.perform(get("/accessory-render-profiles"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 렌더_프로필_관리_화면을_연다() throws Exception {
        mockMvc.perform(get("/accessory-render-profiles"))
                .andExpect(status().isOk())
                .andExpect(view().name("accessory-render-profiles"))
                .andExpect(model().attribute("username", "user"))
                .andExpect(model().attributeExists("s3BaseUrl"));
    }

    private Long createProfile() throws Exception {
        mockMvc.perform(post("/admin/character-accessory-render-profiles/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("0.31000"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));
        return renderProfileRepository.findByItemIdAndCharacterIdAndRenderState(
                        sunglasses.getId(),
                        characterRepository.findByCode("cat_render").orElseThrow().getId(),
                        "default")
                .orElseThrow()
                .getId();
    }

    private String transformBody() {
        return """
                {
                  "positionX": 0.5123456,
                  "positionY": 0.623456,
                  "widthRatio": 0.33335,
                  "rotationDeg": -15,
                  "zIndex": 25
                }
                """;
    }

    private String profileBody(String positionY) {
        return """
                [
                  {
                    "itemAssetKey": "items/character-accessories/eyewear/cat-sunglasses/thumbnail.png",
                    "characterCode": "cat_render",
                    "renderState": "default",
                    "assetKey": "items/character-accessories/eyewear/cat-sunglasses/thumbnail.png",
                    "canvasWidth": 180,
                    "canvasHeight": 172,
                    "assetWidth": 320,
                    "assetHeight": 160,
                    "positionX": 0.50000,
                    "positionY": %s,
                    "widthRatio": 0.5200,
                    "rotationDeg": 0,
                    "zIndex": 20
                  }
                ]
                """.formatted(positionY);
    }
}
