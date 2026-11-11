package com.triples.rougether.userapi.feed.service;

import static com.triples.rougether.userapi.feed.error.FeedErrorCode.FEED_IMAGE_INVALID;
import static com.triples.rougether.userapi.feed.error.FeedErrorCode.FEED_STORAGE_UNAVAILABLE;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.global.image.PrivatePhotoNormalizer;
import com.triples.rougether.userapi.global.image.PrivatePhotoNormalizer.InvalidPhotoException;
import com.triples.rougether.userapi.global.image.PrivatePhotoNormalizer.Photo;
import com.triples.rougether.userapi.global.image.PrivatePhotoNormalizer.PhotoBusyException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

// 사진 규격은 채팅과 공유함. 여기서는 피드 오류 코드로만 바꿈.
@Component
public class FeedImageProcessor {
    public Photo process(MultipartFile file) {
        try {
            return PrivatePhotoNormalizer.normalize(file);
        } catch (InvalidPhotoException e) {
            throw new BusinessException(FEED_IMAGE_INVALID);
        } catch (PhotoBusyException e) {
            throw new BusinessException(FEED_STORAGE_UNAVAILABLE);
        }
    }
}
