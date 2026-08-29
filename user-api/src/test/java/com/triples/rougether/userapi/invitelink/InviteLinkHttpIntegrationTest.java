package com.triples.rougether.userapi.invitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.invite.entity.InviteLinkClick;
import com.triples.rougether.domain.invite.repository.InviteLinkClickRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// 초대 링크 공개 경로의 인증 경계와 커밋 경계 - 실제 SecurityFilterChain(permitAll)을 통과하고,
// @Transactional 없이 트랜잭션이 실제로 커밋되는 경로를 태운다. 랜딩의 판정 경로가 참여 트랜잭션에
// rollback-only 마킹을 남기면 커밋 시 UnexpectedRollbackException(500)이 나는데, 롤백되는
// @Transactional 테스트로는 원리적으로 잡을 수 없어서 여기서 고정한다. 커밋된 클릭 로그는 직접 지운다.
@SpringBootTest
@AutoConfigureMockMvc
class InviteLinkHttpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InviteLinkClickRepository clickRepository;

    private List<InviteLinkClick> clicksOf(String code) {
        return clickRepository.findAll().stream()
                .filter(click -> code.equals(click.getCode()))
                .toList();
    }

    private void deleteClicksOf(String code) {
        clickRepository.deleteAll(clicksOf(code));
    }

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
    void 형식은_맞지만_없는_집_코드도_커밋을_지나_200_랜딩과_무효_클릭_로그를_남긴다() throws Exception {
        // 회귀 방지: 판정에 예외를 던지는 참여 트랜잭션 호출(HouseJoinService.preview)을 쓰면
        // 예외를 삼켜도 rollback-only 로 커밋이 500 으로 터지고 클릭 로그도 사라진다.
        try {
            mockMvc.perform(get("/h/HXNE2345"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));

            List<InviteLinkClick> clicks = clicksOf("HXNE2345");
            assertThat(clicks).hasSize(1);
            assertThat(clicks.get(0).isValid()).isFalse();
        } finally {
            deleteClicksOf("HXNE2345");
        }
    }

    @Test
    void 형식은_맞지만_없는_친구_코드도_커밋을_지나_200_랜딩과_무효_클릭_로그를_남긴다() throws Exception {
        try {
            mockMvc.perform(get("/i/FXNE2345"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));

            List<InviteLinkClick> clicks = clicksOf("FXNE2345");
            assertThat(clicks).hasSize(1);
            assertThat(clicks.get(0).isValid()).isFalse();
        } finally {
            deleteClicksOf("FXNE2345");
        }
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
