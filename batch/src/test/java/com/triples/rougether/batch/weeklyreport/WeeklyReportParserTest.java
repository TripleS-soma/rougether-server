package com.triples.rougether.batch.weeklyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WeeklyReportParserTest {

    private final WeeklyReportParser parser = new WeeklyReportParser();

    @Test
    void 정상_JSON을_summary와_섹션으로_파싱한다() {
        String content = """
                {"summary":"이번 주 잘했어요.","highlights":["매일 달리기"],
                 "failurePatterns":["목요일 실패"],"suggestions":["알람 설정"]}""";

        WeeklyReportParser.Parsed parsed = parser.parse(content);

        assertThat(parsed.summary()).isEqualTo("이번 주 잘했어요.");
        assertThat(parsed.sections().highlights()).containsExactly("매일 달리기");
        assertThat(parsed.sections().failurePatterns()).containsExactly("목요일 실패");
        assertThat(parsed.sections().suggestions()).containsExactly("알람 설정");
    }

    @Test
    void 코드펜스나_앞뒤_설명이_붙어도_JSON_본문만_뽑는다() {
        String content = "물론이죠! ```json\n{\"summary\":\"요약\",\"highlights\":[]}\n``` 참고하세요.";

        WeeklyReportParser.Parsed parsed = parser.parse(content);

        assertThat(parsed.summary()).isEqualTo("요약");
        assertThat(parsed.sections().highlights()).isEmpty();
    }

    @Test
    void 섹션이_없거나_배열이_아니면_빈_배열로_수용한다() {
        WeeklyReportParser.Parsed parsed = parser.parse("{\"summary\":\"요약\",\"highlights\":\"문자열\"}");

        assertThat(parsed.sections().highlights()).isEmpty();
        assertThat(parsed.sections().failurePatterns()).isEmpty();
        assertThat(parsed.sections().suggestions()).isEmpty();
    }

    @Test
    void 개수와_길이_초과는_잘라서_수용하고_빈_항목은_버린다() {
        String longItem = "가".repeat(WeeklyReportPromptBuilder.SECTION_ITEM_MAX_CHARS + 20);
        String longSummary = "나".repeat(WeeklyReportPromptBuilder.SUMMARY_MAX_CHARS + 50);
        String content = "{\"summary\":\"" + longSummary + "\",\"suggestions\":[\"a\",\" \",\"b\",\"c\",\"d\",\""
                + longItem + "\"]}";

        WeeklyReportParser.Parsed parsed = parser.parse(content);

        assertThat(parsed.summary()).hasSize(WeeklyReportPromptBuilder.SUMMARY_MAX_CHARS);
        assertThat(parsed.sections().suggestions()).containsExactly("a", "b", "c");
    }

    @Test
    void summary가_없거나_비면_InvalidResponseException() {
        assertThatThrownBy(() -> parser.parse("{\"highlights\":[\"x\"]}"))
                .isInstanceOf(WeeklyReportParser.InvalidResponseException.class);
        assertThatThrownBy(() -> parser.parse("{\"summary\":\"   \"}"))
                .isInstanceOf(WeeklyReportParser.InvalidResponseException.class);
    }

    @Test
    void JSON이_아니거나_비어_있으면_InvalidResponseException() {
        assertThatThrownBy(() -> parser.parse("죄송해요, 회고를 쓸 수 없어요."))
                .isInstanceOf(WeeklyReportParser.InvalidResponseException.class);
        assertThatThrownBy(() -> parser.parse("{summary: 잘못된 json}"))
                .isInstanceOf(WeeklyReportParser.InvalidResponseException.class);
        assertThatThrownBy(() -> parser.parse("[\"배열\"]"))
                .isInstanceOf(WeeklyReportParser.InvalidResponseException.class);
        assertThatThrownBy(() -> parser.parse(""))
                .isInstanceOf(WeeklyReportParser.InvalidResponseException.class);
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOf(WeeklyReportParser.InvalidResponseException.class);
    }
}
