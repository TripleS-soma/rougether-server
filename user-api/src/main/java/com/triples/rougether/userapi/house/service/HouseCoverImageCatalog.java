package com.triples.rougether.userapi.house.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.house.config.HouseCoverImageProperties;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

// 사용자에게 게시된 집 커버 key manifest와 생성·수정 입력 검증을 한곳에서 관리함.
@Component
public class HouseCoverImageCatalog {

    private static final int MAX_KEY_LENGTH = 255;
    private static final Pattern HOUSE_IMAGE_KEY =
            Pattern.compile("^house/.+\\.(png|jpe?g|webp)$");

    private final List<String> keys;
    private final Set<String> keySet;

    public HouseCoverImageCatalog(HouseCoverImageProperties properties) {
        this.keys = properties.keys().stream().distinct().sorted().toList();
        if (keys.stream().anyMatch(key -> key.length() > MAX_KEY_LENGTH
                || !HOUSE_IMAGE_KEY.matcher(key).matches())) {
            throw new IllegalStateException("집 커버 manifest에 올바르지 않은 asset key가 포함되어 있습니다.");
        }
        this.keySet = Set.copyOf(keys);
    }

    public List<String> keys() {
        return keys;
    }

    public void validatePublished(String coverImageKey) {
        if (coverImageKey != null && !keySet.contains(coverImageKey)) {
            throw new BusinessException(HouseErrorCode.HOUSE_COVER_IMAGE_INVALID);
        }
    }
}
