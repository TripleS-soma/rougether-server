package com.triples.rougether.domain.report;

import java.util.List;

// weekly_reports.sections_json 의 스키마(LLM 생성 섹션). FALLBACK 회고는 세 배열 모두 비어 있다.
public record WeeklyReportSections(List<String> highlights, List<String> failurePatterns, List<String> suggestions) {

    public static WeeklyReportSections empty() {
        return new WeeklyReportSections(List.of(), List.of(), List.of());
    }
}
