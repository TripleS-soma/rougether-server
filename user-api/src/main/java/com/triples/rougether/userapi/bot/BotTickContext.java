package com.triples.rougether.userapi.bot;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.member.entity.User;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

// 한 틱에서 봇 1명을 처리할 때 공유하는 읽기 전용 스냅샷(#310). 행동 어댑터는 이 값만 보고 판정하고,
// 실제 쓰기는 기존 서비스가 각자 트랜잭션·락 아래에서 다시 검증한다.
public record BotTickContext(
        User bot,
        BotActivityProfile profile,
        LocalDate date,
        LocalTime time,
        int tick,
        Instant now,
        Instant todayStart,
        boolean restDay,
        List<HouseSnapshot> houses) {

    public long botId() {
        return bot.getId();
    }

    // 사람 ACTIVE 구성원이 1명 이상인 집만 담긴다.
    public record HouseSnapshot(
            House house,
            HouseMember botMembership,
            List<HouseMember> humans,
            List<HouseMember> bots) {

        public long houseId() {
            return house.getId();
        }
    }
}
