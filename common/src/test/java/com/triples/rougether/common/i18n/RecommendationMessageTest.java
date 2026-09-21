package com.triples.rougether.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecommendationMessageTest {
    @Test
    void 코드와_매개변수로_번역하고_사용자_제목은_보존한다() {
        var params = Map.<String,Object>of("title", "독서 %s", "days", List.of("MON", "THU"), "to", 2);
        assertThat(RecommendationMessage.render("ADJUST_REDUCE_DAYS", params, Language.EN, "원문"))
                .contains("독서 %s", "Monday, Thursday", "2 times", "40%");
        assertThat(RecommendationMessage.render("ADJUST_DROP_DAY", Map.of("title", "독서", "day", "WED"), Language.EN, "원문"))
                .contains("Wednesday", "독서");
        assertThat(RecommendationMessage.render(null, null, Language.EN, "기존 원문")).isEqualTo("기존 원문");
        assertThat(RecommendationMessage.render("ADJUST_REDUCE_DAYS", params, Language.KO, "한국어")).isEqualTo("한국어");
    }
}
