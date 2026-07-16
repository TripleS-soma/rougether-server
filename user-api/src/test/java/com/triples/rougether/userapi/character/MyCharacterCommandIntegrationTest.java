package com.triples.rougether.userapi.character;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.character.service.MyCharacterCommandService;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 착용 캐릭터 교체 회귀 - 단일 착용 불변식(is_selected 동시 1개), 미보유·비활성 거부를 실제 DB 로 검증.
@SpringBootTest
@Transactional
class MyCharacterCommandIntegrationTest {

    @Autowired private MyCharacterCommandService myCharacterCommandService;
    @Autowired private UserRepository userRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private UserCharacterRepository userCharacterRepository;

    private User user() {
        return userRepository.save(User.signUp("select-" + System.nanoTime() + "@rougether.dev"));
    }

    private Character character(String code, boolean active) {
        return characterRepository.save(new Character(code, code, "characters/" + code + ".png", 10, active));
    }

    @Test
    void 보유_캐릭터로_교체하면_기존_착용이_해제된다() {
        User user = user();
        Character worn = character("sel_worn", true);
        Character next = character("sel_next", true);
        userCharacterRepository.save(UserCharacter.of(user, worn, Instant.now(), true));
        userCharacterRepository.save(UserCharacter.of(user, next, Instant.now(), false));

        Long selectedId = myCharacterCommandService.select(user.getId(), next.getId());

        assertThat(selectedId).isEqualTo(next.getId());
        UserCharacter selected = userCharacterRepository
                .findByUserIdAndSelectedTrueAndDeletedAtIsNull(user.getId()).orElseThrow();
        assertThat(selected.getCharacter().getId()).isEqualTo(next.getId());
        // 단일 착용 불변식: 이전 착용은 해제됨
        assertThat(userCharacterRepository.findByUserIdAndDeletedAtIsNull(user.getId()))
                .filteredOn(UserCharacter::isSelected).hasSize(1);
    }

    @Test
    void 이미_착용_중이면_변경_없이_성공한다() {
        User user = user();
        Character worn = character("sel_same", true);
        userCharacterRepository.save(UserCharacter.of(user, worn, Instant.now(), true));

        Long selectedId = myCharacterCommandService.select(user.getId(), worn.getId());

        assertThat(selectedId).isEqualTo(worn.getId());
        assertThat(userCharacterRepository.findByUserIdAndSelectedTrueAndDeletedAtIsNull(user.getId()))
                .isPresent();
    }

    @Test
    void 미보유_캐릭터는_409로_거부한다() {
        User user = user();
        Character notOwned = character("sel_not_owned", true);

        assertThatThrownBy(() -> myCharacterCommandService.select(user.getId(), notOwned.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.CHARACTER_NOT_OWNED));
    }

    @Test
    void 보유_중이어도_비활성_캐릭터는_착용할_수_없다() {
        User user = user();
        Character retired = character("sel_retired", false);
        userCharacterRepository.save(UserCharacter.of(user, retired, Instant.now(), false));

        assertThatThrownBy(() -> myCharacterCommandService.select(user.getId(), retired.getId()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.CHARACTER_NOT_FOUND));
    }
}
