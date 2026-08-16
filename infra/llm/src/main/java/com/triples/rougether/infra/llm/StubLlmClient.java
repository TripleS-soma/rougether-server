package com.triples.rougether.infra.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

// llm.api-key 미설정 환경(로컬·CI)용 stub. 실제 호출 없이 고정 JSON을 돌려줘 배치·테스트가 외부 API 없이 돈다.
// isAvailable()=false 라 회고처럼 결과를 영구 저장하는 배치는 트리거 단계에서 보류된다(fail-closed).
// prod 프로파일에서 stub 이 뜨면 error 로그만 남긴다 — 예외로 앱을 죽이면 다른 배치(리마인더·day-end)까지 같이 죽는다.
@ConditionalOnExpression("!T(org.springframework.util.StringUtils).hasText('${llm.api-key:}')")
@Component
public class StubLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(StubLlmClient.class);

    // 주간 회고 파서가 그대로 받아들일 수 있는 최소 스키마. 다른 용도의 호출자는 자기 파서 기준으로 해석한다.
    static final String STUB_RESPONSE = """
            {"summary":"[stub] 이번 주 회고입니다.","highlights":["[stub] 잘한 점"],\
            "failurePatterns":["[stub] 실패 패턴"],"suggestions":["[stub] 다음 주 제안"]}""";

    public StubLlmClient(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            log.error("prod에서 LLM stub이 활성화됨 — LLM_API_KEY가 빈 값인지 확인 필요(LLM 의존 기능은 보류됨)");
        }
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String complete(LlmChatRequest request) {
        log.debug("[stub] LLM 호출 생략 - userPrompt {}자", request.userPrompt().length());
        return STUB_RESPONSE;
    }
}
