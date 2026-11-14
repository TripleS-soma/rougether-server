package com.triples.rougether.userapi.chat.dto;

import com.triples.rougether.userapi.global.image.PrivatePhotoNormalizer.Crop;
import com.triples.rougether.userapi.global.image.PrivatePhotoNormalizer.InvalidPhotoException;

// 사진 업로드의 자르기 영역(multipart 문자열 그대로). 네 값을 모두 보내거나 모두 생략해야 함.
public record ChatImageCropRequest(String x, String y, String width, String height) {
    public static final ChatImageCropRequest NONE = new ChatImageCropRequest(null, null, null, null);

    // 생략이면 null. 일부만 있거나 숫자가 아니거나 범위를 벗어나면 InvalidPhotoException.
    public Crop toCrop() {
        if (x == null && y == null && width == null && height == null) return null;
        if (x == null || y == null || width == null || height == null) throw new InvalidPhotoException();
        try {
            return new Crop(Double.parseDouble(x.trim()), Double.parseDouble(y.trim()),
                    Double.parseDouble(width.trim()), Double.parseDouble(height.trim()));
        } catch (NumberFormatException e) {
            throw new InvalidPhotoException();
        }
    }
}
