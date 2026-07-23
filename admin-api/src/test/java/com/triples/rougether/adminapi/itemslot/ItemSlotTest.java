package com.triples.rougether.adminapi.itemslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ItemSlotTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ThemeRepository themeRepository;

    @Autowired
    ItemRepository itemRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Item positionedItem;
    private Item surfaceItem;
    private Item animatedItem;
    private Item positionedItemWithoutPool;

    @BeforeEach
    void setUp() {
        Theme theme = themeRepository.save(new Theme("slot_test_theme", "슬롯 테스트 테마", null, true));
        positionedItem = itemRepository.save(new Item(
                theme, "furniture", "positioned", null, null,
                "테스트 의자", CurrencyType.COIN, 100, "items/slot-test/chair.png", false, true));
        surfaceItem = itemRepository.save(new Item(
                theme, "wallpaper", "surface_slot", "wallpaper", null,
                "테스트 벽지", CurrencyType.COIN, 100, "items/slot-test/wallpaper.png", false, true));
        animatedItem = itemRepository.save(new Item(
                theme, "furniture", "positioned", null, null,
                "움직이는 미니 오븐", CurrencyType.COIN, 100,
                "items/slot-test/mini-oven-animated-v1.webp", false, true));
        positionedItemWithoutPool = itemRepository.save(new Item(
                theme, "furniture", "positioned", null, null,
                "뽑기 미등록 의자", CurrencyType.COIN, 100,
                "items/slot-test/no-pool-chair.png", false, true));

        Long gachaId = insertGacha(theme.getId(), "slot_test_gacha", "슬롯 테스트 뽑기");
        insertItemPoolEntry(gachaId, positionedItem.getId(), "일반");
        insertItemPoolEntry(gachaId, animatedItem.getId(), "희귀");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 벌크_적재는_asset_key로_매칭해_슬롯을_적용하고_실패건을_리포트한다() throws Exception {
        String body = """
                [
                  {"assetKey": "items/slot-test/chair.png", "slot": "bottomCenter", "reason": "하단 중앙 대형 가구"},
                  {"assetKey": "items/slot-test/missing.png", "slot": "topLeft"},
                  {"assetKey": "items/slot-test/wallpaper.png", "slot": "badSlot"}
                ]
                """;

        mockMvc.perform(post("/admin/items/slots/import")
                        .contentType(MediaType.APPLICATION_JSON).content(body).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.notFound[0]").value("items/slot-test/missing.png"))
                .andExpect(jsonPath("$.invalid[0]").value("items/slot-test/wallpaper.png"));

        assertThat(itemRepository.findById(positionedItem.getId()).orElseThrow().getDefaultSlot())
                .isEqualTo("bottomCenter");

        // 재실행해도 같은 최종 상태(멱등)
        mockMvc.perform(post("/admin/items/slots/import")
                        .contentType(MediaType.APPLICATION_JSON).content(body).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 벌크_적재는_curl_스크립트용으로_CSRF_토큰_없이_허용된다() throws Exception {
        mockMvc.perform(post("/admin/items/slots/import")
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 단건_슬롯_변경과_해제() throws Exception {
        mockMvc.perform(put("/admin/items/{itemId}/slot", positionedItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slot\": \"topLeft\"}").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultSlot").value("topLeft"));

        mockMvc.perform(put("/admin/items/{itemId}/slot", positionedItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slot\": null}").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultSlot").doesNotExist());

        assertThat(itemRepository.findById(positionedItem.getId()).orElseThrow().getDefaultSlot()).isNull();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 새_아이템과_DB의_defaultScale_기본값은_1_00이다() throws Exception {
        assertThat(positionedItem.getDefaultScale()).isEqualByComparingTo("1.00");
        assertThat(positionedItem.getDefaultPositionX()).isNull();
        assertThat(positionedItem.getDefaultPositionY()).isNull();

        jdbcTemplate.update("""
                INSERT INTO items
                    (theme_id, category_code, placement_type, surface_slot_type, character_slot_type,
                     name, purchase_currency_type, price_amount, asset_key, is_limited, is_active)
                VALUES (?, 'furniture', 'positioned', NULL, NULL,
                        'DB 기본 배율 의자', 'COIN', 100, 'items/slot-test/db-default-chair.png', FALSE, TRUE)
                """, positionedItem.getTheme().getId());

        Map<String, Object> databaseDefaults = jdbcTemplate.queryForMap(
                """
                SELECT default_scale, default_position_x, default_position_y
                FROM items
                WHERE asset_key = 'items/slot-test/db-default-chair.png'
                """);
        assertThat((BigDecimal) databaseDefaults.get("default_scale")).isEqualByComparingTo("1.00");
        assertThat(databaseDefaults.get("default_position_x")).isNull();
        assertThat(databaseDefaults.get("default_position_y")).isNull();

        mockMvc.perform(get("/admin/items/slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/chair.png')].defaultScale")
                        .value(1.0))
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/chair.png')].defaultPositionX")
                        .value(everyItem(nullValue())))
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/chair.png')].defaultPositionY")
                        .value(everyItem(nullValue())));
    }

    @Test
    void DB는_기본_위치의_X_Y_한쪽만_저장하는_것을_거부한다() {
        itemRepository.flush();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE items SET default_position_x = 0.25, default_position_y = NULL WHERE id = ?",
                positionedItem.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void DB는_기본_위치의_0_1_범위를_강제한다() {
        itemRepository.flush();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE items SET default_position_x = -0.01, default_position_y = 1.01 WHERE id = ?",
                positionedItem.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 기본_크기_배율을_HALF_UP_두_자리로_정규화해_저장한다() throws Exception {
        mockMvc.perform(put("/admin/items/{itemId}/default-scale", positionedItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultScale\": 1.235}").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultScale").value(1.24));

        itemRepository.flush();
        BigDecimal persisted = jdbcTemplate.queryForObject(
                "SELECT default_scale FROM items WHERE id = ?", BigDecimal.class, positionedItem.getId());
        assertThat(persisted).isEqualByComparingTo("1.24");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void FREE_기본_크기와_중심_좌표를_한번에_정규화해_저장한다() throws Exception {
        mockMvc.perform(put("/admin/items/{itemId}/render-defaults", positionedItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "defaultScale": 1.235,
                                  "defaultPositionX": 0.123456,
                                  "defaultPositionY": 0.987654
                                }
                                """).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultScale").value(1.24))
                .andExpect(jsonPath("$.defaultPositionX").value(0.12346))
                .andExpect(jsonPath("$.defaultPositionY").value(0.98765));

        itemRepository.flush();
        Map<String, Object> persisted = jdbcTemplate.queryForMap(
                "SELECT default_scale, default_position_x, default_position_y FROM items WHERE id = ?",
                positionedItem.getId());
        assertThat((BigDecimal) persisted.get("default_scale")).isEqualByComparingTo("1.24");
        assertThat((BigDecimal) persisted.get("default_position_x")).isEqualByComparingTo("0.12346");
        assertThat((BigDecimal) persisted.get("default_position_y")).isEqualByComparingTo("0.98765");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void FREE_기본_위치는_0과_1_경계를_허용하고_둘_다_null이면_공통_기본값으로_초기화한다() throws Exception {
        mockMvc.perform(put("/admin/items/{itemId}/render-defaults", positionedItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "defaultScale": 1.20,
                                  "defaultPositionX": 0,
                                  "defaultPositionY": 1
                                }
                                """).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultPositionX").value(0.0))
                .andExpect(jsonPath("$.defaultPositionY").value(1.0));

        mockMvc.perform(put("/admin/items/{itemId}/render-defaults", positionedItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "defaultScale": 1.20,
                                  "defaultPositionX": null,
                                  "defaultPositionY": null
                                }
                                """).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultPositionX").doesNotExist())
                .andExpect(jsonPath("$.defaultPositionY").doesNotExist());

        assertThat(positionedItem.getDefaultPositionX()).isNull();
        assertThat(positionedItem.getDefaultPositionY()).isNull();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void FREE_기본_위치는_X와_Y를_한쪽만_비울_수_없다() throws Exception {
        mockMvc.perform(put("/admin/items/{itemId}/render-defaults", positionedItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "defaultScale": 1.20,
                                  "defaultPositionX": 0.5,
                                  "defaultPositionY": null
                                }
                                """).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ITEM_RENDER_DEFAULTS_INVALID"))
                .andExpect(jsonPath("$.message")
                        .value("기본 위치 X와 Y는 둘 다 입력하거나 둘 다 비워야 합니다."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void FREE_기본_위치는_반올림_전_원본값으로_범위를_검증하고_실패시_배율도_바꾸지_않는다() throws Exception {
        positionedItem.updateRenderDefaults(
                new BigDecimal("1.10"), new BigDecimal("0.40"), new BigDecimal("0.60"));

        mockMvc.perform(put("/admin/items/{itemId}/render-defaults", positionedItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "defaultScale": 1.80,
                                  "defaultPositionX": -0.000001,
                                  "defaultPositionY": 0.5
                                }
                                """).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ITEM_RENDER_DEFAULTS_INVALID"))
                .andExpect(jsonPath("$.message")
                        .value("기본 위치 X와 Y는 0 이상 1 이하의 숫자여야 합니다."));

        assertThat(positionedItem.getDefaultScale()).isEqualByComparingTo("1.10");
        assertThat(positionedItem.getDefaultPositionX()).isEqualByComparingTo("0.40");
        assertThat(positionedItem.getDefaultPositionY()).isEqualByComparingTo("0.60");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void surface_아이템의_FREE_기본_렌더링_값은_변경할_수_없다() throws Exception {
        mockMvc.perform(put("/admin/items/{itemId}/render-defaults", surfaceItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "defaultScale": 1.25,
                                  "defaultPositionX": 0.5,
                                  "defaultPositionY": 0.5
                                }
                                """).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ITEM_RENDER_DEFAULTS_INVALID"))
                .andExpect(jsonPath("$.message")
                        .value("positioned 아이템만 FREE 기본 렌더링 값을 변경할 수 있습니다."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void FREE_기본_렌더링_값_변경은_CSRF_토큰이_필요하다() throws Exception {
        mockMvc.perform(put("/admin/items/{itemId}/render-defaults", positionedItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "defaultScale": 1.25,
                                  "defaultPositionX": 0.5,
                                  "defaultPositionY": 0.5
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 기본_크기_배율의_양쪽_경계값을_허용한다() throws Exception {
        for (String defaultScale : new String[]{"0.50", "2.00"}) {
            mockMvc.perform(put("/admin/items/{itemId}/default-scale", positionedItem.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"defaultScale\": " + defaultScale + "}").with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.defaultScale").value(Double.parseDouble(defaultScale)));
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 기본_크기_배율은_반올림_전_원본값으로_범위를_검증한다() throws Exception {
        for (String defaultScale : new String[]{"0.499", "2.001"}) {
            mockMvc.perform(put("/admin/items/{itemId}/default-scale", positionedItem.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"defaultScale\": " + defaultScale + "}").with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ITEM_DEFAULT_SCALE_INVALID"))
                    .andExpect(jsonPath("$.message")
                            .value("기본 크기 배율은 0.50 이상 2.00 이하의 숫자여야 합니다."));
        }

        assertThat(positionedItem.getDefaultScale()).isEqualByComparingTo("1.00");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void null_기본_크기_배율은_구체적인_400_오류를_반환한다() throws Exception {
        mockMvc.perform(put("/admin/items/{itemId}/default-scale", positionedItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultScale\": null}").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ITEM_DEFAULT_SCALE_INVALID"))
                .andExpect(jsonPath("$.message")
                        .value("기본 크기 배율은 0.50 이상 2.00 이하의 숫자여야 합니다."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void surface_아이템의_기본_크기_배율은_변경할_수_없다() throws Exception {
        mockMvc.perform(put("/admin/items/{itemId}/default-scale", surfaceItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultScale\": 1.25}").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ITEM_DEFAULT_SCALE_INVALID"))
                .andExpect(jsonPath("$.message")
                        .value("positioned 아이템만 기본 크기 배율을 변경할 수 있습니다."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 기본_크기_배율_변경은_CSRF_토큰이_필요하다() throws Exception {
        mockMvc.perform(put("/admin/items/{itemId}/default-scale", positionedItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultScale\": 1.25}"))
                .andExpect(status().isForbidden());

        assertThat(positionedItem.getDefaultScale()).isEqualByComparingTo("1.00");
    }

    @Test
    void 기본_크기_배율_변경은_인증이_필요하다() throws Exception {
        mockMvc.perform(put("/admin/items/{itemId}/default-scale", positionedItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultScale\": 1.25}").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void surface_슬롯_코드나_없는_코드는_400() throws Exception {
        // surface 계열 코드(wallpaper)는 positioned 슬롯이 아니다
        mockMvc.perform(put("/admin/items/{itemId}/slot", positionedItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slot\": \"wallpaper\"}").with(csrf()))
                .andExpect(status().isBadRequest());

        // midCenter 는 캐릭터 자리라 슬롯 집합에 없다 (프론트 FurnitureSlot 과 동일)
        mockMvc.perform(put("/admin/items/{itemId}/slot", positionedItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slot\": \"midCenter\"}").with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void surface_아이템에는_슬롯을_지정할_수_없다() throws Exception {
        mockMvc.perform(put("/admin/items/{itemId}/slot", surfaceItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slot\": \"topLeft\"}").with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 슬롯_목록은_positioned_아이템만_내려준다() throws Exception {
        mockMvc.perform(get("/admin/items/slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/chair.png')].themeCode")
                        .value("slot_test_theme"))
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/chair.png')].themeName")
                        .value("슬롯 테스트 테마"))
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/chair.png')].rarity")
                        .value("일반"))
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/chair.png')].rarityEditable")
                        .value(true))
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/chair.png')].rarityConflict")
                        .value(false))
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/chair.png')].animated")
                        .value(false))
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/mini-oven-animated-v1.webp')].animated")
                        .value(true))
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/no-pool-chair.png')].rarityEditable")
                        .value(false))
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/wallpaper.png')]").isEmpty());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 룸_미리보기_표면_목록은_활성_카탈로그만_내려준다() throws Exception {
        itemRepository.save(new Item(
                positionedItem.getTheme(), "floor", "surface_slot", "floor", null,
                "비활성 바닥", CurrencyType.COIN, 100,
                "items/slot-test/inactive-floor.png", false, false));
        Theme inactiveTheme = themeRepository.save(
                new Theme("inactive_surface_theme", "비활성 표면 테마", null, false));
        itemRepository.save(new Item(
                inactiveTheme, "background", "surface_slot", "background", null,
                "비활성 테마 배경", CurrencyType.COIN, 100,
                "items/slot-test/inactive-theme-background.png", false, true));

        mockMvc.perform(get("/admin/items/surfaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/wallpaper.png')].themeCode")
                        .value("slot_test_theme"))
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/wallpaper.png')].themeName")
                        .value("슬롯 테스트 테마"))
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/wallpaper.png')].surfaceSlotType")
                        .value("wallpaper"))
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/inactive-floor.png')]").isEmpty())
                .andExpect(jsonPath(
                        "$.items[?(@.assetKey == 'items/slot-test/inactive-theme-background.png')]")
                        .isEmpty());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 동일한_테마_이름이_있어도_표면은_themeCode로_구분된다() throws Exception {
        Theme sameNameTheme = themeRepository.save(
                new Theme("slot_test_theme_other", "슬롯 테스트 테마", null, true));
        itemRepository.save(new Item(
                sameNameTheme, "wallpaper", "surface_slot", "wallpaper", null,
                "다른 테마 벽지", CurrencyType.COIN, 100,
                "items/slot-test/other-theme-wallpaper.png", false, true));

        mockMvc.perform(get("/admin/items/surfaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/wallpaper.png')].themeCode")
                        .value("slot_test_theme"))
                .andExpect(jsonPath(
                        "$.items[?(@.assetKey == 'items/slot-test/other-theme-wallpaper.png')].themeCode")
                        .value("slot_test_theme_other"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 등급_변경은_아이템의_모든_활성_ITEM_풀을_한_트랜잭션에서_통일한다() throws Exception {
        Long secondGachaId = insertGacha(
                positionedItem.getTheme().getId(), "slot_test_gacha_2", "두 번째 슬롯 테스트 뽑기");
        insertItemPoolEntry(secondGachaId, positionedItem.getId(), "희귀");
        insertItemPoolEntry(secondGachaId, positionedItem.getId(), "희귀", false);

        for (String rarity : new String[]{"일반", "희귀", "전설"}) {
            mockMvc.perform(put("/admin/items/{itemId}/rarity", positionedItem.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rarity\": \"" + rarity + "\"}").with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rarity").value(rarity))
                    .andExpect(jsonPath("$.rarityEditable").value(true))
                    .andExpect(jsonPath("$.rarityConflict").value(false));
        }

        Integer legendaryCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM gacha_pool_entries
                WHERE item_id = ? AND reward_type = 'ITEM' AND is_active = TRUE AND rarity = '전설'
                """, Integer.class, positionedItem.getId());
        assertThat(legendaryCount).isEqualTo(2);

        String inactiveRarity = jdbcTemplate.queryForObject("""
                SELECT rarity FROM gacha_pool_entries
                WHERE item_id = ? AND reward_type = 'ITEM' AND is_active = FALSE
                """, String.class, positionedItem.getId());
        assertThat(inactiveRarity).isEqualTo("희귀");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 서로_다른_활성_풀_등급은_목록에서_혼합으로_표시한다() throws Exception {
        Long secondGachaId = insertGacha(
                positionedItem.getTheme().getId(), "slot_test_gacha_2", "두 번째 슬롯 테스트 뽑기");
        insertItemPoolEntry(secondGachaId, positionedItem.getId(), "희귀");

        mockMvc.perform(get("/admin/items/slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/chair.png')].rarity")
                        .doesNotExist())
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/chair.png')].rarityConflict")
                        .value(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 허용되지_않은_등급은_400이고_기존_등급을_유지한다() throws Exception {
        mockMvc.perform(put("/admin/items/{itemId}/rarity", positionedItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rarity\": \"EPIC\"}").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ITEM_RARITY_INVALID"));

        String rarity = jdbcTemplate.queryForObject("""
                SELECT rarity FROM gacha_pool_entries
                WHERE item_id = ? AND reward_type = 'ITEM' AND is_active = TRUE
                """, String.class, positionedItem.getId());
        assertThat(rarity).isEqualTo("일반");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 비어있는_등급은_400이다() throws Exception {
        for (String body : new String[]{"{\"rarity\": null}", "{\"rarity\": \"\"}"}) {
            mockMvc.perform(put("/admin/items/{itemId}/rarity", positionedItem.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body).with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ITEM_RARITY_INVALID"));
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void surface_아이템은_등급을_지정할_수_없다() throws Exception {
        mockMvc.perform(put("/admin/items/{itemId}/rarity", surfaceItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rarity\": \"희귀\"}").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ITEM_RARITY_INVALID"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 미등록_아이템에_등급을_지정하면_테마_활성_뽑기_풀에_등록된다() throws Exception {
        mockMvc.perform(put("/admin/items/{itemId}/rarity", positionedItemWithoutPool.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rarity\": \"희귀\"}").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rarity").value("희귀"))
                .andExpect(jsonPath("$.rarityEditable").value(true))
                .andExpect(jsonPath("$.rarityConflict").value(false));

        // 기존 테마 머신(slot_test_gacha)에 엔트리를 추가하고, 새 머신은 만들지 않는다
        Integer gachaCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gacha WHERE theme_id = ?", Integer.class,
                positionedItemWithoutPool.getTheme().getId());
        assertThat(gachaCount).isEqualTo(1);

        String rarity = jdbcTemplate.queryForObject("""
                SELECT rarity FROM gacha_pool_entries
                WHERE item_id = ? AND reward_type = 'ITEM' AND is_active = TRUE
                """, String.class, positionedItemWithoutPool.getId());
        assertThat(rarity).isEqualTo("희귀");

        Integer weight = jdbcTemplate.queryForObject("""
                SELECT weight FROM gacha_pool_entries
                WHERE item_id = ? AND reward_type = 'ITEM' AND is_active = TRUE
                """, Integer.class, positionedItemWithoutPool.getId());
        assertThat(weight).isEqualTo(1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 테마에_활성_뽑기가_없으면_머신을_만들어_등록한다() throws Exception {
        Theme newTheme = themeRepository.save(new Theme("no_gacha_theme", "뽑기 없는 테마", null, true));
        Item newItem = itemRepository.save(new Item(
                newTheme, "furniture", "positioned", null, null,
                "새 테마 가구", CurrencyType.COIN, 100, "items/no-gacha/sofa.png", false, true));

        mockMvc.perform(put("/admin/items/{itemId}/rarity", newItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rarity\": \"전설\"}").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rarity").value("전설"))
                .andExpect(jsonPath("$.rarityEditable").value(true));

        // 스펙 기준 가구 뽑기 머신: 테마 코드 승계, COIN 250, 1회 뽑기, 즉시 활성
        Integer gachaCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM gacha
                WHERE theme_id = ? AND code = 'no_gacha_theme'
                  AND cost_currency_type = 'COIN' AND cost_amount = 250
                  AND draw_count = 1 AND is_active = TRUE
                """, Integer.class, newTheme.getId());
        assertThat(gachaCount).isEqualTo(1);

        String rarity = jdbcTemplate.queryForObject("""
                SELECT rarity FROM gacha_pool_entries
                WHERE item_id = ? AND reward_type = 'ITEM' AND is_active = TRUE
                """, String.class, newItem.getId());
        assertThat(rarity).isEqualTo("전설");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 비활성_머신의_엔트리는_미등록으로_취급되어_활성_머신에_새로_등록된다() throws Exception {
        Theme retiredTheme = themeRepository.save(new Theme("retired_gacha_theme", "비활성 머신 테마", null, true));
        Item item = itemRepository.save(new Item(
                retiredTheme, "furniture", "positioned", null, null,
                "비활성 머신 가구", CurrencyType.COIN, 100, "items/retired-gacha/lamp.png", false, true));
        Long retiredGachaId = insertGacha(retiredTheme.getId(), "retired_gacha", "비활성 머신", false);
        insertItemPoolEntry(retiredGachaId, item.getId(), "일반");

        // 비활성 머신의 잔존 엔트리만 있으면 목록에서 미등록으로 표시
        mockMvc.perform(get("/admin/items/slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/retired-gacha/lamp.png')].rarityEditable")
                        .value(false));

        mockMvc.perform(put("/admin/items/{itemId}/rarity", item.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rarity\": \"희귀\"}").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rarity").value("희귀"))
                .andExpect(jsonPath("$.rarityEditable").value(true));

        // 비활성 머신의 엔트리는 건드리지 않고, 새 활성 머신에 등록된다
        String retiredRarity = jdbcTemplate.queryForObject(
                "SELECT rarity FROM gacha_pool_entries WHERE gacha_id = ?", String.class, retiredGachaId);
        assertThat(retiredRarity).isEqualTo("일반");

        Integer activeGachaCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gacha WHERE theme_id = ? AND is_active = TRUE", Integer.class,
                retiredTheme.getId());
        assertThat(activeGachaCount).isEqualTo(1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 비활성_아이템이나_비활성_테마는_뽑기_풀에_등록할_수_없다() throws Exception {
        Item inactiveItem = itemRepository.save(new Item(
                positionedItemWithoutPool.getTheme(), "furniture", "positioned", null, null,
                "비활성 가구", CurrencyType.COIN, 100, "items/slot-test/inactive-chair.png", false, false));

        Theme inactiveTheme = themeRepository.save(new Theme("inactive_theme", "비활성 테마", null, false));
        Item itemOfInactiveTheme = itemRepository.save(new Item(
                inactiveTheme, "furniture", "positioned", null, null,
                "비활성 테마 가구", CurrencyType.COIN, 100, "items/slot-test/inactive-theme-chair.png",
                false, true));

        for (Item target : new Item[]{inactiveItem, itemOfInactiveTheme}) {
            mockMvc.perform(put("/admin/items/{itemId}/rarity", target.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rarity\": \"희귀\"}").with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ITEM_RARITY_INVALID"));

            Integer entryCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM gacha_pool_entries WHERE item_id = ?", Integer.class,
                    target.getId());
            assertThat(entryCount).isZero();
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 미등록_아이템도_허용되지_않은_등급은_400이고_등록되지_않는다() throws Exception {
        mockMvc.perform(put("/admin/items/{itemId}/rarity", positionedItemWithoutPool.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rarity\": \"EPIC\"}").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ITEM_RARITY_INVALID"));

        Integer entryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gacha_pool_entries WHERE item_id = ?", Integer.class,
                positionedItemWithoutPool.getId());
        assertThat(entryCount).isZero();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 등급_변경은_CSRF_토큰이_필요하다() throws Exception {
        mockMvc.perform(put("/admin/items/{itemId}/rarity", positionedItem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rarity\": \"희귀\"}"))
                .andExpect(status().isForbidden());

        String rarity = jdbcTemplate.queryForObject("""
                SELECT rarity FROM gacha_pool_entries
                WHERE item_id = ? AND reward_type = 'ITEM' AND is_active = TRUE
                """, String.class, positionedItem.getId());
        assertThat(rarity).isEqualTo("일반");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 가구_크기_스튜디오와_실제_룸_미리보기가_렌더링된다() throws Exception {
        mockMvc.perform(get("/item-slots"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("가구 크기 스튜디오")))
                .andExpect(content().string(containsString("라이브 룸 미리보기")))
                .andExpect(content().string(containsString("모바일 Room renderer contract 불러오는 중")))
                .andExpect(content().string(containsString("/contracts/room-render-contract.v1.json")))
                .andExpect(content().string(containsString("/admin/items/surfaces")))
                .andExpect(content().string(containsString("surfaces = surfaceList.items")))
                .andExpect(content().string(containsString("/admin/characters")))
                .andExpect(content().string(containsString("validateRoomContract")))
                .andExpect(content().string(containsString("renderReferenceFixture")))
                .andExpect(content().string(containsString("selectPreviewTheme(item.themeCode)")))
                .andExpect(content().string(containsString("room-background")))
                .andExpect(content().string(containsString("room-wallpaper")))
                .andExpect(content().string(containsString("room-character")))
                .andExpect(content().string(containsString("preview-furniture-layer")))
                .andExpect(content().string(containsString("FREE_V1")))
                .andExpect(content().string(containsString("SLOT_V1")))
                .andExpect(content().string(containsString("/render-defaults")))
                .andExpect(content().string(containsString("const draftDefaults = new Map()")))
                .andExpect(content().string(containsString("let scenePlacements = []")))
                .andExpect(content().string(containsString("let activePreviewTab = 'free'")))
                .andExpect(content().string(containsString("beginFurnitureDrag")))
                .andExpect(content().string(containsString("setPointerCapture")))
                .andExpect(content().string(containsString("position-x")))
                .andExpect(content().string(containsString("newPlacementCenter")))
                .andExpect(content().string(containsString("선택 가구 추가")))
                .andExpect(content().string(containsString("scene-load-theme")))
                .andExpect(content().string(containsString("loadSelectedThemeScene")))
                .andExpect(content().string(containsString("themeGridPositions")))
                .andExpect(content().string(containsString("테마 전체로 교체")))
                .andExpect(content().string(containsString("FREE 미리보기 방향")))
                .andExpect(content().string(containsString("rotation-number")))
                .andExpect(content().string(containsString("flip-horizontal")))
                .andExpect(content().string(containsString("placementTransform")))
                .andExpect(content().string(containsString("toggleSelectedFlip")))
                .andExpect(content().string(containsString("rotatedWidthRatio")))
                .andExpect(content().string(containsString("renderPosition")))
                .andExpect(content().string(containsString("placement.rotationDeg")))
                .andExpect(content().string(containsString("미리보기 배치에만 적용")))
                .andExpect(content().string(containsString("모두 비우기")))
                .andExpect(content().string(containsString("container.setAttribute('inert', '')")))
                .andExpect(content().string(containsString("button.disabled = next")))
                .andExpect(content().string(containsString("selectedId === item.id")))
                .andExpect(content().string(containsString("activePreviewTab !== 'free'")))
                .andExpect(content().string(containsString("setControlsDisabled(!currentItem())")))
                .andExpect(content().string(containsString("translate(-50%, -50%) scale(")))
                .andExpect(content().string(containsString("다시 로그인해주세요")))
                .andExpect(content().string(containsString("fetchWithSession")))
                .andExpect(content().string(containsString("기본 크기는 새 FREE_V1에만 적용")))
                .andExpect(content().string(containsString("움짤만 보기")))
                .andExpect(content().string(containsString("뽑기 등급")))
                .andExpect(content().string(containsString("image-preview-dialog")))
                .andExpect(content().string(containsString("이미지 크게 보기")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 룸_미리보기는_사각_가이드_대신_선택_윤곽과_반전을_표현한다() throws Exception {
        mockMvc.perform(get("/css/admin.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(".room-furniture.selected img")))
                .andExpect(content().string(containsString("drop-shadow")))
                .andExpect(content().string(containsString(".room-furniture.flipped img")))
                .andExpect(content().string(containsString("transform: scaleX(-1)")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 모바일_Room_렌더_계약_JSON을_제공한다() throws Exception {
        mockMvc.perform(get("/contracts/room-render-contract.v1.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("rougether-room-renderer"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.furniture.baseWidth").value(0.28))
                .andExpect(jsonPath("$.furniture.slots.bottomLeft.width").value(0.24))
                .andExpect(jsonPath("$.character.width").value(0.42))
                .andExpect(jsonPath("$.referenceFixture.character.animations.idle")
                        .value("characters/cat/animations/idle.webp"));
    }

    @Test
    void 미인증이면_접근_불가() throws Exception {
        mockMvc.perform(get("/admin/items/slots"))
                .andExpect(status().is3xxRedirection());
    }

    private Long insertGacha(Long themeId, String code, String name) {
        return insertGacha(themeId, code, name, true);
    }

    private Long insertGacha(Long themeId, String code, String name, boolean active) {
        jdbcTemplate.update("""
                INSERT INTO gacha
                    (code, name, cost_currency_type, cost_amount, draw_count,
                     starts_at, ends_at, is_active, created_at, updated_at, theme_id)
                VALUES (?, ?, 'COIN', 100, 1, NULL, NULL, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
                """, code, name, active, themeId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM gacha WHERE code = ?", Long.class, code);
    }

    private void insertItemPoolEntry(Long gachaId, Long itemId, String rarity) {
        insertItemPoolEntry(gachaId, itemId, rarity, true);
    }

    private void insertItemPoolEntry(Long gachaId, Long itemId, String rarity, boolean active) {
        jdbcTemplate.update("""
                INSERT INTO gacha_pool_entries
                    (gacha_id, reward_type, item_id, character_id, currency_type,
                     reward_amount, rarity, weight, is_active)
                VALUES (?, 'ITEM', ?, NULL, NULL, NULL, ?, 1, ?)
                """, gachaId, itemId, rarity, active);
    }
}
