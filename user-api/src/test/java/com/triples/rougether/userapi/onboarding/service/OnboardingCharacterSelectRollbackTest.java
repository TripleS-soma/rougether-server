package com.triples.rougether.userapi.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
class OnboardingCharacterSelectRollbackTest {

    @Autowired
    private OnboardingCommandService service;
    @Autowired
    private CharacterRepository characterRepository;
    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private UserCharacterRepository userCharacterRepository;

    private Long userId;
    private List<Long> characterIds;

    @AfterEach
    void cleanUp() {
        if (userId != null) {
            userCharacterRepository.deleteAll(userCharacterRepository.findByUserId(userId));
            userRepository.deleteById(userId);
        }
        if (characterIds != null) {
            characterRepository.deleteAllById(characterIds);
        }
    }

    @Test
    void 교체_저장_실패시_이전_대표가_유지된다() {
        User user = userRepository.save(User.signUp());
        userId = user.getId();
        Character ch1 = characterRepository.save(new Character("c1", "고양이", "characters/c1.png", 1, true));
        Character ch2 = characterRepository.save(new Character("c2", "강아지", "characters/c2.png", 2, true));
        characterIds = List.of(ch1.getId(), ch2.getId());
        userCharacterRepository.save(UserCharacter.of(user, ch1, Instant.now(), true));
        userCharacterRepository.save(UserCharacter.of(user, ch2, Instant.now(), false));

        doThrow(new RuntimeException("저장 실패")).when(userCharacterRepository).save(any());

        assertThatThrownBy(() -> service.selectCharacter(userId, ch2.getId()))
                .isInstanceOf(RuntimeException.class);

        List<UserCharacter> owned = userCharacterRepository.findByUserIdAndDeletedAtIsNull(userId);
        assertThat(owned).filteredOn(UserCharacter::isSelected)
                .extracting(uc -> uc.getCharacter().getId()).containsExactly(ch1.getId());
    }
}
