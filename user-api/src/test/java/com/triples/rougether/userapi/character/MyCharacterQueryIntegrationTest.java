package com.triples.rougether.userapi.character;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.character.dto.MyCharacterListResponse;
import com.triples.rougether.userapi.character.service.MyCharacterQueryService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

// 보유 캐릭터 목록 회귀 - 정렬(마스터 sortOrder)·착용 플래그·애니메이션 키·삭제 제외를 실제 DB 로 검증.
@SpringBootTest
@Transactional
class MyCharacterQueryIntegrationTest {

    @Autowired private MyCharacterQueryService myCharacterQueryService;
    @Autowired private UserRepository userRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private UserCharacterRepository userCharacterRepository;

    @Test
    void 보유_캐릭터를_마스터_정렬순으로_착용_플래그와_함께_반환한다() {
        User user = userRepository.save(User.signUp("my-char@rougether.dev"));
        Character tigerChar = characterRepository.save(
                new Character("mc_tiger", "Tiger", "characters/mc_tiger.png", 90, true));
        Character bearChar = characterRepository.save(
                new Character("mc_bear", "Bear", "characters/mc_bear.png", 10, true));
        userCharacterRepository.save(UserCharacter.of(user, tigerChar, Instant.parse("2026-07-01T00:00:00Z"), true));
        userCharacterRepository.save(UserCharacter.of(user, bearChar, Instant.parse("2026-07-10T00:00:00Z"), false));

        MyCharacterListResponse response = myCharacterQueryService.getMyCharacters(user.getId());

        // 획득순이 아니라 마스터 sortOrder 순 (bear 10 < tiger 90)
        assertThat(response.items()).extracting("code").containsExactly("mc_bear", "mc_tiger");
        assertThat(response.items()).extracting("selected").containsExactly(false, true);
        assertThat(response.items().get(0).animations().idle())
                .isEqualTo("characters/mc_bear/animations/idle.webp");
        assertThat(response.items().get(0).acquiredAt()).isNotNull();
    }

    @Test
    void 보유가_없으면_빈_목록이다() {
        User user = userRepository.save(User.signUp("my-char-empty@rougether.dev"));

        assertThat(myCharacterQueryService.getMyCharacters(user.getId()).items()).isEmpty();
    }

    @Test
    void 회수된_비활성_캐릭터는_보유_중이어도_목록에서_제외된다() {
        User user = userRepository.save(User.signUp("my-char-retired@rougether.dev"));
        Character active = characterRepository.save(
                new Character("mc_active", "Active", "characters/mc_active.png", 10, true));
        Character retired = characterRepository.save(
                new Character("mc_retired", "Retired", "characters/mc_retired.png", 20, false));
        userCharacterRepository.save(UserCharacter.of(user, active, Instant.now(), true));
        userCharacterRepository.save(UserCharacter.of(user, retired, Instant.now(), false));

        MyCharacterListResponse response = myCharacterQueryService.getMyCharacters(user.getId());

        assertThat(response.items()).extracting("code").containsExactly("mc_active");
    }

    @Test
    void 삭제된_보유는_제외된다() {
        User user = userRepository.save(User.signUp("my-char-del@rougether.dev"));
        Character character = characterRepository.save(
                new Character("mc_del", "Del", "characters/mc_del.png", 10, true));
        UserCharacter owned = userCharacterRepository.save(
                UserCharacter.of(user, character, Instant.now(), false));
        ReflectionTestUtils.setField(owned, "deletedAt", Instant.now());

        assertThat(myCharacterQueryService.getMyCharacters(user.getId()).items()).isEmpty();
    }
}
