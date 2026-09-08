package com.triples.rougether.userapi.contract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

// spec의 날짜 경계 fixture(date-boundary-cases.json)와 모바일이 기록한 실제 요청(date-boundary-requests.json) 로더.
// 기본은 classpath 복사본(src/test/resources/contracts), -Pcontracts.dir=<dir> 이면 그 디렉터리(교차 워크플로가
// spec/모바일 HEAD에서 새로 만든 파일)를 읽는다. 어느 쪽을 읽었는지는 source()로 남긴다
final class DateBoundaryContracts {

    static final String CASES_FILE = "date-boundary-cases.json";
    static final String REQUESTS_FILE = "date-boundary-requests.json";
    private static final String DIR_PROPERTY = "contracts.dir";

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    record Fixture(int schemaVersion, String zone, List<BoundaryCase> cases) {
    }

    record BoundaryCase(String id, String description, String instant, String deviceTimeZone,
                        String expectedDate, Naive naive) {
        @Override
        public String toString() {
            return id + " @ " + deviceTimeZone;
        }
    }

    record Naive(NaiveDate utcTruncated, NaiveDate deviceLocal) {
    }

    // verdict: TODAY / PAST / FUTURE — 서버가 이 날짜를 어떻게 판정해야 하는지(fixture 정본)
    record NaiveDate(String date, String verdict) {
    }

    record Recorded(String mobileSha, String mobileRef, Map<String, Object> spec, List<Run> runs) {
    }

    record Run(String tz, List<RecordedCase> records) {
    }

    record RecordedCase(String caseId, String instant, String expectedDate, String deviceTimeZone,
                        List<RecordedRequest> requests) {
        @Override
        public String toString() {
            return caseId + " @ " + deviceTimeZone;
        }
    }

    record RecordedRequest(String name, String method, String path, Map<String, Object> body) {
    }

    private DateBoundaryContracts() {
    }

    static String source() {
        String dir = System.getProperty(DIR_PROPERTY);
        return dir != null ? "dir " + dir : "classpath contracts/ (vendored copy)";
    }

    static Fixture loadFixture() {
        return read(CASES_FILE, Fixture.class);
    }

    static Recorded loadRecorded() {
        return read(REQUESTS_FILE, Recorded.class);
    }

    static byte[] toJson(Object value) {
        return MAPPER.writeValueAsBytes(value);
    }

    private static <T> T read(String file, Class<T> type) {
        try (InputStream in = open(file)) {
            return MAPPER.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("계약 파일 읽기 실패: " + file, e);
        }
    }

    private static InputStream open(String file) throws IOException {
        String dir = System.getProperty(DIR_PROPERTY);
        if (dir != null) {
            Path path = Path.of(dir, file);
            if (!Files.exists(path)) {
                throw new IllegalStateException("-Dcontracts.dir 에 파일이 없음: " + path);
            }
            return Files.newInputStream(path);
        }
        InputStream in = DateBoundaryContracts.class.getResourceAsStream("/contracts/" + file);
        if (in == null) {
            throw new IllegalStateException("classpath 에 contracts/" + file + " 이 없음");
        }
        return in;
    }
}
