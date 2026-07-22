package com.triples.rougether.adminapi.itemslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

        jdbcTemplate.update("""
                INSERT INTO items
                    (theme_id, category_code, placement_type, surface_slot_type, character_slot_type,
                     name, purchase_currency_type, price_amount, asset_key, is_limited, is_active)
                VALUES (?, 'furniture', 'positioned', NULL, NULL,
                        'DB 기본 배율 의자', 'COIN', 100, 'items/slot-test/db-default-chair.png', FALSE, TRUE)
                """, positionedItem.getTheme().getId());

        BigDecimal databaseDefault = jdbcTemplate.queryForObject(
                "SELECT default_scale FROM items WHERE asset_key = 'items/slot-test/db-default-chair.png'",
                BigDecimal.class);
        assertThat(databaseDefault).isEqualByComparingTo("1.00");

        mockMvc.perform(get("/admin/items/slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/chair.png')].defaultScale")
                        .value(1.0));
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
    void 활성_뽑기_풀에_없는_아이템은_등급을_지정할_수_없다() throws Exception {
        mockMvc.perform(put("/admin/items/{itemId}/rarity", positionedItemWithoutPool.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rarity\": \"희귀\"}").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ITEM_RARITY_INVALID"));
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
                .andExpect(content().string(containsString("모바일 Room.tsx geometry 동기화")))
                .andExpect(content().string(containsString("SLOT_META")))
                .andExpect(content().string(containsString("free-preview-anchor")))
                .andExpect(content().string(containsString("FREE_V1")))
                .andExpect(content().string(containsString("SLOT_V1")))
                .andExpect(content().string(containsString("/default-scale")))
                .andExpect(content().string(containsString("container.setAttribute('inert', '')")))
                .andExpect(content().string(containsString("button.disabled = next")))
                .andExpect(content().string(containsString("selectedId === item.id")))
                .andExpect(content().string(containsString("const draftScales = new Map()")))
                .andExpect(content().string(containsString("setControlsDisabled(!currentItem())")))
                .andExpect(content().string(containsString("previewFurniture.style.width = baseWidth + '%'")))
                .andExpect(content().string(containsString("previewFurniture.style.transform = freeMode")))
                .andExpect(content().string(containsString("기본 크기는 새 FREE_V1에만 적용")))
                .andExpect(content().string(containsString("움짤만 보기")))
                .andExpect(content().string(containsString("뽑기 등급")))
                .andExpect(content().string(containsString("image-preview-dialog")))
                .andExpect(content().string(containsString("이미지 크게 보기")));
    }

    @Test
    void 미인증이면_접근_불가() throws Exception {
        mockMvc.perform(get("/admin/items/slots"))
                .andExpect(status().is3xxRedirection());
    }

    private Long insertGacha(Long themeId, String code, String name) {
        jdbcTemplate.update("""
                INSERT INTO gacha
                    (code, name, cost_currency_type, cost_amount, draw_count,
                     starts_at, ends_at, is_active, created_at, updated_at, theme_id)
                VALUES (?, ?, 'COIN', 100, 1, NULL, NULL, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
                """, code, name, themeId);
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
