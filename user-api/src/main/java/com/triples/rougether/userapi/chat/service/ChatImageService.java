package com.triples.rougether.userapi.chat.service;

import static com.triples.rougether.userapi.chat.error.ChatErrorCode.CHAT_IMAGE_INVALID;
import static com.triples.rougether.userapi.chat.error.ChatErrorCode.CHAT_IMAGE_NOT_FOUND;
import static com.triples.rougether.userapi.chat.error.ChatErrorCode.CHAT_STORAGE_UNAVAILABLE;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.chat.dto.ChatImageCropRequest;
import com.triples.rougether.userapi.chat.dto.ChatImageResponse;
import com.triples.rougether.userapi.global.image.PrivatePhotoNormalizer;
import com.triples.rougether.userapi.global.image.PrivatePhotoNormalizer.InvalidPhotoException;
import com.triples.rougether.userapi.global.image.PrivatePhotoNormalizer.Photo;
import com.triples.rougether.userapi.global.image.PrivatePhotoNormalizer.PhotoBusyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatImageService {
    private final ChatImageTransactions transactions;
    private final ChatImageStorage storage;

    public ChatImageResponse upload(Long userId, Long roomId, MultipartFile file) {
        return upload(userId, roomId, file, ChatImageCropRequest.NONE);
    }

    // crop이 있으면 회전 보정 뒤 그 영역만 잘라 저장함. 응답의 크기는 자른 뒤 변환한 값임.
    public ChatImageResponse upload(Long userId, Long roomId, MultipartFile file, ChatImageCropRequest crop) {
        transactions.checkUploadable(userId, roomId);
        Photo photo = normalize(file, crop);
        // S3 전송 전에 key를 DB에 커밋함. 전송 도중 프로세스가 죽어도 만료 정리 대상이 남음.
        var reservation = transactions.reserve(userId, roomId, photo.width(), photo.height());
        try {
            storage.put(reservation.key(), photo.bytes());
        } catch (RuntimeException e) {
            log.warn("채팅 사진 저장 실패 - imageId={}", reservation.id(), e);
            abandon(reservation.id());
            throw new BusinessException(CHAT_STORAGE_UNAVAILABLE);
        }
        return transactions.complete(userId, reservation.id());
    }

    public byte[] read(Long userId, Long imageId) {
        String key = transactions.readableKey(userId, imageId);
        try {
            return storage.read(key);
        } catch (NoSuchKeyException e) {
            throw new BusinessException(CHAT_IMAGE_NOT_FOUND);
        } catch (RuntimeException e) {
            log.warn("채팅 사진 조회 실패 - imageId={}", imageId, e);
            throw new BusinessException(CHAT_STORAGE_UNAVAILABLE);
        }
    }

    public void cancel(Long userId, Long imageId) {
        transactions.cancel(userId, imageId);
    }

    // 저장 실패한 업로드가 미전송 한도를 차지하지 않게 함. 여기서 실패해도 원래 오류를 그대로 응답함.
    private void abandon(Long imageId) {
        try {
            transactions.abandon(imageId);
        } catch (RuntimeException e) {
            log.warn("저장 실패한 채팅 사진 만료 처리 실패 - imageId={}", imageId, e);
        }
    }

    private static Photo normalize(MultipartFile file, ChatImageCropRequest crop) {
        try {
            return PrivatePhotoNormalizer.normalize(file, crop.toCrop());
        } catch (InvalidPhotoException e) {
            throw new BusinessException(CHAT_IMAGE_INVALID);
        } catch (PhotoBusyException e) {
            throw new BusinessException(CHAT_STORAGE_UNAVAILABLE);
        }
    }
}
