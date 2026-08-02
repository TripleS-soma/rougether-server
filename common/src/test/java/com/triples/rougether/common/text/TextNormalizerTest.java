package com.triples.rougether.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextNormalizerTest {

    @Test
    void 특수문자와_공백을_제거해_끼워넣기_우회를_무력화한다() {
        assertThat(TextNormalizer.normalize("시@발")).isEqualTo("시발");
        assertThat(TextNormalizer.normalize("시 발")).isEqualTo("시발");
        assertThat(TextNormalizer.normalize("시.발.놈")).isEqualTo("시발놈");
    }

    @Test
    void 대문자는_소문자로_숫자는_유지한다() {
        assertThat(TextNormalizer.normalize("FUck")).isEqualTo("fuck");
        assertThat(TextNormalizer.normalize("시1발")).isEqualTo("시1발");
    }

    @Test
    void 한글_자모도_유지되고_저장과_판정이_같은_형태가_된다() {
        // NFKC 가 호환 자모를 조합형으로 접지만, 등록·판정이 같은 normalize 를 쓰므로 매칭은 일관된다
        assertThat(TextNormalizer.normalize("ㅅ ㅂ")).isNotEmpty()
                .isEqualTo(TextNormalizer.normalize("ㅅㅂ"));
        assertThat(TextNormalizer.normalize("ㅗ!ㅗ")).isNotEmpty()
                .isEqualTo(TextNormalizer.normalize("ㅗㅗ"));
    }

    @Test
    void 전각문자와_NFD_분해_한글도_정규화된다() {
        assertThat(TextNormalizer.normalize("ｓｉｂａｌ")).isEqualTo("sibal");
        assertThat(TextNormalizer.normalize("１８놈")).isEqualTo("18놈");
        // NFD 로 분해된 "시발" (조합형 자모) 도 NFKC 재결합으로 매칭 형태가 된다
        assertThat(TextNormalizer.normalize(java.text.Normalizer.normalize("시발", java.text.Normalizer.Form.NFD)))
                .isEqualTo("시발");
    }

    @Test
    void 이모지와_특수문자만_있으면_빈_문자열이다() {
        assertThat(TextNormalizer.normalize("★☆!!")).isEmpty();
        assertThat(TextNormalizer.normalize(null)).isEmpty();
        assertThat(TextNormalizer.normalize("  ")).isEmpty();
    }
}
