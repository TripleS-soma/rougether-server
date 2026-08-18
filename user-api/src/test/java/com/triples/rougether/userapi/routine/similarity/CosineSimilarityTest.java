package com.triples.rougether.userapi.routine.similarity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class CosineSimilarityTest {

    @Test
    void 같은_방향은_1_직교는_0_반대는_minus1() {
        assertThat(CosineSimilarity.of(new float[] {1, 0}, new float[] {2, 0})).isCloseTo(1.0, within(1e-9));
        assertThat(CosineSimilarity.of(new float[] {1, 0}, new float[] {0, 3})).isCloseTo(0.0, within(1e-9));
        assertThat(CosineSimilarity.of(new float[] {1, 0}, new float[] {-1, 0})).isCloseTo(-1.0, within(1e-9));
        assertThat(CosineSimilarity.of(new float[] {1, 1}, new float[] {1, 0})).isCloseTo(Math.sqrt(0.5), within(1e-6));
    }

    @Test
    void 영벡터_null_길이불일치는_0으로_방어한다() {
        assertThat(CosineSimilarity.of(new float[] {0, 0}, new float[] {1, 0})).isZero();
        assertThat(CosineSimilarity.of(new float[] {1, 0}, new float[] {0, 0})).isZero();
        assertThat(CosineSimilarity.of(null, new float[] {1})).isZero();
        assertThat(CosineSimilarity.of(new float[] {1}, null)).isZero();
        assertThat(CosineSimilarity.of(new float[] {1, 0}, new float[] {1})).isZero();
        assertThat(CosineSimilarity.of(new float[] {}, new float[] {})).isZero();
    }
}
