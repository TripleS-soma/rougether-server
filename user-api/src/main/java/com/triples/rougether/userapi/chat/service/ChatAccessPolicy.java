package com.triples.rougether.userapi.chat.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.chat.entity.ChatRoom;
import com.triples.rougether.domain.chat.repository.ChatRoomRepository;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import java.util.List;
import com.triples.rougether.domain.chat.entity.ChatRoomType;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.chat.error.ChatErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChatAccessPolicy {
    private final ChatRoomRepository rooms;
    private final UserRepository users;
    private final HouseRepository houses;
    private final HouseMemberRepository members;

    // 전체 채팅 도입 시 유형별 인가만 확장함. 미지원 유형은 기본 거부함.
    public void requireReadable(ChatRoom room, Long userId) {
        if (room.getRoomType() != ChatRoomType.HOUSE) deny();
        House house = houses.findById(room.getHouseId())
                .filter(h -> !h.isDeleted()).orElseThrow(ChatAccessPolicy::forbidden);
        HouseMember member = members.findByHouseIdAndUserId(house.getId(), userId)
                .filter(HouseMember::isActive).orElseThrow(ChatAccessPolicy::forbidden);
        if (member.getUser().isDeleted() || member.getUser().isBot()) deny();
    }

    // 메시지/읽음 서비스는 집을 알 필요 없이 방 유형별 쓰기 정책에 위임함.
    public User lockWriter(Long roomId, Long userId) {
        var target = rooms.findAccessTarget(roomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        if (target.getRoomType() != ChatRoomType.HOUSE) deny();
        return lockHouseMember(target.getHouseId(), userId);
    }

    public List<Participant> participants(ChatRoom room) {
        if (room.getRoomType() != ChatRoomType.HOUSE) deny();
        houses.findById(room.getHouseId()).filter(h -> !h.isDeleted()).orElseThrow(ChatAccessPolicy::forbidden);
        return members.findByHouseIdAndStatusWithUser(room.getHouseId(),
                HouseMemberStatus.ACTIVE).stream()
                .filter(m -> !m.getUser().isDeleted() && !m.getUser().isBot())
                .map(m -> new Participant(m.getUser().getId(), m.getId())).toList();
    }

    public record Participant(Long userId, Long membershipId) {}

    // 회원탈퇴와 동일하게 user → house → membership 순서로 잠금함.
    public User lockHouseMember(Long houseId, Long userId) {
        User user = users.findByIdForUpdate(userId)
                .filter(u -> !u.isDeleted() && !u.isBot()).orElseThrow(ChatAccessPolicy::forbidden);
        houses.findWithLockById(houseId).filter(h -> !h.isDeleted()).orElseThrow(ChatAccessPolicy::forbidden);
        members.findWithLockByHouseIdAndUserId(houseId, userId)
                .filter(HouseMember::isActive).orElseThrow(ChatAccessPolicy::forbidden);
        return user;
    }

    private static BusinessException forbidden() { return new BusinessException(ChatErrorCode.CHAT_FORBIDDEN); }
    private static void deny() { throw forbidden(); }
}
