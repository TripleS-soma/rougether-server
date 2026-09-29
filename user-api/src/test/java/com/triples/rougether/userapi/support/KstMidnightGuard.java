package com.triples.rougether.userapi.support;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

// 테스트 클래스가 KST 자정 직전에 시작하면 자정이 지날 때까지 기다림.
// 클래스 로딩 시 실제 시계로 오늘(TODAY)을 고정하는 테스트는, 서비스가 호출 시점의 실제 시계(LocalDate.now(KST))를
// 읽기 때문에 실행 도중 자정을 넘기면 두 날짜가 어긋나 간헐 실패함. 정적 필드 초기화는 이 콜백 이후에 일어나므로
// 기다린 뒤 고정된 TODAY 와 서비스의 오늘이 같은 날이 됨. 근본 해결은 서비스의 Clock 주입(후속).
public class KstMidnightGuard implements BeforeAllCallback {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 한 테스트 클래스가 끝나기에 충분한 여유
    private static final Duration WINDOW = Duration.ofMinutes(3);

    @Override
    public void beforeAll(ExtensionContext context) throws InterruptedException {
        ZonedDateTime now = ZonedDateTime.now(KST);
        Duration untilMidnight = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(KST));
        if (untilMidnight.compareTo(WINDOW) < 0) {
            Thread.sleep(untilMidnight.toMillis() + 1_000);
        }
    }
}
