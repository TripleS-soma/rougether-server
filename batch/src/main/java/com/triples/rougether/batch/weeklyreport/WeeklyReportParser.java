package com.triples.rougether.batch.weeklyreport;

import com.triples.rougether.domain.report.WeeklyReportSections;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// LLM 응답(JSON 문자열)을 summary + 섹션으로 파싱·정규화한다. 코드펜스나 앞뒤 잡음은 관대하게 벗겨내되,
// summary 가 없거나 비어 있으면 InvalidResponseException 을 던져 호출자가 1회 재요청하게 한다.
// 길이·개수 초과는 실패가 아니라 잘라서 수용한다(모델이 제한을 어겨도 회고 자체는 살린다).
@Component
public class WeeklyReportParser {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public record Parsed(String summary, WeeklyReportSections sections) {
    }

    public static class InvalidResponseException extends RuntimeException {
        public InvalidResponseException(String message) {
            super(message);
        }

        public InvalidResponseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public Parsed parse(String content) {
        if (content == null || content.isBlank()) {
            throw new InvalidResponseException("LLM 응답이 비어 있음");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(extractJsonObject(content));
        } catch (RuntimeException e) {
            throw new InvalidResponseException("LLM 응답 JSON 파싱 실패", e);
        }
        if (!root.isObject()) {
            throw new InvalidResponseException("LLM 응답이 JSON 객체가 아님");
        }
        String summary = root.path("summary").asString("").strip();
        if (summary.isBlank()) {
            throw new InvalidResponseException("summary 누락");
        }
        return new Parsed(
                truncate(summary, WeeklyReportPromptBuilder.SUMMARY_MAX_CHARS),
                new WeeklyReportSections(
                        stringList(root.path("highlights")),
                        stringList(root.path("failurePatterns")),
                        stringList(root.path("suggestions"))));
    }

    // ```json ... ``` 같은 코드펜스나 앞뒤 설명이 붙어도 첫 '{'부터 마지막 '}'까지를 JSON 본문으로 본다.
    static String extractJsonObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new InvalidResponseException("LLM 응답에 JSON 객체가 없음");
        }
        return content.substring(start, end + 1);
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : node) {
            if (items.size() >= WeeklyReportPromptBuilder.SECTION_MAX_ITEMS) {
                break;
            }
            String text = item.isString() ? item.asString().strip() : "";
            if (!text.isBlank()) {
                items.add(truncate(text, WeeklyReportPromptBuilder.SECTION_ITEM_MAX_CHARS));
            }
        }
        return List.copyOf(items);
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
