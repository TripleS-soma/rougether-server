package com.triples.rougether.userapi.character;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.CharacterPose;
import com.triples.rougether.userapi.character.dto.CharacterPoseResponse;
import org.junit.jupiter.api.Test;

class CharacterPoseResponseTest {

    @Test
    void 활성_포즈만_sortOrder_순으로_변환한다() {
        Character character = new Character(
                "cat", "고양이", "characters/cat/animations/blink.gif", 70, true);
        character.getPoses().add(new CharacterPose(
                character, "temp2", "characters/temp2.webp", 20, true));
        character.getPoses().add(new CharacterPose(
                character, "hidden", "characters/hidden.webp", 5, false));
        character.getPoses().add(new CharacterPose(
                character, "temp1", "characters/temp1.webp", 10, true));

        var poses = CharacterPoseResponse.activeOf(character);

        assertThat(poses).extracting(CharacterPoseResponse::code)
                .containsExactly("temp1", "temp2");
        assertThat(poses).extracting(CharacterPoseResponse::assetKey)
                .containsExactly("characters/temp1.webp", "characters/temp2.webp");
    }
}
