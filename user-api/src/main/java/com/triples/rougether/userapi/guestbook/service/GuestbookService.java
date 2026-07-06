package com.triples.rougether.userapi.guestbook.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.room.entity.RoomGuestbook;
import com.triples.rougether.domain.room.repository.RoomGuestbookRepository;
import com.triples.rougether.userapi.guestbook.dto.GuestbookCreateRequest;
import com.triples.rougether.userapi.guestbook.dto.GuestbookCreateResponse;
import com.triples.rougether.userapi.guestbook.dto.GuestbookListResponse;
import com.triples.rougether.userapi.guestbook.dto.GuestbookListResponse.GuestbookItem;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 방명록 - 방 주인과 같은 집(ACTIVE 구성원)만 조회·작성. 조회는 커서 기반 최신순.
@Service
public class GuestbookService {

    private final RoomGuestbookRepository roomGuestbookRepository;
    private final HouseRepository houseRepository;
    private final HouseMemberRepository houseMemberRepository;

    public GuestbookService(RoomGuestbookRepository roomGuestbookRepository,
                            HouseRepository houseRepository,
                            HouseMemberRepository houseMemberRepository) {
        this.roomGuestbookRepository = roomGuestbookRepository;
        this.houseRepository = houseRepository;
        this.houseMemberRepository = houseMemberRepository;
    }

    // 방명록 목록 - 커서 기반 무한스크롤. size+1 로 조회해 hasNext 판정.
    @Transactional(readOnly = true)
    public GuestbookListResponse getGuestbooks(Long userId, Long roomOwnerId, Long houseId,
                                               Long cursor, int size) {
        requireSameHouseMembers(userId, roomOwnerId, houseId);
        List<RoomGuestbook> found = roomGuestbookRepository.findPageByCursor(
                roomOwnerId, houseId, cursor, PageRequest.of(0, size + 1));
        boolean hasNext = found.size() > size;
        List<RoomGuestbook> page = hasNext ? found.subList(0, size) : found;
        List<GuestbookItem> items = page.stream().map(GuestbookItem::of).toList();
        Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
        return new GuestbookListResponse(items, nextCursor, hasNext);
    }

    // 방명록 작성 - 방 주인 본인도 자기 방에 쓸 수 있다.
    @Transactional
    public GuestbookCreateResponse write(Long userId, Long roomOwnerId, GuestbookCreateRequest request) {
        SameHouseContext context = requireSameHouseMembers(userId, roomOwnerId, request.houseId());
        RoomGuestbook guestbook = roomGuestbookRepository.save(RoomGuestbook.write(
                context.roomOwner(), context.house(), context.author(), request.content()));
        return GuestbookCreateResponse.of(guestbook);
    }

    private record SameHouseContext(House house, User author, User roomOwner) {
    }

    // 집 존재(삭제 제외) + 요청자·방 주인 모두 그 집 ACTIVE 구성원인지 확인.
    private SameHouseContext requireSameHouseMembers(Long userId, Long roomOwnerId, Long houseId) {
        House house = houseRepository.findById(houseId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
        HouseMember requester = houseMemberRepository.findByHouseIdAndUserId(houseId, userId)
                .filter(HouseMember::isActive)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_MEMBER));
        HouseMember owner = houseMemberRepository.findByHouseIdAndUserId(houseId, roomOwnerId)
                .filter(HouseMember::isActive)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_MEMBER));
        return new SameHouseContext(house, requester.getUser(), owner.getUser());
    }
}
