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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// 착용 캐릭터 교체 회귀 - 단일 착용 불변식(is_selected 동시 1개), 미보유·비활성 거부,
// 방 레벨 10 해금 guard(제품 결정 2026-10-14)를 실제 DB 로 검증.
@SpringBootTest
@Transactional
class MyCharacterCommandIntegrationTest {

    @Autowired private MyCharacterCommandService myCharacterCommandService;
    @Autowired private UserRepository userRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private UserCharacterRepository userCharacterRepository;
    @Autowired private JdbcTemplate jdbc;

    private User user() {
        return userRepository.save(User.signUp("select-" + System.nanoTime() + "@rougether.dev"));
    }

    private Character character(String code, boolean active) {
        return characterRepository.save(new Character(code, code, "characters/" + code + ".png", 10, active));
    }

    private void room(User user, int level, int highestLevel) {
        jdbc.update("""
                INSERT INTO personal_rooms (user_id, growth_level, growth_points, highest_growth_level,
                                            layout_format, layout_revision, updated_at)
                VALUES (?, ?, 0, ?, 'SLOT_V1', 0, CURRENT_TIMESTAMP)
                """, user.getId(), level, highestLevel);
    }

    @Test
    void 보유_캐릭터로_교체하면_기존_착용이_해제된다() {
        User user = user();
        room(user, 10, 10);
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
    void 방레벨_9면_다른_캐릭터로_교체를_403으로_거부하고_착용을_유지한다() {
        User user = user();
        room(user, 9, 9);
        Character worn = character("lock_worn", true);
        Character next = character("lock_next", true);
        userCharacterRepository.save(UserCharacter.of(user, worn, Instant.now(), true));
        userCharacterRepository.save(UserCharacter.of(user, next, Instant.now(), false));

        assertThatThrownBy(() -> myCharacterCommandService.select(user.getId(), next.getId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.CHARACTER_SWAP_LOCKED);
                    assertThat(e.getErrorCode().code()).isEqualTo("CHARACTER_SWAP_LOCKED");
                    assertThat(e.getErrorCode().status()).isEqualTo(403);
                });
        assertThat(userCharacterRepository.findByUserIdAndSelectedTrueAndDeletedAtIsNull(user.getId())
                .orElseThrow().getCharacter().getId()).isEqualTo(worn.getId());
    }

    @Test
    void 방이_없으면_레벨0으로_보고_교체를_거부한다() {
        User user = user();
        Character worn = character("noroom_worn", true);
        Character next = character("noroom_next", true);
        userCharacterRepository.save(UserCharacter.of(user, worn, Instant.now(), true));
        userCharacterRepository.save(UserCharacter.of(user, next, Instant.now(), false));

        assertThatThrownBy(() -> myCharacterCommandService.select(user.getId(), next.getId()))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.CHARACTER_SWAP_LOCKED));
    }

    @Test
    void 현재_레벨이_내려가도_최고_달성_레벨_10이면_교체할_수_있다() {
        User user = user();
        room(user, 3, 10);
        Character worn = character("highest_worn", true);
        Character next = character("highest_next", true);
        userCharacterRepository.save(UserCharacter.of(user, worn, Instant.now(), true));
        userCharacterRepository.save(UserCharacter.of(user, next, Instant.now(), false));

        myCharacterCommandService.select(user.getId(), next.getId());

        assertThat(userCharacterRepository.findByUserIdAndSelectedTrueAndDeletedAtIsNull(user.getId())
                .orElseThrow().getCharacter().getId()).isEqualTo(next.getId());
    }

    // 방이 없는(레벨 0) 사용자도 착용 중인 캐릭터 재지정은 무변경 성공임.
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
