package com.triples.rougether.userapi.house.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.house.config.HouseCoverImageProperties;
import com.triples.rougether.userapi.house.config.HouseCoverImageProperties.Entry;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

// 사용자에게 게시된 집 커버 key manifest와 생성·수정 입력 검증을 한곳에서 관리함.
@Component
public class HouseCoverImageCatalog {

    private static final int MAX_CODE_LENGTH = 50;
    private static final int MAX_NAME_LENGTH = 30;
    private static final int MAX_KEY_LENGTH = 255;
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Pattern HOUSE_IMAGE_KEY =
            Pattern.compile("^house/.+\\.(png|jpe?g|webp)$");

    private final List<PublishedCoverImage> items;
    private final Set<String> keySet;

    public HouseCoverImageCatalog(HouseCoverImageProperties properties) {
        List<Entry> entries = properties.items();
        if (entries.stream().anyMatch(this::isInvalid)
                || entries.stream().map(Entry::code).distinct().count() != entries.size()
                || entries.stream().map(Entry::coverImageKey).distinct().count() != entries.size()) {
            throw new IllegalStateException("집 커버 manifest에 올바르지 않거나 중복된 항목이 포함되어 있습니다.");
        }
        this.items = entries.stream()
                .map(entry -> new PublishedCoverImage(entry.code(), entry.name(), entry.coverImageKey()))
                .sorted(Comparator.comparing(PublishedCoverImage::code))
                .toList();
        this.keySet = items.stream()
                .map(PublishedCoverImage::coverImageKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private boolean isInvalid(Entry entry) {
        return entry.code() == null
                || entry.code().length() > MAX_CODE_LENGTH
                || !CODE.matcher(entry.code()).matches()
                || entry.name() == null
                || entry.name().isBlank()
                || entry.name().length() > MAX_NAME_LENGTH
                || entry.coverImageKey() == null
                || entry.coverImageKey().length() > MAX_KEY_LENGTH
                || !HOUSE_IMAGE_KEY.matcher(entry.coverImageKey()).matches();
    }

    public List<PublishedCoverImage> items() {
        return items;
    }

    public void validatePublished(String coverImageKey) {
        if (coverImageKey != null && !keySet.contains(coverImageKey)) {
            throw new BusinessException(HouseErrorCode.HOUSE_COVER_IMAGE_INVALID);
        }
    }

    public record PublishedCoverImage(String code, String name, String coverImageKey) {
    }
}
