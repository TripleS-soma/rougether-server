package com.triples.rougether.userapi.chat.web;

import com.triples.rougether.userapi.chat.dto.ChatImageResponse;
import com.triples.rougether.userapi.chat.dto.ChatMessageListResponse;
import com.triples.rougether.userapi.chat.dto.ChatMessageResponse;
import com.triples.rougether.userapi.chat.dto.ChatNotificationRequest;
import com.triples.rougether.userapi.chat.dto.ChatNotificationResponse;
import com.triples.rougether.userapi.chat.dto.ChatReadRequest;
import com.triples.rougether.userapi.chat.dto.ChatRoomResponse;
import com.triples.rougether.userapi.chat.dto.ChatSendRequest;
import com.triples.rougether.userapi.chat.service.ChatCommandService;
import com.triples.rougether.userapi.chat.service.ChatImageService;
import com.triples.rougether.userapi.chat.service.ChatQueryService;
import com.triples.rougether.userapi.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
@Tag(name = "채팅", description = "집 구성원 채팅, 사진과 읽음 상태")
public class ChatController {
    private final ChatCommandService commands;
    private final ChatQueryService query;
    private final ChatImageService images;

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

    @Operation(summary = "채팅 메시지 전송",
            description = "그 방의 현재 구성원만 보낼 수 있습니다. content로 글을, imageId로 사진을 보내며 둘 중 하나만 넣습니다. "
                    + "imageId는 채팅 사진 업로드(POST /api/v1/chat/rooms/{roomId}/images) 응답의 값을 사용하고 사진 하나는 메시지 하나에만 쓸 수 있습니다.")
    @PostMapping("/chat/rooms/{roomId}/messages")
    public ChatMessageResponse send(@AuthenticationPrincipal AuthUser user,
                                    @Parameter(description = "채팅방 ID") @PathVariable Long roomId,
                                    @Valid @RequestBody ChatSendRequest request) {
        return commands.send(user.id(), roomId, request);
    }

    @GetMapping("/chat/rooms/{roomId}/notification")
    public ChatNotificationResponse notification(@AuthenticationPrincipal AuthUser user, @PathVariable Long roomId) {
        return query.notification(user.id(), roomId);
    }

    @PutMapping("/chat/rooms/{roomId}/notification")
    public ChatNotificationResponse changeNotification(@AuthenticationPrincipal AuthUser user, @PathVariable Long roomId,
                                                       @Valid @RequestBody ChatNotificationRequest request) {
        return commands.changePushMuted(user.id(), roomId, request.muted());
    }

    @Operation(summary = "채팅 사진 업로드",
            description = "그 방의 현재 구성원만 올릴 수 있습니다. multipart의 file로 JPEG·PNG 10MB 이하를 받아 긴 변 1,600px JPEG로 변환하고 위치 정보를 지웁니다. "
                    + "사용자당 KST 하루 50장, 보내지 않은 사진 30장까지 올릴 수 있고 보내지 않은 사진은 24시간 뒤 삭제됩니다.")
    @PostMapping(value = "/chat/rooms/{roomId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ChatImageResponse uploadImage(@AuthenticationPrincipal AuthUser user,
                                         @Parameter(description = "채팅방 ID") @PathVariable Long roomId,
                                         @RequestPart("file") MultipartFile file) {
        return images.upload(user.id(), roomId, file);
    }

    @Operation(summary = "채팅 사진 조회",
            description = "Authorization 헤더를 넣어 JPEG 바이트를 받습니다. 보내기 전에는 올린 사람만, 보낸 뒤에는 그 방의 현재 구성원만 볼 수 있으며 CDN URL로 조합하지 않습니다.")
    @GetMapping("/chat/images/{imageId}")
    public ResponseEntity<byte[]> image(@AuthenticationPrincipal AuthUser user,
                                        @Parameter(description = "메시지의 image.imageId 또는 사진 업로드 응답의 imageId") @PathVariable Long imageId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff").body(images.read(user.id(), imageId));
    }

    @Operation(summary = "보내지 않은 채팅 사진 취소", description = "본인이 올렸지만 아직 메시지로 보내지 않은 사진만 취소할 수 있습니다.")
    @DeleteMapping("/chat/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelImage(@AuthenticationPrincipal AuthUser user,
                            @Parameter(description = "사진 업로드 응답의 imageId") @PathVariable Long imageId) {
        images.cancel(user.id(), imageId);
    }

    @PutMapping("/chat/rooms/{roomId}/read")
    public ChatRoomResponse read(@AuthenticationPrincipal AuthUser user, @PathVariable Long roomId,
                                 @Valid @RequestBody ChatReadRequest request) {
        return commands.read(user.id(), roomId, request.lastReadSequence());
    }
}
