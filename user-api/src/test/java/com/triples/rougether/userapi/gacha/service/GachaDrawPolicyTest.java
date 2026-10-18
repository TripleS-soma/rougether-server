package com.triples.rougether.userapi.gacha.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.gacha.entity.GachaPoolEntry;
import com.triples.rougether.domain.gacha.entity.GachaRarity;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GachaDrawPolicyTest {

    private final Gacha gacha = new Gacha("TEST", "추첨 테스트", CurrencyType.COIN, 25, 1, null, true);

    @ParameterizedTest(name = "roll={0}이면 {1} 등급을 선택한다")
    @CsvSource({"0, 일반", "69, 일반", "70, 희귀", "94, 희귀", "95, 전설", "99, 전설"})
    void 등급_확률의_양쪽_경계를_유지한다(int roll, String expectedRarity) {
        List<GachaPoolEntry> entries = List.of(
                entry("희귀 가구", GachaRarity.RARE),
                entry("전설 가구", GachaRarity.LEGENDARY),
                entry("일반 가구", GachaRarity.NORMAL));
        SequenceRandom random = new SequenceRandom(roll, 0);
        GachaDrawPolicy.DrawPool pool = new GachaDrawPolicy(random).prepare(entries);

        assertThat(pool.pick().getRarity()).isEqualTo(expectedRarity);
        assertThat(random.bounds).containsExactly(100, 1);
    }

    @Test
    void 선택한_등급_안에서_입력_순서대로_후보를_선택한다() {
        GachaPoolEntry firstRare = entry("첫 희귀 가구", GachaRarity.RARE);
        GachaPoolEntry secondRare = entry("두 번째 희귀 가구", GachaRarity.RARE);
        SequenceRandom random = new SequenceRandom(70, 1, 94, 0);
        GachaDrawPolicy.DrawPool pool = new GachaDrawPolicy(random).prepare(List.of(
                firstRare, entry("일반 가구", GachaRarity.NORMAL), secondRare));

        assertThat(pool.pick()).isSameAs(secondRare);
        assertThat(pool.pick()).isSameAs(firstRare);
        assertThat(random.bounds).containsExactly(100, 2, 100, 2);
    }

    @Test
    void 모두_등급이_없으면_등급_난수_없이_전체에서_선택한다() {
        GachaPoolEntry first = uniformEntry("첫 악세사리");
        GachaPoolEntry second = uniformEntry("두 번째 악세사리");
        GachaPoolEntry third = uniformEntry("세 번째 악세사리");
        SequenceRandom random = new SequenceRandom(2, 0);
        GachaDrawPolicy.DrawPool pool = new GachaDrawPolicy(random).prepare(List.of(first, second, third));

        assertThat(pool.pick()).isSameAs(third);
        assertThat(pool.pick()).isSameAs(first);
        assertThat(random.bounds).containsExactly(3, 3);
    }

    @Test
    void 등급이_섞여_있으면_null_등급을_일반_후보에_포함한다() {
        GachaPoolEntry rare = entry("희귀 가구", GachaRarity.RARE);
        GachaPoolEntry ungraded = uniformEntry("등급 없는 가구");
        GachaPoolEntry normal = entry("일반 가구", GachaRarity.NORMAL);
        SequenceRandom random = new SequenceRandom(0, 0, 69, 1, 70, 0);
        GachaDrawPolicy.DrawPool pool = new GachaDrawPolicy(random).prepare(List.of(rare, ungraded, normal));

        assertThat(pool.pick()).isSameAs(ungraded);
        assertThat(pool.pick()).isSameAs(normal);
        assertThat(pool.pick()).isSameAs(rare);
        assertThat(random.bounds).containsExactly(100, 2, 100, 2, 100, 1);
    }

    @ParameterizedTest(name = "{1} 후보가 없고 roll={0}이면 전체 후보에서 선택한다")
    @CsvSource({"0, 일반", "70, 희귀", "95, 전설"})
    void 선택한_등급이_없으면_전체_후보로_돌아간다(int roll, String missingRarity) {
        List<GachaPoolEntry> entries = List.of(
                        entry("일반 가구", GachaRarity.NORMAL),
                        entry("희귀 가구", GachaRarity.RARE),
                        entry("전설 가구", GachaRarity.LEGENDARY)).stream()
                .filter(entry -> !entry.getRarity().equals(missingRarity))
                .toList();
        SequenceRandom random = new SequenceRandom(roll, 1);
        GachaDrawPolicy.DrawPool pool = new GachaDrawPolicy(random).prepare(entries);

        assertThat(pool.pick()).isSameAs(entries.get(1));
        assertThat(random.bounds).containsExactly(100, 2);
    }

    private GachaPoolEntry entry(String name, String rarity) {
        return GachaPoolEntry.itemEntry(gacha, item(name), rarity);
    }

    private GachaPoolEntry uniformEntry(String name) {
        return GachaPoolEntry.uniformItemEntry(gacha, item(name));
    }

    private Item item(String name) {
        return new Item(null, "FURNITURE", "positioned", null, null,
                name, null, null, "test/" + name + ".png", false, true);
    }

    private static final class SequenceRandom implements RandomGenerator {

        private final int[] values;
        private final List<Integer> bounds = new ArrayList<>();
        private int index;

        private SequenceRandom(int... values) {
            this.values = values;
        }

        @Override
        public int nextInt(int bound) {
            bounds.add(bound);
            assertThat(index).as("예상한 횟수만 난수를 요청한다").isLessThan(values.length);
            int value = values[index++];
            assertThat(value).as("난수 값은 요청 범위 안에 있어야 한다").isBetween(0, bound - 1);
            return value;
        }

        @Override
        public long nextLong() {
            throw new AssertionError("추첨 정책은 nextInt(bound)만 사용해야 한다");
        }
    }
}
