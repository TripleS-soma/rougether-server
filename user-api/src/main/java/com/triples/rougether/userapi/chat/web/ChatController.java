package com.triples.rougether.userapi.chat.web;

import com.triples.rougether.userapi.chat.dto.ChatImageCropRequest;
import com.triples.rougether.userapi.chat.dto.ChatImageResponse;
import com.triples.rougether.userapi.chat.dto.ChatMessageListResponse;
import com.triples.rougether.userapi.chat.dto.ChatMessageResponse;
import com.triples.rougether.userapi.chat.dto.ChatNotificationRequest;
import com.triples.rougether.userapi.chat.dto.ChatNotificationResponse;
import com.triples.rougether.userapi.chat.dto.ChatReactionRequest;
import com.triples.rougether.userapi.chat.dto.ChatReactionsResponse;
import com.triples.rougether.userapi.chat.dto.ChatReadRequest;
import com.triples.rougether.userapi.chat.dto.ChatRoomResponse;
import com.triples.rougether.userapi.chat.dto.ChatSendRequest;
import com.triples.rougether.userapi.chat.service.ChatCommandService;
import com.triples.rougether.userapi.chat.service.ChatImageService;
import com.triples.rougether.userapi.chat.service.ChatQueryService;
import com.triples.rougether.userapi.chat.service.ChatReactionService;
import com.triples.rougether.userapi.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Tag(name = "채팅", description = "집 구성원 채팅, 사진·이모티콘·공감과 읽음 상태")
public class ChatController {
    private final ChatCommandService commands;
    private final ChatQueryService query;
    private final ChatImageService images;
    private final ChatReactionService reactions;

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
            description = "그 방의 현재 구성원만 보낼 수 있습니다. 한 번에 한 가지만 보냅니다: 글(content만), "
                    + "사진(imageIds 1~10장, content를 함께 넣으면 사진 설명 글), 이모티콘(emoticonKey만). "
                    + "imageIds는 채팅 사진 업로드(POST /api/v1/chat/rooms/{roomId}/images) 응답의 값을 쓰고 적힌 순서대로 붙으며 "
                    + "사진 하나는 메시지 하나에만 쓸 수 있습니다. 사진은 모두 쓸 수 있을 때만 전송되고 글과 설명 글에는 금칙어 검사를 적용합니다. "
                    + "같은 clientMessageId로 같은 내용을 다시 보내면 처음 메시지를 그대로 돌려줍니다.")
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
                    + "잘라서 보내려면 cropX·cropY·cropWidth·cropHeight를 함께 넣습니다. 네 값은 회전을 바로잡은 사진의 가로·세로에 대한 "
                    + "0~1 비율이고 모두 보내거나 모두 생략하며, 잘린 결과는 가로·세로 모두 16px 이상이어야 합니다. "
                    + "응답의 width·height는 자른 뒤 변환한 크기입니다. "
                    + "사용자당 KST 하루 50장, 보내지 않은 사진 30장까지 올릴 수 있고 보내지 않은 사진은 24시간 뒤 삭제됩니다.")
    @PostMapping(value = "/chat/rooms/{roomId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ChatImageResponse uploadImage(@AuthenticationPrincipal AuthUser user,
                                         @Parameter(description = "채팅방 ID") @PathVariable Long roomId,
                                         @RequestPart("file") MultipartFile file,
                                         @Parameter(description = "자를 영역의 왼쪽 위치(0 이상 1 미만, 사진 가로 대비 비율)",
                                                 schema = @Schema(type = "number", example = "0.1"))
                                         @RequestParam(required = false) String cropX,
                                         @Parameter(description = "자를 영역의 위쪽 위치(0 이상 1 미만, 사진 세로 대비 비율)",
                                                 schema = @Schema(type = "number", example = "0.2"))
                                         @RequestParam(required = false) String cropY,
                                         @Parameter(description = "자를 영역의 가로 비율(0 초과 1 이하, cropX와 합쳐 1 이하)",
                                                 schema = @Schema(type = "number", example = "0.8"))
                                         @RequestParam(required = false) String cropWidth,
                                         @Parameter(description = "자를 영역의 세로 비율(0 초과 1 이하, cropY와 합쳐 1 이하)",
                                                 schema = @Schema(type = "number", example = "0.6"))
                                         @RequestParam(required = false) String cropHeight) {
        return images.upload(user.id(), roomId, file, new ChatImageCropRequest(cropX, cropY, cropWidth, cropHeight));
    }

    @Operation(summary = "채팅 사진 조회",
            description = "Authorization 헤더를 넣어 JPEG 바이트를 받습니다. 보내기 전에는 올린 사람만, 보낸 뒤에는 그 방의 현재 구성원만 볼 수 있으며 CDN URL로 조합하지 않습니다.")
    @GetMapping("/chat/images/{imageId}")
    public ResponseEntity<byte[]> image(@AuthenticationPrincipal AuthUser user,
                                        @Parameter(description = "메시지의 images[].imageId(또는 image.imageId)나 사진 업로드 응답의 imageId") @PathVariable Long imageId) {
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

    @Operation(summary = "메시지 공감 설정",
            description = "그 방의 현재 구성원이 메시지에 공감을 남기거나 다른 유형으로 바꿉니다. 한 사람은 메시지당 하나만 남길 수 있고 "
                    + "본인 메시지에도 가능하며, 같은 유형을 다시 보내면 그대로 유지됩니다. 운영자가 삭제하지 않은 그 방의 메시지에만 남길 수 있습니다. "
                    + "읽음 상태와 sequence는 바뀌지 않고 푸시도 보내지 않으며, 응답은 그 메시지의 공감 전체입니다.")
    @PutMapping("/chat/rooms/{roomId}/messages/{messageId}/reaction")
    public ChatReactionsResponse react(@AuthenticationPrincipal AuthUser user,
                                       @Parameter(description = "채팅방 ID") @PathVariable Long roomId,
                                       @Parameter(description = "메시지 ID") @PathVariable Long messageId,
                                       @Valid @RequestBody ChatReactionRequest request) {
        return reactions.react(user.id(), roomId, messageId, request.type());
    }

    @Operation(summary = "메시지 공감 취소",
            description = "그 방의 현재 구성원이 메시지에 남긴 내 공감을 지웁니다. 남긴 공감이 없어도 성공하며, 응답은 그 메시지의 공감 전체입니다.")
    @DeleteMapping("/chat/rooms/{roomId}/messages/{messageId}/reaction")
    public ChatReactionsResponse removeReaction(@AuthenticationPrincipal AuthUser user,
                                                @Parameter(description = "채팅방 ID") @PathVariable Long roomId,
                                                @Parameter(description = "메시지 ID") @PathVariable Long messageId) {
        return reactions.remove(user.id(), roomId, messageId);
    }

    @PutMapping("/chat/rooms/{roomId}/read")
    public ChatRoomResponse read(@AuthenticationPrincipal AuthUser user, @PathVariable Long roomId,
                                 @Valid @RequestBody ChatReadRequest request) {
        return commands.read(user.id(), roomId, request.lastReadSequence());
    }
}
