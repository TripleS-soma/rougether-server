package com.triples.rougether.domain.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

class PersonalRoomGrowthTest {

    @ParameterizedTest
    @CsvSource({
            "0, 0, 20", "19, 0, 1", "20, 1, 22", "41, 1, 1", "42, 2, 24",
            "65, 2, 1", "66, 3, 26", "91, 3, 1", "92, 4, 28",
            "120, 5, 30", "289, 9, 1", "290, 10, 40", "780, 20, 60"
    })
    void 레벨이_오를수록_구간_포인트가_2씩_늘어난다(int points, int level, long remaining) {
        PersonalRoom room = PersonalRoom.create(User.signUp());
        room.changeGrowthPoints(points);
        assertThat(room.getGrowthPoints()).isEqualTo(points);
        assertThat(room.getGrowthLevel()).isEqualTo(level);
        assertThat(room.getPointsToNextLevel()).isEqualTo(remaining);
        assertThat(room.getLayoutRevision()).isZero();
    }

    @Test
    void 한번에_여러_레벨을_올려도_누적_포인트를_남기고_취소로_경계를_되돌린다() {
        PersonalRoom room = PersonalRoom.create(User.signUp());
        room.changeGrowthPoints(20);
        room.changeGrowthPoints(46);
        assertThat(room.getGrowthLevel()).isEqualTo(3);
        assertThat(room.getPointsToNextLevel()).isEqualTo(26);
        room.changeGrowthPoints(-10);
        assertThat(room.getGrowthLevel()).isEqualTo(2);
        assertThat(room.getGrowthPoints()).isEqualTo(56);
        assertThat(room.getPointsToNextLevel()).isEqualTo(10);
        room.changeGrowthPoints(-56);
        assertThat(room.getGrowthLevel()).isZero();
        assertThat(room.getHighestGrowthLevel()).isEqualTo(3);
        assertThat(room.getPointsToNextLevel()).isEqualTo(20);
    }

    @Test
    void 적립량보다_많이_회수하면_기존_레벨과_포인트를_보존하고_실패한다() {
        PersonalRoom room = PersonalRoom.create(User.signUp());
        room.changeGrowthPoints(42);
        assertThatThrownBy(() -> room.changeGrowthPoints(-43)).isInstanceOf(IllegalStateException.class);
        assertThat(room.getGrowthPoints()).isEqualTo(42);
        assertThat(room.getGrowthLevel()).isEqualTo(2);
    }

    @Test
    void 큰_누적치에서도_레벨_정수_범위의_경계를_정확히_검증한다() {
        PersonalRoom room = PersonalRoom.create(User.signUp());
        long nextLevel = (long) Integer.MAX_VALUE + 1;
        long maxPoints = nextLevel * (nextLevel + 19) - 1;
        ReflectionTestUtils.setField(room, "growthPoints", maxPoints);
        room.changeGrowthPoints(0);
        assertThat(room.getGrowthLevel()).isEqualTo(Integer.MAX_VALUE);
        assertThat(room.getPointsToNextLevel()).isEqualTo(1);
        assertThatThrownBy(() -> room.changeGrowthPoints(1)).isInstanceOf(ArithmeticException.class);
        assertThat(room.getGrowthPoints()).isEqualTo(maxPoints);
        assertThat(room.getGrowthLevel()).isEqualTo(Integer.MAX_VALUE);
    }
}
