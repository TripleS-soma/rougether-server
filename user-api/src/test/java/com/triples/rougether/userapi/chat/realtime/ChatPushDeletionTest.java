package com.triples.rougether.userapi.chat.realtime;

import static org.mockito.Mockito.*;

import com.triples.rougether.domain.chat.entity.ChatMessage;
import com.triples.rougether.domain.chat.repository.*;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.chat.service.ChatAccessPolicy;
import com.triples.rougether.userapi.chat.service.ChatMessageSent;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import com.triples.rougether.userapi.notification.service.NotificationSettingService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ChatPushDeletionTest {
    @Test
    void 대기_작업_실행_전에_삭제된_메시지는_푸시하지_않는다() { check(true, false); }

    @Test
    void 대기_작업_실행_전에_탈퇴한_발신자의_메시지는_푸시하지_않는다() { check(false, true); }

    private void check(boolean deletedMessage, boolean deletedSender) {
        var messages = mock(ChatMessageRepository.class);
        var rooms = mock(ChatRoomRepository.class);
        var fcm = mock(FcmPushExecutor.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var message = mock(ChatMessage.class);
        var sender = mock(User.class);
        when(message.isDeleted()).thenReturn(deletedMessage);
        when(message.getSender()).thenReturn(sender);
        when(sender.isDeleted()).thenReturn(deletedSender);
        when(messages.findById(17L)).thenReturn(Optional.of(message));
        var notifier = new ChatPushNotifier(messages, mock(ChatImageRepository.class), rooms,
                mock(ChatReadStateRepository.class),
                mock(HouseRepository.class), mock(UserRepository.class), mock(ChatAccessPolicy.class),
                mock(ChatSocketHandler.class), mock(NotificationSettingService.class), fcm, manager);
        notifier.onMessageSent(new ChatMessageSent(17L));
        verify(messages).findById(17L);
        verifyNoInteractions(rooms, fcm);
    }
}
