package com.triples.rougether.userapi.invitelink.web;

import com.triples.rougether.domain.invite.entity.InviteLinkType;
import com.triples.rougether.userapi.invitelink.config.InviteLinkProperties;
import com.triples.rougether.userapi.invitelink.service.InviteLandingView;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

// 랜딩 HTML 렌더러. 템플릿(invitelink/landing.html)의 자리표시자를 판정 결과·스토어 연결값으로 치환한다.
// 표시 문자열(닉네임·집 이름)은 HTML escape 하고, 코드는 발급 문자 집합([A-Z2-9])만 통과해 들어온다.
@Component
public class InviteLandingPageRenderer {

    private final InviteLinkProperties properties;
    private final String template;

    public InviteLandingPageRenderer(InviteLinkProperties properties) {
        this.properties = properties;
        this.template = loadTemplate();
    }

    // 순차 replace 는 치환 결과가 다음 치환의 입력이 된다. 사용자 입력(집 이름)이 들어가는
    // HEADLINE 을 마지막에 치환해, 자리표시자 모양의 이름이 다른 섹션으로 재치환되는 것을 막는다.
    public String render(InviteLandingView view) {
        return template
                .replace("{{CLIP_PAYLOAD}}", clipPayload(view))
                .replace("{{ACTION_SECTION}}", actionSection(view))
                .replace("{{CODE_SECTION}}", codeSection(view))
                .replace("{{SUBLINE}}", subline(view))
                .replace("{{HEADLINE}}", headline(view));
    }

    private String headline(InviteLandingView view) {
        String name = view.displayName() == null ? null : HtmlUtils.htmlEscape(view.displayName());
        if (view.type() == InviteLinkType.FRIEND) {
            if (!view.valid()) {
                return "루게더 초대";
            }
            return name == null ? "친구가 루게더에 초대했어요" : name + "님이 루게더에 초대했어요";
        }
        if (name != null && (view.valid() || view.expired())) {
            return "‘" + name + "’ 집에 초대받았어요";
        }
        return "루게더 집 초대";
    }

    private String subline(InviteLandingView view) {
        if (view.expired()) {
            return "초대코드가 만료됐어요. 초대한 친구에게 새 링크를 요청해 주세요.";
        }
        if (!view.valid()) {
            return "유효하지 않은 초대 링크예요. 초대한 친구에게 새 링크를 요청해 주세요.";
        }
        if (view.type() == InviteLinkType.FRIEND) {
            return "앱을 설치하고 초대코드를 입력하면 둘 다 코인을 받아요. 아래 버튼으로 설치하면 첫 실행에서 코드가 자동으로 입력돼요.";
        }
        return "앱을 설치하고 초대코드로 집에 참여해 보세요. 아래 버튼으로 설치하면 첫 실행에서 코드가 자동으로 입력돼요.";
    }

    private String codeSection(InviteLandingView view) {
        if (!view.valid()) {
            return "";
        }
        return """
                <div class="code-section">
                  <div class="code-label">초대코드</div>
                  <div class="code-box">
                    <span class="code-value">%s</span>
                    <button type="button" class="copy-btn" data-copy>복사</button>
                  </div>
                </div>""".formatted(HtmlUtils.htmlEscape(view.code()));
    }

    // 스토어 버튼: Android 는 Play referrer 로 코드를 실어 보내고(설치 후 Install Referrer 로 수신),
    // iOS 는 파라미터 전달 수단이 없어 클립보드(data-copy)로만 잇는다. 미설정 스토어 버튼은 숨긴다.
    private String actionSection(InviteLandingView view) {
        StringBuilder actions = new StringBuilder();
        boolean hasStoreButton = false;
        if (properties.hasAndroidPackage()) {
            actions.append("""
                    <a class="store-btn play" data-copy href="%s">Google Play에서 받기</a>""".formatted(playStoreUrl(view)));
            hasStoreButton = true;
        }
        if (properties.hasAppstoreId()) {
            actions.append("""
                    <a class="store-btn appstore" data-copy href="%s">App Store에서 받기</a>""".formatted(appStoreUrl()));
            hasStoreButton = true;
        }
        if (!hasStoreButton) {
            actions.append("""
                    <p class="store-fallback">스토어 설치 링크가 아직 연결되지 않았어요. 스토어에서 ‘루게더’를 검색해 설치해 주세요.</p>""");
        }
        if (view.valid() && properties.hasAppScheme()) {
            actions.append("""
                    <a class="app-open" href="%s">이미 설치했다면 앱에서 열기</a>""".formatted(appSchemeUrl(view)));
        }
        return actions.toString();
    }

    private String playStoreUrl(InviteLandingView view) {
        String url = "https://play.google.com/store/apps/details?id=" + properties.androidPackage();
        if (view.valid()) {
            String referrer = "invite_type=" + typeToken(view.type()) + "&invite_code=" + view.code();
            url += "&referrer=" + URLEncoder.encode(referrer, StandardCharsets.UTF_8);
        }
        return url;
    }

    private String appStoreUrl() {
        return "https://apps.apple.com/kr/app/id" + properties.appstoreId();
    }

    // 스킴 라우트는 배포된 앱의 기존 계약을 따른다 - rougether-mobile 의 src/app/invite.tsx(친구)·join.tsx(집).
    private String appSchemeUrl(InviteLandingView view) {
        String route = view.type() == InviteLinkType.FRIEND ? "invite" : "join";
        return properties.appScheme() + "://" + route + "?code=" + view.code();
    }

    // 앱이 클립보드에서 초대를 오인식 없이 식별하는 봉투 형식. 모바일 이슈의 파싱 규칙과 계약이다.
    private String clipPayload(InviteLandingView view) {
        if (!view.valid()) {
            return "";
        }
        return "rougether-invite:" + typeToken(view.type()) + ":" + view.code();
    }

    private String typeToken(InviteLinkType type) {
        return type == InviteLinkType.FRIEND ? "friend" : "house";
    }

    private static String loadTemplate() {
        try (var in = new ClassPathResource("invitelink/landing.html").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("초대 랜딩 템플릿을 읽지 못했습니다.", e);
        }
    }
}
