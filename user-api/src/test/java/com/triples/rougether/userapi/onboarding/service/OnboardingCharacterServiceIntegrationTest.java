package com.triples.rougether.userapi.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import com.triples.rougether.userapi.onboarding.dto.OnboardingCharacterResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class OnboardingCharacterServiceIntegrationTest {

    @Autowired
    private CharacterRepository characterRepository;
    @Autowired
    private UserCharacterRepository userCharacterRepository;
    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager em;

    private OnboardingCharacterService service;
    private Long userId;
    private Long c1;
    private Long c2;
    private Long inactive;

    @BeforeEach
    void setUp() {
        service = new OnboardingCharacterService(characterRepository, userCharacterRepository, userRepository);
        userId = userRepository.save(User.signUp()).getId();
        c1 = characterRepository.save(new Character("c1", "고양이", "characters/c1.png", 1, true)).getId();
        c2 = characterRepository.save(new Character("c2", "강아지", "characters/c2.png", 2, true)).getId();
        inactive = characterRepository.save(new Character("cx", "숨김", "characters/cx.png", 3, false)).getId();
    }

    @Test
    void 최초_선택은_스타터를_지급하고_선택한다() {
        OnboardingCharacterResponse response = service.selectCharacter(userId, c1);

        assertThat(response.selectedCharacterId()).isEqualTo(c1);
        List<UserCharacter> owned = userCharacterRepository.findByUserIdAndDeletedAtIsNull(userId);
        assertThat(owned).hasSize(1);
        assertThat(owned.get(0).isSelected()).isTrue();
        assertThat(owned.get(0).getAcquiredAt()).isNotNull();
    }

    @Test
    void 보유_캐릭터로는_무료_교체하고_이전_대표를_해제한다() {
        User user = userRepository.findById(userId).orElseThrow();
        own(user, c1, true);
        own(user, c2, false);

        OnboardingCharacterResponse response = service.selectCharacter(userId, c2);

        assertThat(response.selectedCharacterId()).isEqualTo(c2);
        List<UserCharacter> owned = userCharacterRepository.findByUserIdAndDeletedAtIsNull(userId);
        assertThat(owned).hasSize(2);
        assertThat(owned).filteredOn(UserCharacter::isSelected)
                .extracting(uc -> uc.getCharacter().getId()).containsExactly(c2);
    }

    @Test
    void 미보유_상태에서_보유가_있으면_409() {
        own(userRepository.findById(userId).orElseThrow(), c1, true);

        assertBusiness(() -> service.selectCharacter(userId, c2), MemberErrorCode.CHARACTER_NOT_OWNED);
    }

    @Test
    void 비존재_캐릭터는_404() {
        assertBusiness(() -> service.selectCharacter(userId, 999_999L), MemberErrorCode.CHARACTER_NOT_FOUND);
    }

    @Test
    void 비활성_캐릭터는_404() {
        assertBusiness(() -> service.selectCharacter(userId, inactive), MemberErrorCode.CHARACTER_NOT_FOUND);
    }

    @Test
    void 이미_선택된_캐릭터_재선택은_무해하다() {
        own(userRepository.findById(userId).orElseThrow(), c1, true);

        OnboardingCharacterResponse response = service.selectCharacter(userId, c1);

        assertThat(response.selectedCharacterId()).isEqualTo(c1);
        List<UserCharacter> owned = userCharacterRepository.findByUserIdAndDeletedAtIsNull(userId);
        assertThat(owned).hasSize(1);
        assertThat(owned.get(0).isSelected()).isTrue();
    }

    @Test
    void 선택은_유저행에_비관적_쓰기락을_건다() {
        em.flush();
        em.clear();

        User locked = userRepository.findByIdForUpdate(userId).orElseThrow();

        assertThat(em.getLockMode(locked)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    private void own(User user, Long characterId, boolean selected) {
        Character character = characterRepository.findById(characterId).orElseThrow();
        userCharacterRepository.save(UserCharacter.of(user, character, Instant.now(), selected));
    }

    private void assertBusiness(Runnable runnable, MemberErrorCode expected) {
        assertThatThrownBy(runnable::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }
}
