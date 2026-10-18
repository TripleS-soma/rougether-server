package com.triples.rougether.userapi.gacha.service;

import com.triples.rougether.domain.gacha.entity.GachaPoolEntry;
import com.triples.rougether.domain.gacha.entity.GachaRarity;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class GachaDrawPolicy {

    private static final int TIER_NORMAL_MAX = 70;
    private static final int TIER_RARE_MAX = 95;

    private final RandomGenerator random;

    public GachaDrawPolicy() {
        this(new Random());
    }

    public GachaDrawPolicy(RandomGenerator random) {
        this.random = random;
    }

    // 서비스에서 유효성과 빈 풀을 검증한 후보를 받아 요청마다 한 번 준비함.
    public DrawPool prepare(List<GachaPoolEntry> pool) {
        return new DrawPool(pool, random);
    }

    public static final class DrawPool {

        private final List<GachaPoolEntry> pool;
        private final Map<String, List<GachaPoolEntry>> byRarity;
        private final boolean uniform;
        private final RandomGenerator random;

        private DrawPool(List<GachaPoolEntry> pool, RandomGenerator random) {
            this.pool = List.copyOf(pool);
            this.byRarity = this.pool.stream()
                    .collect(Collectors.groupingBy(
                            entry -> entry.getRarity() == null ? GachaRarity.NORMAL : entry.getRarity()));
            this.uniform = this.pool.stream().allMatch(entry -> entry.getRarity() == null);
            this.random = random;
        }

        public GachaPoolEntry pick() {
            // 등급이 모두 없으면 등급 추첨 없이 전체 후보를 균등하게 선택함.
            if (uniform) {
                return pool.get(random.nextInt(pool.size()));
            }

            int roll = random.nextInt(100);
            String rarity = roll < TIER_NORMAL_MAX
                    ? GachaRarity.NORMAL
                    : roll < TIER_RARE_MAX ? GachaRarity.RARE : GachaRarity.LEGENDARY;
            List<GachaPoolEntry> tier = byRarity.getOrDefault(rarity, pool);
            if (tier.isEmpty()) {
                tier = pool;
            }
            return tier.get(random.nextInt(tier.size()));
        }
    }
}
