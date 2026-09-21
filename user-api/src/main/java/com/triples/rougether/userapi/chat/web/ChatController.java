package com.triples.rougether.userapi.chat.web;

import com.triples.rougether.userapi.chat.dto.ChatMessageListResponse;
import com.triples.rougether.userapi.chat.dto.ChatMessageResponse;
import com.triples.rougether.userapi.chat.dto.ChatReadRequest;
import com.triples.rougether.userapi.chat.dto.ChatRoomResponse;
import com.triples.rougether.userapi.chat.dto.ChatSendRequest;
import com.triples.rougether.userapi.chat.service.ChatCommandService;
import com.triples.rougether.userapi.chat.service.ChatQueryService;
import com.triples.rougether.userapi.global.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
@Tag(name = "채팅", description = "집 구성원 텍스트 채팅과 읽음 상태")
public class ChatController {
    private final ChatCommandService commands;
    private final ChatQueryService query;

    @PostMapping("/houses/{houseId}/chat-room")
    public ChatRoomResponse open(@AuthenticationPrincipal AuthUser user, @PathVariable Long houseId) {
        return commands.openHouse(user.id(), houseId);
    }

    @GetMapping("/chat/rooms/{roomId}")
    public ChatRoomResponse room(@AuthenticationPrincipal AuthUser user, @PathVariable Long roomId) {
        return query.get(user.id(), roomId);
    }

    @GetMapping("/chat/rooms/{roomId}/messages")
    public ChatMessageListResponse messages(@AuthenticationPrincipal AuthUser user, @PathVariable Long roomId,
            @RequestParam(required = false) Long before, @RequestParam(required = false) Long after,
            @RequestParam(defaultValue = "50") int size) {
        return query.messages(user.id(), roomId, before, after, size);
    }

    @PostMapping("/chat/rooms/{roomId}/messages")
    public ChatMessageResponse send(@AuthenticationPrincipal AuthUser user, @PathVariable Long roomId,
                                    @Valid @RequestBody ChatSendRequest request) {
        return commands.send(user.id(), roomId, request);
    }

    @PutMapping("/chat/rooms/{roomId}/read")
    public ChatRoomResponse read(@AuthenticationPrincipal AuthUser user, @PathVariable Long roomId,
                                 @Valid @RequestBody ChatReadRequest request) {
        return commands.read(user.id(), roomId, request.lastReadSequence());
    }
}
