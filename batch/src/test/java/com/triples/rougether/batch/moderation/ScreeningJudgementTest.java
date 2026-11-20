package com.triples.rougether.batch.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.moderation.entity.ContentReportReason;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScreeningJudgementTest {

    private static Map<String, Double> probs(double spam, double abuse, double none) {
        return Map.of("SPAM", spam, "ABUSE", abuse, "SEXUAL", 0.0, "VIOLENCE", 0.0, "PERSONAL_INFO", 0.0,
                "NONE", none);
    }

    @Test
    void 유해_확률은_1에서_NONE을_뺀_값이고_사유는_NONE_외_최고_항목이다() {
        ScreeningJudgement judgement = ScreeningJudgement.of(probs(0.15, 0.5, 0.35), 0.6);
        assertThat(judgement.harmProbability()).isEqualByComparingTo("0.6500");
        assertThat(judgement.topReason()).isEqualTo(ContentReportReason.ABUSE);
        assertThat(judgement.flagged()).isTrue();
        assertThat(judgement.detail()).isEqualTo("auto: ABUSE p=0.6500");
    }

    @Test
    void 기준_경계값은_포함하고_미만은_신고하지_않는다() {
        assertThat(ScreeningJudgement.of(probs(0.6, 0.0, 0.4), 0.6).flagged()).isTrue();
        assertThat(ScreeningJudgement.of(probs(0.59, 0.0, 0.41), 0.6).flagged()).isFalse();
    }

    @Test
    void 저장되는_4자리_반올림값으로_기준을_비교한다() {
        ScreeningJudgement judgement = ScreeningJudgement.of(probs(0.59996, 0.0, 0.40004), 0.6);
        assertThat(judgement.harmProbability()).isEqualByComparingTo("0.6000");
        assertThat(judgement.flagged()).isTrue();
    }

    @Test
    void 동률이면_정의_순서가_앞인_항목을_사유로_고른다() {
        assertThat(ScreeningJudgement.of(probs(0.3, 0.3, 0.4), 0.6).topReason()).isEqualTo(ContentReportReason.SPAM);
    }
}
