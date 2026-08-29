package com.triples.rougether.userapi.invitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.invite.service.InviteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// 초대 링크 연결값 미설정 환경(스토어 등록 전·로컬)의 단계적 축소 동작.
// 서버는 정상 기동하되 shareUrl 은 null, well-known 은 404 여야 한다.
@SpringBootTest(properties = {
        "invite.link.share-base-url=",
        "invite.link.android-package=",
        "invite.link.android-cert-fingerprints=",
        "invite.link.appstore-id=",
        "invite.link.apple-app-id="
})
@AutoConfigureMockMvc
@Transactional
class InviteLinkUnconfiguredTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private InviteService inviteService;
    @Autowired private UserRepository userRepository;

    @Test
    void base_URL_미설정이면_shareUrl_은_null_이다() {
        User user = userRepository.save(User.signUp("unconfigured-share@rougether.dev"));

        assertThat(inviteService.getMyCode(user.getId()).shareUrl()).isNull();
    }

    @Test
    void 연결값_미설정이면_well_known_은_404_다() throws Exception {
        // 자리표시자 검증 파일을 배포하는 것보다 명시적 부재가 낫다 - OS 링크 검증이 어중간하게 걸리지 않게.
        mockMvc.perform(get("/.well-known/apple-app-site-association"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/.well-known/assetlinks.json"))
                .andExpect(status().isNotFound());
    }
}
