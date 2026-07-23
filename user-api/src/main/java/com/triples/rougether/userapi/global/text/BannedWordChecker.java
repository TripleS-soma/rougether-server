package com.triples.rougether.userapi.global.text;

import com.triples.rougether.common.text.TextNormalizer;
import com.triples.rougether.domain.moderation.entity.BannedWord;
import com.triples.rougether.domain.moderation.repository.BannedWordRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 금칙어 판정 (#209). 정규화(TextNormalizer) 후 부분 문자열 포함 매칭.
// 목록은 TTL 5분 메모리 캐시 - 어드민 갱신이 최대 5분 지연 반영되는 것을 허용(멀티 프로세스라 즉시 무효화 불가).
@Slf4j
@Component
public class BannedWordChecker {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final BannedWordRepository bannedWordRepository;

    private volatile List<String> cachedWords = List.of();
    private volatile Instant cacheLoadedAt = Instant.EPOCH;

    public BannedWordChecker(BannedWordRepository bannedWordRepository) {
        this.bannedWordRepository = bannedWordRepository;
    }

    public boolean containsBannedWord(String text) {
        String normalized = TextNormalizer.normalize(text);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String word : loadWords()) {
            if (normalized.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private List<String> loadWords() {
        if (Instant.now().isAfter(cacheLoadedAt.plus(CACHE_TTL))) {
            // 동시 갱신은 중복 조회일 뿐이라 락 없이 last-write-wins 로 충분함
            List<String> loaded = bannedWordRepository.findAll().stream()
                    .map(BannedWord::getWord)
                    .toList();
            if (loaded.isEmpty()) {
                // fail-open 설계라 목록이 비면 전부 통과 - 시드 미적재를 조용히 지나치지 않게 남긴다
                log.warn("금칙어 목록이 비어 있음 - 모든 텍스트가 통과됨 (seed-banned-words.sh 적재 여부 확인)");
            }
            cachedWords = loaded;
            cacheLoadedAt = Instant.now();
        }
        return cachedWords;
    }
}
