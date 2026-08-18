package com.triples.rougether.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TitleNormalizerTest {

    @Test
    void 공백은_전부_제거하고_소문자로_만든다() {
        assertThat(TitleNormalizer.normalize("물 마시기")).isEqualTo("물마시기");
        assertThat(TitleNormalizer.normalize("  Morning  Run \t")).isEqualTo("morningrun");
        assertThat(TitleNormalizer.normalize("팀\n회의")).isEqualTo("팀회의");
    }

    @Test
    void 전각_공백과_전각_문자는_NFKC로_접는다() {
        assertThat(TitleNormalizer.normalize("물　마시기")).isEqualTo("물마시기");
        assertThat(TitleNormalizer.normalize("ＰＴ")).isEqualTo("pt");
        // NFD 로 분해된 한글도 NFKC 가 결합해 같은 값이 된다
        assertThat(TitleNormalizer.normalize("헬스")).isEqualTo("헬스");
    }

    @Test
    void 구두점과_이모지는_남긴다() {
        assertThat(TitleNormalizer.normalize("PT!")).isNotEqualTo(TitleNormalizer.normalize("PT"));
        assertThat(TitleNormalizer.normalize("운동🏃")).isEqualTo("운동🏃");
    }

    @Test
    void null은_빈_문자열() {
        assertThat(TitleNormalizer.normalize(null)).isEmpty();
    }
}
