package com.triples.rougether.adminapi.asset.web;

import com.triples.rougether.adminapi.asset.service.AssetDeleteResult;
import com.triples.rougether.adminapi.asset.service.AssetStorageService;
import com.triples.rougether.domain.character.repository.CharacterPoseRepository;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin/assets")
public class AssetDeleteController {

    private static final Pattern CHARACTER_ASSET_KEY_PATTERN = Pattern.compile(
            "^characters/[A-Za-z0-9][A-Za-z0-9._/-]*\\.(png|jpg|jpeg|webp|gif)$");

    // code 파생 애니메이션 계약 key (user-api CharacterAnimations.of 와 동일 규칙).
    // DB 에 row 가 없어 참조 검사로는 안 잡히므로 code 를 뽑아 캐릭터 존재 여부로 막는다.
    private static final Pattern CHARACTER_ANIMATION_KEY_PATTERN = Pattern.compile(
            "^characters/([^/]+)/animations/(?:idle|pose-cycle|wave)\\.webp$");

    private final AssetStorageService storage;
    private final CharacterRepository characterRepository;
    private final CharacterPoseRepository poseRepository;

    public AssetDeleteController(
            AssetStorageService storage,
            CharacterRepository characterRepository,
            CharacterPoseRepository poseRepository) {
        this.storage = storage;
        this.characterRepository = characterRepository;
        this.poseRepository = poseRepository;
    }

    @DeleteMapping
    public AssetDeleteResult delete(@RequestParam("key") String key) {
        validateKey(key);
        if (characterRepository.existsByBaseAssetKey(key)
                || poseRepository.existsByAssetKey(key)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "DB에서 사용 중인 캐릭터 에셋입니다. DB 연결을 먼저 해제하세요.");
        }
        if (isRegisteredCharacterAnimation(key)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "등록된 캐릭터의 애니메이션 계약 에셋입니다. 캐릭터를 카탈로그에서 제거하기 전에는 삭제할 수 없습니다.");
        }
        if (!storage.exists(key)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "S3 에셋을 찾을 수 없습니다.");
        }
        return storage.archiveAndDelete(key);
    }

    private boolean isRegisteredCharacterAnimation(String key) {
        var matcher = CHARACTER_ANIMATION_KEY_PATTERN.matcher(key);
        return matcher.matches() && characterRepository.findByCode(matcher.group(1)).isPresent();
    }

    private void validateKey(String key) {
        if (!CHARACTER_ASSET_KEY_PATTERN.matcher(key).matches()
                || key.contains("..")
                || key.contains("//")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "characters/ 하위 이미지 object key만 삭제할 수 있습니다.");
        }
    }
}
