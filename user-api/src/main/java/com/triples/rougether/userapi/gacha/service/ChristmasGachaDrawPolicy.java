package com.triples.rougether.userapi.gacha.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.gacha.entity.GachaPoolEntry;
import com.triples.rougether.domain.gacha.entity.GachaRarity;
import com.triples.rougether.userapi.gacha.error.GachaErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

// 이벤트의 그룹 확률은 후보 수나 보유 여부와 무관함. 일반 머신의 fallback 정책과 분리함.
@Component
public class ChristmasGachaDrawPolicy {
    private final RandomGenerator random;

    public ChristmasGachaDrawPolicy() { this(new Random()); }
    public ChristmasGachaDrawPolicy(RandomGenerator random) { this.random = random; }

    public DrawPool prepare(List<GachaPoolEntry> christmas, List<GachaPoolEntry> regular) {
        return new DrawPool(tiers(christmas), tiers(regular), random);
    }

    private Map<String, List<GachaPoolEntry>> tiers(List<GachaPoolEntry> pool) {
        if (pool.isEmpty() || pool.stream().anyMatch(e -> !GachaRarity.isSupported(e.getRarity()))) {
            throw new BusinessException(GachaErrorCode.EMPTY_POOL);
        }
        var tiers = pool.stream().collect(Collectors.groupingBy(GachaPoolEntry::getRarity));
        if (!tiers.keySet().containsAll(List.of(GachaRarity.NORMAL, GachaRarity.RARE, GachaRarity.LEGENDARY))) {
            throw new BusinessException(GachaErrorCode.EMPTY_POOL);
        }
        return tiers;
    }

    public static final class DrawPool {
        private final Map<String, List<GachaPoolEntry>> christmas;
        private final Map<String, List<GachaPoolEntry>> regular;
        private final RandomGenerator random;
        private DrawPool(Map<String, List<GachaPoolEntry>> christmas,
                         Map<String, List<GachaPoolEntry>> regular, RandomGenerator random) {
            this.christmas = christmas;
            this.regular = regular;
            this.random = random;
        }
        public GachaPoolEntry pick() {
            var group = random.nextInt(100) < 50 ? christmas : regular;
            int roll = random.nextInt(100);
            String rarity = roll < 70 ? GachaRarity.NORMAL : roll < 95 ? GachaRarity.RARE : GachaRarity.LEGENDARY;
            var tier = group.get(rarity);
            return tier.get(random.nextInt(tier.size()));
        }
        public double probabilityPercent(GachaPoolEntry entry, boolean isChristmas) {
            var tier = (isChristmas ? christmas : regular).get(entry.getRarity());
            double rarityPercent = GachaRarity.NORMAL.equals(entry.getRarity()) ? 70
                    : GachaRarity.RARE.equals(entry.getRarity()) ? 25 : 5;
            return 0.5 * rarityPercent / tier.size();
        }
    }
}
