package com.triples.rougether.userapi.character;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.CharacterAccessoryRenderProfile;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.CharacterAccessoryRenderProfileRepository;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterAccessoryRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.character.dto.CharacterAccessoriesResponse;
import com.triples.rougether.userapi.character.dto.MyCharacterListResponse;
import com.triples.rougether.userapi.character.error.CharacterAccessoryErrorCode;
import com.triples.rougether.userapi.character.service.CharacterAccessoryCommandService;
import com.triples.rougether.userapi.character.service.MyCharacterCommandService;
import com.triples.rougether.userapi.character.service.MyCharacterQueryService;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import com.triples.rougether.userapi.room.service.RoomQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class CharacterAccessoryIntegrationTest {

    @Autowired private CharacterAccessoryCommandService characterAccessoryCommandService;
    @Autowired private MyCharacterCommandService myCharacterCommandService;
    @Autowired private MyCharacterQueryService myCharacterQueryService;
    @Autowired private RoomQueryService roomQueryService;
    @Autowired private UserRepository userRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private UserCharacterRepository userCharacterRepository;
    @Autowired private UserCharacterAccessoryRepository userCharacterAccessoryRepository;
    @Autowired private CharacterAccessoryRenderProfileRepository renderProfileRepository;
    @Autowired private ThemeRepository themeRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private UserItemRepository userItemRepository;

    private User user;
    private UserCharacter cat;
    private UserCharacter bear;
    private UserItem sunglasses;
    private UserItem glasses;
    private UserItem crown;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.signUp("accessory-" + System.nanoTime() + "@rougether.dev"));
        cat = userCharacterRepository.save(UserCharacter.of(
                user, character("accessory_cat"), Instant.now(), true));
        bear = userCharacterRepository.save(UserCharacter.of(
                user, character("accessory_bear"), Instant.now(), false));

        Theme theme = themeRepository.save(
                new Theme("accessory_theme_" + System.nanoTime(), "악세사리", null, true));
        sunglasses = own(accessory(theme, "선글라스", "eyewear", "sunglasses.png", true));
        glasses = own(accessory(theme, "검은 뿔테안경", "eyewear", "glasses.png", true));
        crown = own(accessory(theme, "왕관", "headwear", "crown.png", true));
        support(sunglasses.getItem(), cat.getCharacter(), "0.50000", "0.31000", "0.5200", 20);
        support(glasses.getItem(), cat.getCharacter(), "0.50000", "0.31000", "0.5200", 20);
        support(crown.getItem(), cat.getCharacter(), "0.50000", "0.17000", "0.3500", 10);
        support(glasses.getItem(), bear.getCharacter(), "0.50000", "0.30000", "0.5000", 20);
    }

    @Test
    void 같은_슬롯에_새_악세사리를_적용하면_교체하고_다른_슬롯은_유지한다() {
        characterAccessoryCommandService.equip(user.getId(), cat.getId(), sunglasses.getId());
        characterAccessoryCommandService.equip(user.getId(), cat.getId(), crown.getId());

        CharacterAccessoriesResponse response =
                characterAccessoryCommandService.equip(user.getId(), cat.getId(), glasses.getId());

        assertThat(response.userCharacterId()).isEqualTo(cat.getId());
        assertThat(response.items()).extracting("userItemId")
                .containsExactly(glasses.getId(), crown.getId());
        assertThat(response.items()).extracting("characterSlotType")
                .containsExactly("eyewear", "headwear");
        assertThat(response.items().get(0).renderProfiles()).singleElement()
                .satisfies(profile -> {
                    assertThat(profile.canvasWidth()).isEqualTo(180);
                    assertThat(profile.canvasHeight()).isEqualTo(172);
                    assertThat(profile.assetWidth()).isEqualTo(320);
                    assertThat(profile.assetHeight()).isEqualTo(160);
                });
        assertThat(userCharacterAccessoryRepository.findByUserCharacterId(cat.getId())).hasSize(2);
    }

    @Test
    void 캐릭터를_바꿨다가_돌아와도_각_캐릭터의_착용_상태가_유지된다() {
        characterAccessoryCommandService.equip(user.getId(), cat.getId(), sunglasses.getId());
        characterAccessoryCommandService.equip(user.getId(), bear.getId(), glasses.getId());

        myCharacterCommandService.select(user.getId(), bear.getCharacter().getId());
        myCharacterCommandService.select(user.getId(), cat.getCharacter().getId());

        MyCharacterListResponse response = myCharacterQueryService.getMyCharacters(user.getId());
        var catResponse = response.items().stream()
                .filter(item -> item.userCharacterId().equals(cat.getId()))
                .findFirst().orElseThrow();
        var bearResponse = response.items().stream()
                .filter(item -> item.userCharacterId().equals(bear.getId()))
                .findFirst().orElseThrow();
        assertThat(catResponse.accessories()).extracting("userItemId")
                .containsExactly(sunglasses.getId());
        assertThat(bearResponse.accessories()).extracting("userItemId")
                .containsExactly(glasses.getId());
    }

    @Test
    void 방_캐릭터_응답에도_저장된_악세사리가_따라붙는다() {
        characterAccessoryCommandService.equip(user.getId(), cat.getId(), sunglasses.getId());
        characterAccessoryCommandService.equip(user.getId(), cat.getId(), crown.getId());

        RoomResponse response = roomQueryService.getMyRoom(user.getId());

        assertThat(response.character()).isNotNull();
        assertThat(response.character().accessories()).extracting("userItemId")
                .containsExactly(sunglasses.getId(), crown.getId());
    }

    @Test
    void 슬롯_해제는_멱등이고_다른_슬롯을_지우지_않는다() {
        characterAccessoryCommandService.equip(user.getId(), cat.getId(), sunglasses.getId());
        characterAccessoryCommandService.equip(user.getId(), cat.getId(), crown.getId());

        CharacterAccessoriesResponse first =
                characterAccessoryCommandService.unequip(user.getId(), cat.getId(), "eyewear");
        CharacterAccessoriesResponse second =
                characterAccessoryCommandService.unequip(user.getId(), cat.getId(), "eyewear");

        assertThat(first.items()).extracting("userItemId").containsExactly(crown.getId());
        assertThat(second.items()).extracting("userItemId").containsExactly(crown.getId());
    }

    @Test
    void 다른_사용자의_아이템은_적용할_수_없다() {
        User other = userRepository.save(User.signUp("accessory-other-" + System.nanoTime() + "@rougether.dev"));
        UserItem otherItem = userItemRepository.save(UserItem.create(other, sunglasses.getItem()));

        assertThatThrownBy(() ->
                characterAccessoryCommandService.equip(user.getId(), cat.getId(), otherItem.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(CharacterAccessoryErrorCode.ACCESSORY_NOT_OWNED));
    }

    @Test
    void 일반_가구와_비활성_악세사리는_적용할_수_없다() {
        Theme theme = sunglasses.getItem().getTheme();
        UserItem furniture = own(itemRepository.save(new Item(
                theme, "furniture", "positioned", null, null,
                "의자", CurrencyType.DIAMOND, 100, "items/accessory/chair.png", false, true)));
        UserItem inactive = own(accessory(theme, "회수된 모자", "headwear", "inactive.png", false));

        assertThatThrownBy(() ->
                characterAccessoryCommandService.equip(user.getId(), cat.getId(), furniture.getId()))
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(CharacterAccessoryErrorCode.ACCESSORY_INVALID));
        assertThatThrownBy(() ->
                characterAccessoryCommandService.equip(user.getId(), cat.getId(), inactive.getId()))
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(CharacterAccessoryErrorCode.ACCESSORY_INVALID));
    }

    @Test
    void 렌더_프로필이_없는_캐릭터에는_악세사리를_적용할_수_없다() {
        assertThatThrownBy(() ->
                characterAccessoryCommandService.equip(user.getId(), bear.getId(), sunglasses.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode().code())
                        .isEqualTo("CHARACTER_ACCESSORY_UNSUPPORTED_CHARACTER"));
    }

    private Character character(String code) {
        return characterRepository.save(
                new Character(code + "_" + System.nanoTime(), code, "characters/" + code + ".png", 10, true));
    }

    private Item accessory(Theme theme, String name, String slot, String filename, boolean active) {
        return itemRepository.save(new Item(
                theme, "character_accessory", "character", null, slot,
                name, null, null, "items/accessories/" + filename, false, active));
    }

    private UserItem own(Item item) {
        return userItemRepository.save(UserItem.create(user, item));
    }

    private void support(
            Item item,
            Character character,
            String positionX,
            String positionY,
            String widthRatio,
            int zIndex) {
        renderProfileRepository.save(new CharacterAccessoryRenderProfile(
                item,
                character,
                CharacterAccessoryRenderProfile.DEFAULT_STATE,
                item.getAssetKey(),
                180,
                172,
                320,
                160,
                new BigDecimal(positionX),
                new BigDecimal(positionY),
                new BigDecimal(widthRatio),
                0,
                zIndex));
    }
}
