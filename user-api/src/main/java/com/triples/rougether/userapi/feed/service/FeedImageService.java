package com.triples.rougether.userapi.feed.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.feed.dto.FeedImageResponse;
import static com.triples.rougether.userapi.feed.error.FeedErrorCode.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@Service
@RequiredArgsConstructor
public class FeedImageService {
    private final FeedAccess access;
    private final FeedImageProcessor processor;
    private final FeedImageTransactions transactions;
    private final FeedImageStorage storage;

    public FeedImageResponse upload(Long userId, MultipartFile file) {
        access.active(userId);
        var photo = processor.process(file);
        // S3 전송 전에 key를 DB에 커밋함. 전송 도중 프로세스가 죽어도 만료 정리 대상이 남음.
        var reservation = transactions.reserve(userId, photo.width(), photo.height());
        try { storage.put(reservation.key(), photo.bytes()); }
        catch (RuntimeException e) { throw new BusinessException(FEED_STORAGE_UNAVAILABLE); }
        return transactions.complete(userId, reservation.id());
    }
    public byte[] read(Long userId, Long imageId) {
        String key = transactions.readableKey(userId, imageId);
        try { return storage.read(key); }
        catch (NoSuchKeyException e) { throw new BusinessException(FEED_IMAGE_NOT_FOUND); }
        catch (RuntimeException e) { throw new BusinessException(FEED_STORAGE_UNAVAILABLE); }
    }
    public void cancel(Long userId, Long imageId) { transactions.cancel(userId, imageId); }
}
