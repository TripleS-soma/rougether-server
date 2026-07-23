package com.triples.rougether.userapi.global.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.moderation.entity.BannedWord;
import com.triples.rougether.domain.moderation.repository.BannedWordRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

// TTL 캐시 동작 - 만료 전엔 DB 재조회 없이 캐시를 쓰고, 만료 후 첫 판정에서 갱신한다 (#209).
@ExtendWith(MockitoExtension.class)
class BannedWordCheckerTest {

    @Mock private BannedWordRepository bannedWordRepository;

    @Test
    void TTL_만료_전에는_재조회하지_않고_만료_후_갱신한다() {
        when(bannedWordRepository.findAll()).thenReturn(List.of(BannedWord.of("시발")));
        BannedWordChecker checker = new BannedWordChecker(bannedWordRepository);

        assertThat(checker.containsBannedWord("시발")).isTrue();
        assertThat(checker.containsBannedWord("멀쩡")).isFalse();
        // 두 번 판정해도 TTL(5분) 안이라 조회는 1회
        verify(bannedWordRepository, times(1)).findAll();

        // TTL 경과 시뮬레이션 - 다음 판정에서 재조회
        ReflectionTestUtils.setField(checker, "cacheLoadedAt", Instant.now().minusSeconds(6 * 60));
        when(bannedWordRepository.findAll()).thenReturn(List.of());
        assertThat(checker.containsBannedWord("시발")).isFalse();
        verify(bannedWordRepository, times(2)).findAll();
    }
}
