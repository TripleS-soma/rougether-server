package com.triples.rougether.infra.llm;

// system/user 두 메시지로 구성한 단발 채팅 요청. maxTokens·temperature가 null이면 클라이언트 설정 기본값을 쓴다.
public record LlmChatRequest(String systemPrompt, String userPrompt, Integer maxTokens, Double temperature) {

    public LlmChatRequest {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("userPrompt는 비어 있을 수 없습니다");
        }
    }

    public static LlmChatRequest of(String systemPrompt, String userPrompt) {
        return new LlmChatRequest(systemPrompt, userPrompt, null, null);
    }
}
