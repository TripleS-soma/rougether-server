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

    private Item positionedItem;
    private Item surfaceItem;

    @BeforeEach
    void setUp() {
        Theme theme = themeRepository.save(new Theme("slot_test_theme", "슬롯 테스트 테마", null, true));
        positionedItem = itemRepository.save(new Item(
                theme, "furniture", "positioned", null, null,
                "테스트 의자", CurrencyType.COIN, 100, "items/slot-test/chair.png", false, true));
        surfaceItem = itemRepository.save(new Item(
                theme, "wallpaper", "surface_slot", "wallpaper", null,
                "테스트 벽지", CurrencyType.COIN, 100, "items/slot-test/wallpaper.png", false, true));
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
                .andExpect(jsonPath("$.items[?(@.assetKey == 'items/slot-test/wallpaper.png')]").isEmpty());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 슬롯_편집_페이지가_렌더링된다() throws Exception {
        mockMvc.perform(get("/item-slots"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("기본 슬롯 편집")));
    }

    @Test
    void 미인증이면_접근_불가() throws Exception {
        mockMvc.perform(get("/admin/items/slots"))
                .andExpect(status().is3xxRedirection());
    }
}
