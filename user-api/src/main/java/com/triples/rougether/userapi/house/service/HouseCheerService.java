package com.triples.rougether.userapi.house.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.CheerType;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberCheer;
import com.triples.rougether.domain.house.repository.HouseMemberCheerRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.userapi.house.dto.HouseCheerResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.notification.service.NotificationService;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 집 멤버 원탭 응원(#173).
// 알림은 진입점(NotificationService.send)을 같은 트랜잭션에서 직접 호출한다 - spec(notification) 계약대로
// 내역 저장은 동기(응원 커밋과 원자적)고 push 만 진입점 내부에서 커밋 후 비동기로 나간다(push 실패해도 내역은 남음).
// AFTER_COMMIT 리스너 + 새 트랜잭션 방식은 커밋된 트랜잭션 참여(내역 유실)·커밋 예외 전파(요청 500)·
// 이중 커넥션 점유·제출 거부 전파·비동기 내역 유실까지 실패 모드가 많아 채택하지 않는다(#174 리뷰 이력).
@Service
public class HouseCheerService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 도배 방지: 같은 대상에게 같은 타입은 하루(KST) 5회까지
    private static final int DAILY_LIMIT_PER_TYPE = 5;
    private static final String NOTIFICATION_TITLE = "응원이 도착했어요";
    // 온보딩 전(닉네임 null) 보낸이의 알림 표시명
    private static final String FALLBACK_SENDER_NAME = "집 친구";

    private final HouseRepository houseRepository;
    private final HouseMemberRepository houseMemberRepository;
    private final HouseMemberCheerRepository houseMemberCheerRepository;
    private final NotificationService notificationService;

    public HouseCheerService(HouseRepository houseRepository,
                             HouseMemberRepository houseMemberRepository,
                             HouseMemberCheerRepository houseMemberCheerRepository,
                             NotificationService notificationService) {
        this.houseRepository = houseRepository;
        this.houseMemberRepository = houseMemberRepository;
        this.houseMemberCheerRepository = houseMemberCheerRepository;
        this.notificationService = notificationService;
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
        int sentToday = houseMemberCheerRepository.countBySender_IdAndTarget_IdAndCheerTypeAndCheerDate(
                userId, target.getUser().getId(), type.code(), today);
        if (sentToday >= DAILY_LIMIT_PER_TYPE) {
            throw new BusinessException(HouseErrorCode.HOUSE_CHEER_LIMIT_EXCEEDED);
        }

        HouseMemberCheer cheer;
        try {
            // 동시 요청은 같은 daily_seq 를 계산할 수 있어 UNIQUE 충돌을 409 로 변환한다(즉시 flush 로 이 지점에서 감지).
            cheer = houseMemberCheerRepository.saveAndFlush(HouseMemberCheer.send(
                    house, requester.getUser(), target.getUser(), type, today, sentToday + 1));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HouseErrorCode.HOUSE_CHEER_LIMIT_EXCEEDED);
        }

        String senderName = requester.getUser().getNickname() != null
                ? requester.getUser().getNickname()
                : FALLBACK_SENDER_NAME;
        notificationService.send(
                target.getUser().getId(),
                NotificationType.FRIEND_CHEER,
                NOTIFICATION_TITLE,
                senderName + "님: " + type.message(),
                cheer.getId());

        return HouseCheerResponse.of(cheer, houseId, membershipId);
    }
}
