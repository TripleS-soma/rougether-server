package com.triples.rougether.userapi.bot;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.support.UserMetricCount;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 동거 봇의 집 거주 규칙(#309). 모든 메서드는 호출자가 house 행 락을 잡은 트랜잭션 안에서 부른다(MANDATORY) —
// 정원 카운트(current_member_count)와 멤버십 상태를 같은 락 아래에서 갱신하기 위해서다.
// - joinStarterHouse: 온보딩 기본 집에 부하(활성 멤버십 수)가 가장 적은 봇 2명을 입주시킨다. 알림 없음.
// - yieldSeat: 만석에 사람이 들어오면 가장 나중에 들어온 봇 1명이 자리를 비운다(LEFT).
// - hasSeatForHuman: 입주 신청 접수 판정용 읽기 전용 버전(자리가 있거나 비켜줄 봇이 있으면 true).
// - dissolveIfNoHumans: 사람이 0명이 되면 남은 봇을 전원 LEFT 처리하고 집을 soft delete 한다.
@Slf4j
@Service
public class BotResidencyService {

    static final int STARTER_HOUSE_BOT_COUNT = 2;

    private final UserRepository userRepository;
    private final HouseMemberRepository houseMemberRepository;

    public BotResidencyService(UserRepository userRepository, HouseMemberRepository houseMemberRepository) {
        this.userRepository = userRepository;
        this.houseMemberRepository = houseMemberRepository;
    }

    // 봇 풀이 비어 있으면(시드 전) 아무 것도 하지 않는다 — 기본 집 생성 자체는 막지 않는다.
    @Transactional(propagation = Propagation.MANDATORY)
    public List<HouseMember> joinStarterHouse(House house) {
        List<User> bots = userRepository.findAllByBotTrueAndDeletedAtIsNull();
        if (bots.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> loadByBotId = houseMemberRepository
                .countActiveByUserIds(bots.stream().map(User::getId).toList(), HouseMemberStatus.ACTIVE)
                .stream()
                .collect(Collectors.toMap(UserMetricCount::getUserId, UserMetricCount::getMetricCount));
        List<User> chosen = bots.stream()
                .sorted(Comparator.comparingLong((User bot) -> loadByBotId.getOrDefault(bot.getId(), 0L))
                        .thenComparing(User::getId))
                .limit(STARTER_HOUSE_BOT_COUNT)
                .toList();
        List<HouseMember> joined = new ArrayList<>();
        for (User bot : chosen) {
            if (house.isFull()) {
                break;
            }
            joined.add(houseMemberRepository.save(HouseMember.create(house, bot, HouseMemberRole.MEMBER)));
            house.increaseMemberCount();
        }
        log.debug("동거 봇 기본 집 입주 - houseId={}, bots={}", house.getId(),
                joined.stream().map(m -> m.getUser().getBotKey()).toList());
        return joined;
    }

    // 만석이고 ACTIVE 봇이 있으면 가장 나중에 들어온 봇 1명을 내보내고 true. 자리를 못 만들면 false.
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean yieldSeat(House house) {
        if (!house.isFull()) {
            return true;
        }
        List<HouseMember> bots = houseMemberRepository
                .findActiveBotMembersLatestFirst(house.getId(), HouseMemberStatus.ACTIVE);
        if (bots.isEmpty()) {
            return false;
        }
        HouseMember yielding = bots.get(0);
        yielding.leave();
        house.decreaseMemberCount();
        log.debug("동거 봇 자리 양보 - houseId={}, bot={}", house.getId(), yielding.getUser().getBotKey());
        return true;
    }

    // 자리가 있거나(정원 미달) 비켜줄 ACTIVE 봇이 있으면 true — 입주 신청 접수 판정용(읽기 전용, 상태 변경 없음).
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean hasSeatForHuman(House house) {
        return !house.isFull()
                || !houseMemberRepository.findActiveBotMembersLatestFirst(house.getId(), HouseMemberStatus.ACTIVE).isEmpty();
    }

    // 사람이 0명이면 봇 전원 LEFT + 집 soft delete. 해체했으면 true.
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean dissolveIfNoHumans(House house) {
        long humans = houseMemberRepository.countActiveHumans(house.getId(), HouseMemberStatus.ACTIVE);
        if (humans > 0) {
            return false;
        }
        List<HouseMember> bots = houseMemberRepository
                .findActiveBotMembersLatestFirst(house.getId(), HouseMemberStatus.ACTIVE);
        for (HouseMember bot : bots) {
            bot.leave();
            house.decreaseMemberCount();
        }
        house.softDelete();
        log.debug("동거 봇만 남은 집 해체 - houseId={}, bots={}", house.getId(), bots.size());
        return true;
    }

    public static boolean isBot(HouseMember member) {
        return member.getUser().isBot();
    }
}
