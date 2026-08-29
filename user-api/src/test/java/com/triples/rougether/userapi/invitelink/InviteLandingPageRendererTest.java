package com.triples.rougether.userapi.invitelink;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.invite.entity.InviteLinkType;
import com.triples.rougether.userapi.invitelink.config.InviteLinkProperties;
import com.triples.rougether.userapi.invitelink.service.InviteLandingView;
import com.triples.rougether.userapi.invitelink.web.InviteLandingPageRenderer;
import java.util.List;
import org.junit.jupiter.api.Test;

// 랜딩 HTML 렌더링 계약 - 스토어 연결(Play referrer·클립보드 payload)과 표시 문자열 escape 를 본다.
// Spring 없이 도는 순수 단위 테스트.
class InviteLandingPageRendererTest {

    private static final InviteLinkProperties FULL = new InviteLinkProperties(
            "https://invite.rougether.test",
            "rougether",
            "com.triples.rougether",
            List.of("AA:BB"),
            "6740000000",
            "TESTTEAM99.com.triples.rougether");

    private static final InviteLinkProperties NO_STORE = new InviteLinkProperties(
            "https://invite.rougether.test", "rougether", null, null, null, null);

    @Test
    void 유효한_친구_초대는_코드와_스토어_연결이_모두_렌더된다() {
        String html = new InviteLandingPageRenderer(FULL)
                .render(InviteLandingView.valid(InviteLinkType.FRIEND, "ABCD2345", "소**"));

        assertThat(html).contains("소**님이 루게더에 초대했어요");
        assertThat(html).contains("ABCD2345");
        // Android 딥링크의 본체 - Play referrer 에 코드가 실려야 설치 후 Install Referrer 로 복원된다.
        assertThat(html).contains(
                "https://play.google.com/store/apps/details?id=com.triples.rougether"
                        + "&referrer=invite_type%3Dfriend%26invite_code%3DABCD2345");
        assertThat(html).contains("https://apps.apple.com/kr/app/id6740000000");
        // iOS deferred 경로 - 앱이 클립보드에서 식별하는 봉투 형식(모바일 파싱 계약).
        assertThat(html).contains("rougether-invite:friend:ABCD2345");
        // 스킴 라우트는 배포된 앱 계약(rougether://invite, rougether://join)을 따른다.
        assertThat(html).contains("rougether://invite?code=ABCD2345");
    }

    @Test
    void 무효_코드는_코드_섹션_없이_안내만_렌더되고_referrer_도_실리지_않는다() {
        String html = new InviteLandingPageRenderer(FULL)
                .render(InviteLandingView.invalid(InviteLinkType.FRIEND, "NOPE2345"));

        // CSS 정의는 항상 포함되므로 렌더된 마크업(class="...") 기준으로 부재를 확인한다.
        assertThat(html).contains("유효하지 않은 초대 링크예요");
        assertThat(html).doesNotContain("class=\"code-value\"");
        assertThat(html).doesNotContain("referrer=");
        assertThat(html).doesNotContain("NOPE2345");
    }

    @Test
    void 만료된_집_초대는_집_이름과_만료_안내를_렌더한다() {
        String html = new InviteLandingPageRenderer(FULL)
                .render(InviteLandingView.expired(InviteLinkType.HOUSE, "LNDX2345", "만료 테스트 집"));

        assertThat(html).contains("‘만료 테스트 집’ 집에 초대받았어요");
        assertThat(html).contains("초대코드가 만료됐어요");
        assertThat(html).doesNotContain("class=\"code-value\"");
    }

    @Test
    void 유효한_집_초대는_house_타입_payload_를_렌더한다() {
        String html = new InviteLandingPageRenderer(FULL)
                .render(InviteLandingView.valid(InviteLinkType.HOUSE, "LNDH2345", "아침 루틴 하우스"));

        assertThat(html).contains("‘아침 루틴 하우스’ 집에 초대받았어요");
        assertThat(html).contains("rougether-invite:house:LNDH2345");
        assertThat(html).contains("invite_type%3Dhouse%26invite_code%3DLNDH2345");
        assertThat(html).contains("rougether://join?code=LNDH2345");
    }

    @Test
    void 표시_이름은_HTML_escape_된다() {
        String html = new InviteLandingPageRenderer(FULL)
                .render(InviteLandingView.valid(InviteLinkType.FRIEND, "ABCD2345", "<b>x"));

        // 집 이름·닉네임은 사용자 입력 - 공개 페이지에 그대로 꽂히면 XSS.
        assertThat(html).contains("&lt;b&gt;x님이");
        assertThat(html).doesNotContain("<b>x님이");
    }

    @Test
    void 스토어_미설정_환경은_버튼_대신_검색_안내를_렌더한다() {
        String html = new InviteLandingPageRenderer(NO_STORE)
                .render(InviteLandingView.valid(InviteLinkType.FRIEND, "ABCD2345", null));

        assertThat(html).contains("친구가 루게더에 초대했어요");
        assertThat(html).contains("스토어에서 ‘루게더’를 검색해 설치해 주세요");
        assertThat(html).doesNotContain("class=\"store-btn");
    }
}
