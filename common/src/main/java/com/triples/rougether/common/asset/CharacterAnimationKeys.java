package com.triples.rougether.common.asset;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// 캐릭터 코드에서 파생되는 표준 애니메이션 key 규칙을 공유함
public record CharacterAnimationKeys(String idle, String poseCycle, String wave) {

    private static final String PREFIX = "characters/";
    private static final String DIRECTORY = "/animations/";
    private static final String IDLE = "idle.webp";
    private static final String POSE_CYCLE = "pose-cycle.webp";
    private static final String WAVE = "wave.webp";
    private static final Pattern KEY_PATTERN = Pattern.compile(
            "^" + Pattern.quote(PREFIX) + "([^/]+)" + Pattern.quote(DIRECTORY)
                    + "(?:" + List.of(IDLE, POSE_CYCLE, WAVE).stream()
                            .map(Pattern::quote).collect(Collectors.joining("|")) + ")$");

    public static CharacterAnimationKeys of(String code) {
        String base = PREFIX + code + DIRECTORY;
        return new CharacterAnimationKeys(base + IDLE, base + POSE_CYCLE, base + WAVE);
    }

    // 활성화 검사와 누락 안내에 사용하므로 순서를 유지함
    public List<String> all() {
        return List.of(idle, poseCycle, wave);
    }

    public static boolean matches(String key) {
        return characterCode(key).isPresent();
    }

    // 이름 규칙만 판별함. 요청별 안전한 경로 검증과 DB 참조 검사는 호출자가 수행함
    public static Optional<String> characterCode(String key) {
        if (key == null) {
            return Optional.empty();
        }
        var matcher = KEY_PATTERN.matcher(key);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
