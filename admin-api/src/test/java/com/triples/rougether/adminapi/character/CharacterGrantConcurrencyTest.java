package com.triples.rougether.adminapi.character;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.adminapi.character.service.CharacterGrantService;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 동시 지급이 같은 캐릭터를 2행 만들지 않는지 검증 — user 행 락(SELECT FOR UPDATE) 직렬화의 회귀 방어.
// 락은 커밋 시점에 풀리므로 @Transactional 테스트(단일 트랜잭션)로는 검증 불가 — 실제 커밋 + 수동 정리로 검증한다.
// 뽑기(GachaService)·온보딩 선택·착용 교체도 같은 user 락을 잡으므로 이 직렬화 메커니즘을 공유한다.
@SpringBootTest
class CharacterGrantConcurrencyTest {

    @Autowired private CharacterGrantService characterGrantService;
    @Autowired private UserRepository userRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private UserCharacterRepository userCharacterRepository;

    private Long userId;
    private Long characterId;

    @AfterEach
    void cleanup() {
        if (userId != null) {
            userCharacterRepository.findByUserId(userId).forEach(userCharacterRepository::delete);
            userRepository.deleteById(userId);
        }
        if (characterId != null) {
            characterRepository.deleteById(characterId);
        }
    }

    @Test
    void 동시에_같은_캐릭터를_지급해도_보유는_1행만_생긴다() throws Exception {
        User user = userRepository.save(User.signUp("grant-race@rougether.dev"));
        Character character = characterRepository.save(
                new Character("cg_race", "Race", "characters/cg_race.png", 10, true));
        userId = user.getId();
        characterId = character.getId();

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger alreadyOwned = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (characterGrantService.grant(userId, "cg_race").alreadyOwned()) {
                        alreadyOwned.incrementAndGet();
                    } else {
                        granted.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 락 경합 예외가 나더라도 중복 행이 없어야 한다는 본검증은 아래 count 로 수행
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // 핵심 불변식: user 행 락 직렬화로 보유는 정확히 1행
        assertThat(userCharacterRepository.findByUserIdAndDeletedAtIsNull(userId)).hasSize(1);
        assertThat(granted.get()).isEqualTo(1);
        assertThat(alreadyOwned.get()).isEqualTo(threads - 1);
    }
}
