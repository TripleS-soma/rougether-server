package com.triples.rougether.domain.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "chat_rooms")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", nullable = false, length = 20)
    private ChatRoomType roomType;
    @Column(name = "house_id", unique = true)
    private Long houseId;
    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;

    public static ChatRoom forHouse(Long houseId) {
        ChatRoom room = new ChatRoom();
        room.roomType = ChatRoomType.HOUSE;
        room.houseId = houseId;
        return room;
    }

    // 방 행 잠금 아래 증가시켜 메시지 순서와 커밋 순서가 일치하게 함.
    public long nextSequence() {
        return ++lastSequence;
    }
}
