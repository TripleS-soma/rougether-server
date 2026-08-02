package com.triples.rougether.adminapi.characterpose.service;

import com.triples.rougether.adminapi.asset.service.AssetStorageService;
import com.triples.rougether.adminapi.characterpose.dto.CharacterPoseAdminRequest;
import com.triples.rougether.adminapi.characterpose.dto.CharacterPoseAdminResponse;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.CharacterPose;
import com.triples.rougether.domain.character.repository.CharacterPoseRepository;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CharacterPoseAdminService {

    private static final Pattern ASSET_KEY_PATTERN = Pattern.compile(
            "^characters/[A-Za-z0-9][A-Za-z0-9._/-]*\\.(png|jpg|jpeg|webp|gif)$");

    private final CharacterRepository characterRepository;
    private final CharacterPoseRepository poseRepository;
    private final AssetStorageService assetStorageService;

    public CharacterPoseAdminService(
            CharacterRepository characterRepository,
            CharacterPoseRepository poseRepository,
            AssetStorageService assetStorageService) {
        this.characterRepository = characterRepository;
        this.poseRepository = poseRepository;
        this.assetStorageService = assetStorageService;
    }

    @Transactional(readOnly = true)
    public List<CharacterPoseAdminResponse> list() {
        return poseRepository.findAllWithCharacter().stream()
                .map(CharacterPoseAdminResponse::of)
                .toList();
    }

    @Transactional
    public CharacterPoseAdminResponse upsert(CharacterPoseAdminRequest request) {
        validateAssetKey(request.assetKey());
        if (!assetStorageService.exists(request.assetKey())) {
            throw new IllegalArgumentException("S3에서 에셋을 찾을 수 없습니다: " + request.assetKey());
        }

        Character character = characterRepository.findByCode(request.characterCode())
                .orElseThrow(() -> new NoSuchElementException(
                        "캐릭터를 찾을 수 없습니다: " + request.characterCode()));

        CharacterPose pose = poseRepository.findByCharacterIdAndCode(
                        character.getId(), request.code())
                .orElseGet(() -> new CharacterPose(
                        character,
                        request.code(),
                        request.assetKey(),
                        request.sortOrder(),
                        request.active()));
        pose.update(
                request.code(),
                request.assetKey(),
                request.sortOrder(),
                request.active());
        return CharacterPoseAdminResponse.of(poseRepository.save(pose));
    }

    @Transactional
    public void delete(Long poseId) {
        CharacterPose pose = poseRepository.findById(poseId)
                .orElseThrow(() -> new NoSuchElementException(
                        "캐릭터 포즈를 찾을 수 없습니다: " + poseId));
        poseRepository.delete(pose);
    }

    private void validateAssetKey(String key) {
        if (!ASSET_KEY_PATTERN.matcher(key).matches()
                || key.contains("..")
                || key.contains("//")) {
            throw new IllegalArgumentException(
                    "assetKey는 전체 URL이 아닌 characters/ 하위 이미지 object key여야 합니다.");
        }
    }
}
