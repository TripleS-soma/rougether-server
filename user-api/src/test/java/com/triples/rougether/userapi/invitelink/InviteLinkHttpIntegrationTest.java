package com.triples.rougether.userapi.invitelink;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// 초대 링크 공개 경로의 인증 경계 - 실제 SecurityFilterChain(permitAll)을 통과하는지 본다.
// @Transactional 없이 커밋되는 테스트라 DB 를 쓰지 않는 요청만 쓴다
// (형식 밖 코드는 클릭 로그를 남기지 않는다 - InviteLandingIntegrationTest 검증 사항).
@SpringBootTest
@AutoConfigureMockMvc
class InviteLinkHttpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 친구_초대_랜딩은_비인증으로_열린다() throws Exception {
        mockMvc.perform(get("/i/ab"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    void 집_초대_랜딩은_비인증으로_열린다() throws Exception {
        mockMvc.perform(get("/h/ab"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    void apple_well_known_은_설정값으로_서빙된다() throws Exception {
        mockMvc.perform(get("/.well-known/apple-app-site-association"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.applinks.details[0].appIDs[0]")
                        .value("TESTTEAM99.com.triples.rougether"))
                .andExpect(jsonPath("$.applinks.details[0].components[0]['/']").value("/i/*"))
                .andExpect(jsonPath("$.applinks.details[0].components[1]['/']").value("/h/*"));
    }

    @Test
    void assetlinks_는_설정값으로_서빙된다() throws Exception {
        mockMvc.perform(get("/.well-known/assetlinks.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].target.package_name").value("com.triples.rougether"))
                .andExpect(jsonPath("$[0].relation[0]").value("delegate_permission/common.handle_all_urls"));
    }

    @Test
    void 초대_API_는_여전히_인증이_필요하다() throws Exception {
        // permitAll 추가가 기존 인증 경계를 넓히지 않았는지 대조군으로 확인.
        mockMvc.perform(get("/api/v1/invites/me"))
                .andExpect(status().isUnauthorized());
    }
}
