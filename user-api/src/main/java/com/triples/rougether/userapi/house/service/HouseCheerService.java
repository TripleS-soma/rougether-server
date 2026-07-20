package com.triples.rougether.userapi.house.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.CheerType;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberCheer;
import com.triples.rougether.domain.house.repository.HouseMemberCheerRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.userapi.house.dto.HouseCheerResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 집 멤버 원탭 응원(#173). 저장 커밋 후 리스너가 알림을 발송한다(저장 롤백 시 알림 없음).
@Service
public class HouseCheerService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 온보딩 전(닉네임 null) 보낸이의 알림 표시명
    private static final String FALLBACK_SENDER_NAME = "집 친구";

    private final HouseRepository houseRepository;
    private final HouseMemberRepository houseMemberRepository;
    private final HouseMemberCheerRepository houseMemberCheerRepository;
    private final ApplicationEventPublisher eventPublisher;

    public HouseCheerService(HouseRepository houseRepository,
                             HouseMemberRepository houseMemberRepository,
                             HouseMemberCheerRepository houseMemberCheerRepository,
                             ApplicationEventPublisher eventPublisher) {
        this.houseRepository = houseRepository;
        this.houseMemberRepository = houseMemberRepository;
        this.houseMemberCheerRepository = houseMemberCheerRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public HouseCheerResponse cheer(Long userId, Long houseId, Long membershipId, String typeCode) {
        CheerType type = CheerType.fromCode(typeCode)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_CHEER_TYPE_INVALID));

        House house = houseRepository.findById(houseId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
        HouseMember requester = houseMemberRepository.findByHouseIdAndUserId(houseId, userId)
                .filter(HouseMember::isActive)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_MEMBER));
        HouseMember target = houseMemberRepository.findById(membershipId)
                .filter(member -> member.getHouse().getId().equals(houseId))
                .filter(HouseMember::isActive)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_MEMBER_NOT_FOUND));
        if (target.getUser().getId().equals(userId)) {
            throw new BusinessException(HouseErrorCode.HOUSE_CHEER_SELF);
        }

        LocalDate today = LocalDate.now(KST);
        if (houseMemberCheerRepository.existsBySender_IdAndTarget_IdAndCheerTypeAndCheerDate(
                userId, target.getUser().getId(), type.code(), today)) {
            throw new BusinessException(HouseErrorCode.HOUSE_CHEER_DUPLICATED);
        }

        HouseMemberCheer cheer;
        try {
            // 동시 요청은 사전 검사를 둘 다 통과할 수 있어 UNIQUE 충돌을 409 로 변환한다(즉시 flush 로 이 지점에서 감지).
            cheer = houseMemberCheerRepository.saveAndFlush(
                    HouseMemberCheer.send(house, requester.getUser(), target.getUser(), type, today));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HouseErrorCode.HOUSE_CHEER_DUPLICATED);
        }

        String senderName = requester.getUser().getNickname() != null
                ? requester.getUser().getNickname()
                : FALLBACK_SENDER_NAME;
        eventPublisher.publishEvent(new HouseCheerSentEvent(
                cheer.getId(), target.getUser().getId(), senderName, type));

        return HouseCheerResponse.of(cheer, houseId, membershipId);
    }

    // 커밋 후 알림 발송용 이벤트. senderName 은 커밋 시점 조회를 피하려고 발행 시점에 확정해 담는다.
    public record HouseCheerSentEvent(Long cheerId, Long targetUserId, String senderName, CheerType type) {
    }
}
