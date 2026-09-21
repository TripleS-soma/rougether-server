package com.triples.rougether.userapi.global.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.MemberRole;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CatalogLocalizationIntegrationTest {
    // 이 테스트의 새 회원은 외부 트랜잭션 미커밋 상태다. 독립 트랜잭션인 활동 집계는
    // FK 잠금을 기다리므로 응답 언어 검증과 무관한 관측 부수효과만 대역 처리한다.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.triples.rougether.userapi.activity.service.UserDailyActivityRecorder activityRecorder;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ItemRepository items;
    @Autowired ThemeRepository themes;
    @Autowired TokenService tokens;
    @Autowired EntityManager em;

    @Test
    void 요청마다_언어가_분리되고_JSON_번역은_재조회해도_유지되며_ID와_key는_같다() throws Exception {
        User user = users.save(User.signUp());
        Theme theme = themes.save(new Theme("translation-test", "우리 테마", "themes/same.png", true));
        theme.updateNameTranslations(Map.of("en", "Our theme"));
        Item item = items.save(new Item(theme, "furniture", "positioned", null, null, "나무 의자",
                CurrencyType.DIAMOND, 10, "items/same.png", false, true));
        item.updateNameTranslations(Map.of("en", "Wooden chair"));
        Long id = item.getId();
        em.flush(); em.clear();
        assertThat(items.findById(id).orElseThrow().getNameTranslations()).containsEntry("en", "Wooden chair");
        String token = tokens.issueAccessToken(user.getId(), MemberRole.NORMAL);
        for (String language : List.of("en-US,en;q=0.9", "ko-KR", "en")) {
            boolean english = language.startsWith("en");
            mvc.perform(get("/api/v1/items").param("themeId", theme.getId().toString()).header("Authorization", "Bearer " + token).header("Accept-Language", language))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id))
                    .andExpect(jsonPath("$.items[0].assetKey").value("items/same.png"))
                    .andExpect(jsonPath("$.items[0].name").value(english ? "Wooden chair" : "나무 의자"))
                    .andExpect(jsonPath("$.items[0].theme.name").value(english ? "Our theme" : "우리 테마"));
        }
        mvc.perform(get("/api/v1/items").param("themeId", theme.getId().toString()).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.items[0].name").value("나무 의자"));
    }
}
