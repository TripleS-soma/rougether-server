package com.triples.rougether.userapi.gacha.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.gacha.entity.GachaPoolEntry;
import com.triples.rougether.domain.gacha.entity.GachaRarity;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.userapi.gacha.error.GachaErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ChristmasGachaDrawPolicyTest {

    private final Gacha gacha = new Gacha("CHRISTMAS", "크리스마스", CurrencyType.COIN,
            25, 1, null, true);

    @ParameterizedTest
    @CsvSource({"0, true", "49, true", "50, false", "99, false"})
    void 후보_수가_달라도_그룹_추첨은_50퍼센트_경계를_유지한다(int groupRoll, boolean christmas) {
        List<GachaPoolEntry> christmasEntries = entries("크리스마스", 2, 1, 3);
        List<GachaPoolEntry> regularEntries = entries("기존", 1, 3, 1);
        SequenceRandom random = new SequenceRandom(groupRoll, 0, 0);
        var pool = new ChristmasGachaDrawPolicy(random).prepare(christmasEntries, regularEntries);

        GachaPoolEntry picked = pool.pick();

        assertThat(christmasEntries.contains(picked)).isEqualTo(christmas);
        assertThat(regularEntries.contains(picked)).isEqualTo(!christmas);
        assertThat(random.bounds).containsExactly(100, 100, christmas ? 2 : 1);
    }

    @ParameterizedTest
    @CsvSource({
            "49, 0, 일반", "49, 69, 일반", "49, 70, 희귀", "49, 94, 희귀", "49, 95, 전설", "49, 99, 전설",
            "50, 0, 일반", "50, 69, 일반", "50, 70, 희귀", "50, 94, 희귀", "50, 95, 전설", "50, 99, 전설"
    })
    void 각_그룹_안에서_70_25_5_등급_경계를_유지한다(int groupRoll, int rarityRoll, String expectedRarity) {
        List<GachaPoolEntry> christmas = entries("크리스마스", 1, 1, 1);
        List<GachaPoolEntry> regular = entries("기존", 1, 1, 1);
        SequenceRandom random = new SequenceRandom(groupRoll, rarityRoll, 0);
        var pool = new ChristmasGachaDrawPolicy(random).prepare(christmas, regular);

        GachaPoolEntry picked = pool.pick();

        assertThat(picked.getRarity()).isEqualTo(expectedRarity);
        assertThat(groupRoll < 50 ? christmas : regular).contains(picked);
        assertThat(random.bounds).containsExactly(100, 100, 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {49, 50})
    void 여섯_번도_매번_독립_추첨하여_한_그룹이_여섯_개_나올_수_있다(int groupRoll) {
        List<GachaPoolEntry> christmas = entries("크리스마스", 1, 1, 1);
        List<GachaPoolEntry> regular = entries("기존", 1, 1, 1);
        SequenceRandom random = new SequenceRandom(
                groupRoll, 0, 0, groupRoll, 0, 0, groupRoll, 0, 0,
                groupRoll, 0, 0, groupRoll, 0, 0, groupRoll, 0, 0);
        var pool = new ChristmasGachaDrawPolicy(random).prepare(christmas, regular);
        List<GachaPoolEntry> drawn = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            drawn.add(pool.pick());
        }

        assertThat(drawn).hasSize(6).allMatch((groupRoll < 50 ? christmas : regular)::contains);
        assertThat(random.bounds).containsExactly(
                100, 100, 1, 100, 100, 1, 100, 100, 1,
                100, 100, 1, 100, 100, 1, 100, 100, 1);
    }

    @Test
    void 선택한_등급_안에서만_후보를_균등하게_선택한다() {
        List<GachaPoolEntry> christmas = entries("크리스마스", 2, 1, 3);
        List<GachaPoolEntry> regular = entries("기존", 1, 3, 1);
        SequenceRandom random = new SequenceRandom(49, 0, 1, 50, 70, 2);
        var pool = new ChristmasGachaDrawPolicy(random).prepare(christmas, regular);

        assertThat(pool.pick()).isSameAs(christmas.get(1));
        assertThat(pool.pick()).isSameAs(regular.get(3));
        assertThat(random.bounds).containsExactly(100, 100, 2, 100, 100, 3);
    }

    @Test
    void 그룹별_후보_수와_등급별_후보_수가_달라도_표시_확률의_합은_100이다() {
        List<GachaPoolEntry> christmas = entries("크리스마스", 2, 1, 3);
        List<GachaPoolEntry> regular = entries("기존", 1, 3, 1);
        var pool = new ChristmasGachaDrawPolicy(new SequenceRandom()).prepare(christmas, regular);

        double christmasPercent = christmas.stream().mapToDouble(entry -> pool.probabilityPercent(entry, true)).sum();
        double regularPercent = regular.stream().mapToDouble(entry -> pool.probabilityPercent(entry, false)).sum();

        assertThat(christmasPercent).isCloseTo(50, within(1e-10));
        assertThat(regularPercent).isCloseTo(50, within(1e-10));
        assertThat(christmasPercent + regularPercent).isCloseTo(100, within(1e-10));
        assertThat(pool.probabilityPercent(christmas.get(0), true)).isCloseTo(17.5, within(1e-10));
        assertThat(pool.probabilityPercent(christmas.get(2), true)).isCloseTo(12.5, within(1e-10));
        assertThat(pool.probabilityPercent(christmas.get(3), true)).isCloseTo(2.5 / 3, within(1e-10));
        assertThat(pool.probabilityPercent(regular.get(0), false)).isCloseTo(35, within(1e-10));
        assertThat(pool.probabilityPercent(regular.get(1), false)).isCloseTo(12.5 / 3, within(1e-10));
        assertThat(pool.probabilityPercent(regular.get(4), false)).isCloseTo(2.5, within(1e-10));
    }

    @Test
    void 어느_그룹이든_비어있거나_양수_확률의_등급이_누락되거나_null이면_추첨_전에_거절한다() {
        List<GachaPoolEntry> valid = entries("정상", 1, 1, 1);
        List<List<GachaPoolEntry>> invalidPools = new ArrayList<>();
        invalidPools.add(List.of());
        for (String missing : List.of(GachaRarity.NORMAL, GachaRarity.RARE, GachaRarity.LEGENDARY)) {
            invalidPools.add(valid.stream().filter(entry -> !entry.getRarity().equals(missing)).toList());
        }
        List<GachaPoolEntry> ungraded = new ArrayList<>(valid);
        ungraded.add(GachaPoolEntry.uniformItemEntry(gacha, item("등급 없음")));
        invalidPools.add(ungraded);

        for (List<GachaPoolEntry> invalid : invalidPools) {
            SequenceRandom random = new SequenceRandom();
            var policy = new ChristmasGachaDrawPolicy(random);

            assertThatThrownBy(() -> policy.prepare(invalid, valid))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(GachaErrorCode.EMPTY_POOL));
            assertThatThrownBy(() -> policy.prepare(valid, invalid))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(GachaErrorCode.EMPTY_POOL));
            assertThat(random.bounds).isEmpty();
        }
    }

    private List<GachaPoolEntry> entries(String name, int normalCount, int rareCount, int legendaryCount) {
        List<GachaPoolEntry> entries = new ArrayList<>();
        addEntries(entries, name, GachaRarity.NORMAL, normalCount);
        addEntries(entries, name, GachaRarity.RARE, rareCount);
        addEntries(entries, name, GachaRarity.LEGENDARY, legendaryCount);
        return entries;
    }

    private void addEntries(List<GachaPoolEntry> entries, String name, String rarity, int count) {
        for (int i = 0; i < count; i++) {
            entries.add(GachaPoolEntry.itemEntry(gacha, item(name + rarity + i), rarity));
        }
    }

    private Item item(String name) {
        return new Item(null, "furniture", "positioned", null, null,
                name, null, null, "test/" + name + ".png", false, true);
    }

    private static final class SequenceRandom implements RandomGenerator {
        private final int[] values;
        private final List<Integer> bounds = new ArrayList<>();
        private int index;

        private SequenceRandom(int... values) { this.values = values; }

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
