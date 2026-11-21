package com.triples.rougether.userapi.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.triples.rougether.domain.chat.entity.*;
import com.triples.rougether.domain.chat.repository.*;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.userapi.chat.service.*;
import com.triples.rougether.common.error.BusinessException;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChatImageExpiryBoundaryTest {
    @Test
    void 실제_열람과_정리는_전송부터_30일_정확한_경계에서_전환된다() {
        Instant sentAt = Instant.parse("2026-11-15T00:00:00Z");
        Instant expiry = sentAt.plus(ChatImage.SENT_RETENTION);
        var images = mock(ChatImageRepository.class);
        var messages = mock(ChatMessageRepository.class);
        var rooms = mock(ChatRoomRepository.class);
        var houses = mock(HouseRepository.class);
        var access = mock(ChatAccessPolicy.class);
        var storage = mock(ChatImageStorage.class);
        var image = mock(ChatImage.class);
        var message = mock(ChatMessage.class);
        var room = mock(ChatRoom.class);
        when(image.getOwner()).thenReturn(mock(User.class));
        when(image.isReady()).thenReturn(true);
        when(image.getId()).thenReturn(1L);
        when(image.getMessageId()).thenReturn(2L);
        when(image.getRoomId()).thenReturn(3L);
        when(image.getStorageKey()).thenReturn("private/chat/example.jpg");
        when(image.getCreatedAt()).thenReturn(sentAt);
        when(image.getExpiresAt()).thenReturn(Instant.EPOCH); // 구버전 업로드 만료 값은 무시해야 함.
        when(message.getCreatedAt()).thenReturn(sentAt);
        when(images.findById(1L)).thenReturn(Optional.of(image));
        when(images.findForUpdate(1L)).thenReturn(Optional.of(image));
        when(messages.findById(2L)).thenReturn(Optional.of(message));
        when(rooms.findById(3L)).thenReturn(Optional.of(room));
        when(access.canRead(room, 4L)).thenReturn(true);
        Clock before = Clock.fixed(expiry.minusNanos(1), ZoneOffset.UTC);
        var beforeAccess = new ChatImageTransactions(access, images, rooms, messages, before);
        assertThat(beforeAccess.metadata(4L, 1L).expiresAt()).isEqualTo(expiry);
        new ChatCleanupTransactions(images, messages, rooms, houses, storage, before).image(1L);
        verify(storage, never()).delete(anyString());
        Clock at = Clock.fixed(expiry, ZoneOffset.UTC);
        var atAccess = new ChatImageTransactions(access, images, rooms, messages, at);
        assertThatThrownBy(() -> atAccess.readableKey(4L, 1L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> atAccess.metadata(4L, 1L)).isInstanceOf(BusinessException.class);
        new ChatCleanupTransactions(images, messages, rooms, houses, storage, at).image(1L);
        verify(storage).delete("private/chat/example.jpg");
        verify(image).markPurged(expiry);
        verify(images, never()).delete(any());
    }
}
