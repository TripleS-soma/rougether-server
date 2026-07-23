package com.triples.rougether.common.text;

import java.text.Normalizer;
import java.util.Locale;

// 금칙어 매칭용 정규화 (#209). 저장(어드민 등록)과 판정(user-api)이 같은 규칙을 쓰도록 common 에 둔다.
// 규칙: NFKC(전각→반각, 자모 결합) 후 소문자화, 한글(완성형·자모)/영문/숫자 외 문자를 전부 제거
// - 공백·특수문자·이모지 끼워넣기와 전각문자(ｓｉｂａｌ)·NFD 분해 한글 우회를 무력화.
public final class TextNormalizer {

    private TextNormalizer() {
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder normalized = new StringBuilder(folded.length());
        for (char c : folded.toLowerCase(Locale.ROOT).toCharArray()) {
            if (isKorean(c) || isAlphaNumeric(c)) {
                normalized.append(c);
            }
        }
        return normalized.toString();
    }

    private static boolean isKorean(char c) {
        // 완성형 + 호환 자모 + 조합형 자모(U+1100~U+11FF — NFKC 가 호환 자모를 조합형으로 접기 때문에 필요)
        return (c >= '가' && c <= '힣') || (c >= 'ㄱ' && c <= 'ㅣ') || (c >= 'ᄀ' && c <= 'ᇿ');
    }

    private static boolean isAlphaNumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
