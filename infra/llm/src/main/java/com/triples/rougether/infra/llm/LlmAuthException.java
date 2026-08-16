package com.triples.rougether.infra.llm;

// 401/403 — API 키가 없거나 잘못됐거나 권한이 없는 경우. 재시도 대상이 아니며, 호출자는 이를 "일시 장애 → 폴백"이 아니라
// "설정 장애 → 중단(나중에 재실행)"으로 다뤄야 한다. 폴백으로 흡수하면 전 사용자에게 열화된 결과가 정본으로 남는다.
public class LlmAuthException extends LlmException {

    public LlmAuthException(String message, Throwable cause) {
        super(message, false, cause);
    }
}
