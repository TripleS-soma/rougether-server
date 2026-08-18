package com.triples.rougether.common.text;

import java.text.Normalizer;
import java.util.Locale;

// 루틴·투두 제목 동일성 판정용 정규화 (#303 유사도 비교 EXACT 단계, #290 캡처 임포트 병합 규칙과 동일).
// 규칙: NFKC(전각→반각, 자모 결합) 후 소문자화, 공백 문자 전부 제거 — "물 마시기" == "물마시기" == "물　마시기".
// 구두점·이모지는 남긴다("PT!" != "PT") — 금칙어용 TextNormalizer 와 달리 제목은 있는 그대로 비교하고,
// 표기 차이는 임베딩 단계가 흡수한다.
public final class TitleNormalizer {

    private TitleNormalizer() {
    }

    public static String normalize(String title) {
        if (title == null) {
            return "";
        }
        String folded = Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) {
                normalized.append(c);
            }
        }
        return normalized.toString();
    }
}
