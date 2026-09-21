package com.triples.rougether.adminapi.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CatalogTranslationTest {
    @Autowired MockMvc mvc;
    @Autowired ThemeRepository themes;

    @Test
    @WithMockUser(roles = "ADMIN")
    void 번역을_관리해도_기본명과_에셋은_보존한다() throws Exception {
        var theme = themes.save(new Theme("i18n", "숲", "themes/forest.png", true));
        String url = "/admin/catalog/themes/" + theme.getId() + "/translations";
        mvc.perform(put(url).with(csrf()).contentType("application/json")
                        .content("{\"nameTranslations\":{\"en\":\"Forest\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("숲"))
                .andExpect(jsonPath("$.nameTranslations.en").value("Forest"));
        assertThat(theme.getCoverImageKey()).isEqualTo("themes/forest.png");
        mvc.perform(put(url).with(csrf()).contentType("application/json").content("{\"nameTranslations\":{}}"))
                .andExpect(status().isOk());
        assertThat(theme.nameIn("en")).isEqualTo("숲");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void CSRF와_지원언어_검증을_우회할_수_없다() throws Exception {
        String url = "/admin/catalog/themes/1/translations";
        mvc.perform(put(url).contentType("application/json").content("{\"nameTranslations\":{}}"))
                .andExpect(status().isForbidden());
        mvc.perform(put(url).with(csrf()).contentType("application/json").content("{\"nameTranslations\":{\"ja\":\"森\"}}"))
                .andExpect(status().isBadRequest());
    }
}
