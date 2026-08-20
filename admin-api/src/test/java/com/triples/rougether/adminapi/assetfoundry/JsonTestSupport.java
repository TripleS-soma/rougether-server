package com.triples.rougether.adminapi.assetfoundry;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JsonTestSupport {

    private JsonTestSupport() {
    }

    static long longValue(String json, String field) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*(\\d+)")
                .matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("JSON 필드를 찾을 수 없습니다: " + field);
        }
        return Long.parseLong(matcher.group(1));
    }
}
