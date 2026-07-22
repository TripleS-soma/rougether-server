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
    void 슬롯_편집_페이지가_렌더링된다() throws Exception {
        mockMvc.perform(get("/item-slots"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("가구 관리")))
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
