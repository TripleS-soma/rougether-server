package com.triples.rougether.domain.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "chat_read_states")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatReadState {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "room_id", nullable = false)
    private Long roomId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "last_read_sequence", nullable = false)
    private long lastReadSequence;

    public static ChatReadState unread(Long roomId, Long userId) {
        ChatReadState state = new ChatReadState();
        state.roomId = roomId;
        state.userId = userId;
        return state;
    }

    public boolean advance(long sequence) {
        if (sequence <= lastReadSequence) return false;
        lastReadSequence = sequence;
        return true;
    }
}
