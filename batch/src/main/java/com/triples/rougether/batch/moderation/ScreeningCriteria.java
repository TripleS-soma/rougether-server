package com.triples.rougether.batch.moderation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

// Jev choice 질문 정의(#478). 유해 항목 이름은 ContentReportReason 값과 같게 둬 신고 사유로 그대로 씀.
// 설명은 한국어 커뮤니티 맥락으로 씀 — 가벼운 반말·인터넷 말투 자체는 유해가 아님을 NONE 에 명시.
final class ScreeningCriteria {

    static final String QUESTION = "harm";
    static final String NONE = "NONE";

    static final String INSTRUCTIONS = "한국어 습관 앱 커뮤니티에 사용자가 올린 글이다. state.text 가 아래 유해 유형 중 무엇에 "
            + "해당하는지 하나를 고른다. state.context 가 있으면 댓글이 달린 원 게시글이며 판단 참고용이다.";

    static final Map<String, String> CRITERIA = criteria();

    private ScreeningCriteria() {
    }

    private static Map<String, String> criteria() {
        Map<String, String> criteria = new LinkedHashMap<>();
        criteria.put("SPAM", "광고·홍보·도배, 외부 링크나 연락처로 유도하는 글");
        criteria.put("ABUSE", "욕설·비하·혐오 표현, 특정인을 괴롭히거나 조롱하는 글");
        criteria.put("SEXUAL", "성적인 묘사, 성희롱, 음란한 내용");
        criteria.put("VIOLENCE", "폭력·위협, 자해나 범죄를 조장하는 글");
        criteria.put("PERSONAL_INFO", "전화번호·주소·계좌번호·실명 등 본인이나 타인의 개인정보를 드러내는 글");
        criteria.put(NONE, "위에 해당하지 않는 일상 대화·잡담·질문. 가벼운 반말이나 인터넷 말투도 여기에 해당");
        // 정의 순서를 유지해야 함(요청의 항목 순서·동률 사유 선택). Map.copyOf 는 순서를 보장하지 않음.
        return Collections.unmodifiableMap(criteria);
    }
}
