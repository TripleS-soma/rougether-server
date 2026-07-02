package com.triples.rougether.adminapi.asset;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.adminapi.asset.service.AssetStorageService;
import com.triples.rougether.adminapi.asset.service.AssetSummary;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AssetBrowseTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AssetStorageService storage;

    @Test
    @WithMockUser(roles = "ADMIN")
    void kind별_에셋_목록을_내려준다() throws Exception {
        given(storage.list("items")).willReturn(List.of(
                new AssetSummary("items/bakery/chair.png", 1024L, Instant.EPOCH),
                new AssetSummary("items/bakery/table.png", 2048L, Instant.EPOCH)));

        mockMvc.perform(get("/admin/assets").param("kind", "items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].key").value("items/bakery/chair.png"))
                .andExpect(jsonPath("$.items[0].size").value(1024))
                .andExpect(jsonPath("$.items[1].key").value("items/bakery/table.png"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 허용안된_kind면_400() throws Exception {
        mockMvc.perform(get("/admin/assets").param("kind", "etc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 에셋_조회_페이지가_렌더링된다() throws Exception {
        mockMvc.perform(get("/assets"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("에셋 조회")));
    }

    @Test
    void 미인증이면_접근_불가() throws Exception {
        mockMvc.perform(get("/admin/assets").param("kind", "items"))
                .andExpect(status().is3xxRedirection());
    }
}
