package com.triples.rougether.userapi.aiuser.service;

import com.triples.rougether.domain.onboarding.entity.OnboardingHouseChoice;
import com.triples.rougether.userapi.category.dto.CategoryCreateRequest;
import com.triples.rougether.userapi.category.service.CategoryService;
import com.triples.rougether.userapi.character.service.MyCharacterCommandService;
import com.triples.rougether.userapi.character.service.CharacterAccessoryCommandService;
import com.triples.rougether.userapi.chat.dto.ChatSendRequest;
import com.triples.rougether.userapi.chat.service.ChatCommandService;
import com.triples.rougether.userapi.feed.dto.FeedCreateRequest;
import com.triples.rougether.userapi.feed.dto.FeedCommentRequest;
import com.triples.rougether.userapi.feed.dto.FeedUpdateRequest;
import com.triples.rougether.userapi.feed.service.FeedCommandService;
import com.triples.rougether.userapi.gacha.dto.GachaDrawRequest;
import com.triples.rougether.userapi.gacha.service.GachaService;
import com.triples.rougether.userapi.guestbook.dto.GuestbookCreateRequest;
import com.triples.rougether.userapi.guestbook.service.GuestbookService;
import com.triples.rougether.userapi.house.dto.HouseCreateRequest;
import com.triples.rougether.userapi.house.dto.HouseMissionCreateRequest;
import com.triples.rougether.userapi.house.service.*;
import com.triples.rougether.userapi.market.dto.PlaceOrderRequest;
import com.triples.rougether.userapi.market.service.*;
import com.triples.rougether.userapi.member.dto.MemberUpdateRequest;
import com.triples.rougether.userapi.member.service.MemberService;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsRequest;
import com.triples.rougether.userapi.onboarding.service.*;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest;
import com.triples.rougether.userapi.room.service.RoomCommandService;
import com.triples.rougether.userapi.room.service.RoomCobwebService;
import com.triples.rougether.userapi.routine.dto.*;
import com.triples.rougether.userapi.routine.service.*;
import com.triples.rougether.userapi.shop.service.ShopCommandService;
import com.triples.rougether.userapi.todo.dto.*;
import com.triples.rougether.userapi.todo.service.TodoService;
import com.triples.rougether.userapi.attendance.service.AttendanceEventService;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AiUserActionExecutor {
    private final ObjectMapper mapper;
    private final Validator validator;
    private final FeedCommandService feed;
    private final ChatCommandService chat;
    private final TodoService todos;
    private final RoutineService routines;
    private final RoutineLogService routineLogs;
    private final CategoryService categories;
    private final OnboardingCommandService onboarding;
    private final OnboardingHouseService onboardingHouse;
    private final HouseJoinService joins;
    private final HouseMemberCommandService members;
    private final HouseCommandService houses;
    private final HouseMissionService missions;
    private final ShopCommandService shop;
    private final GachaService gacha;
    private final RoomCommandService room;
    private final MyCharacterCommandService characters;
    private final AttendanceEventService attendance;
    private final MarketOrderCommandService orders;
    private final MarketAssetService assets;
    private final HouseCheerService cheers;
    private final GuestbookService guestbooks;
    private final RoomCobwebService cobwebs;
    private final CharacterAccessoryCommandService accessories;
    private final MemberService profiles;

    public enum Action {
        WAIT, FEED_POST, FEED_COMMENT, FEED_LIKE, FEED_UPDATE, FEED_DELETE,
        CHAT_SEND, CHAT_OPEN, CHAT_READ, TODO_CREATE, TODO_UPDATE, TODO_COMPLETE, TODO_CANCEL, TODO_DELETE,
        CATEGORY_CREATE, ROUTINE_CREATE, ROUTINE_UPDATE, ROUTINE_COMPLETE, ROUTINE_SKIP, ROUTINE_CANCEL, ROUTINE_DELETE,
        ONBOARD_GOALS, ONBOARD_CHARACTER, ONBOARD_HOUSE, HOUSE_JOIN, HOUSE_REQUEST, HOUSE_LEAVE, HOUSE_CREATE,
        MISSION_CREATE, MISSION_CONTRIBUTE, MISSION_CLAIM, SHOP_PURCHASE, GACHA_DRAW, ROOM_LAYOUT,
        CHARACTER_SELECT, ATTENDANCE, MARKET_ORDER, MARKET_CANCEL, MARKET_AMEND, MARKET_ISSUE,
        HOUSE_CHEER, GUESTBOOK_WRITE, ROOM_CLEAN, MEMBER_ROOM_CLEAN,
        ACCESSORY_EQUIP, ACCESSORY_UNEQUIP, PROFILE_UPDATE
    }

    // 실행자는 claim의 userId만 사용함. 모델 출력에서 작성자/actor 권한을 받지 않음.
    public Object execute(AiUserJobs.Job job, Action action, JsonNode payload) {
        if (payload == null || !payload.isObject() || payload.has("userId") || payload.has("authorId")) bad("Invalid action payload");
        Long user = job.userId();
        UUID key = UUID.nameUUIDFromBytes(("ai-action:" + job.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return switch (action) {
            case WAIT -> "WAIT";
            case FEED_POST -> {
                var p = mapper.createObjectNode().setAll((tools.jackson.databind.node.ObjectNode) payload);
                p.remove("topic");
                p.put("clientPostId", key.toString());
                yield feed.create(user, dto(p, FeedCreateRequest.class));
            }
            case FEED_COMMENT -> feed.comment(user, id(payload,"postId"),
                    valid(new FeedCommentRequest(key, text(payload,"content"),
                            job.sourceType().equals("FEED_COMMENT") ? job.sourceId()
                                    : payload.hasNonNull("replyToCommentId") ? id(payload,"replyToCommentId") : null)));
            case FEED_LIKE -> { feed.like(user,id(payload,"postId"),payload.path("liked").asBoolean(true)); yield "OK"; }
            case FEED_UPDATE -> { feed.update(user,id(payload,"postId"),dto(payload.path("request"),FeedUpdateRequest.class)); yield "OK"; }
            case FEED_DELETE -> { feed.delete(user,id(payload,"postId")); yield "OK"; }
            case CHAT_OPEN -> chat.openHouse(user,id(payload,"houseId"));
            case CHAT_SEND -> chat.send(user,id(payload,"roomId"),valid(new ChatSendRequest(key,text(payload,"content"))));
            case CHAT_READ -> chat.read(user,id(payload,"roomId"),payload.path("sequence").asLong(-1));
            case TODO_CREATE -> todos.create(user,dto(payload,TodoCreateRequest.class));
            case TODO_UPDATE -> todos.update(user,id(payload,"todoId"),dto(payload.path("request"),TodoUpdateRequest.class));
            case TODO_COMPLETE -> todos.complete(user,id(payload,"todoId"));
            case TODO_CANCEL -> todos.cancelComplete(user,id(payload,"todoId"));
            case TODO_DELETE -> { todos.delete(user,id(payload,"todoId")); yield "OK"; }
            case CATEGORY_CREATE -> categories.create(user,dto(payload,CategoryCreateRequest.class));
            case ROUTINE_CREATE -> routines.create(user,dto(payload,RoutineCreateRequest.class));
            case ROUTINE_UPDATE -> routines.update(user,id(payload,"routineId"),dto(payload.path("request"),RoutineUpdateRequest.class));
            case ROUTINE_COMPLETE -> routineLogs.complete(user,id(payload,"routineId"),dto(payload.path("request"),RoutineLogCreateRequest.class));
            case ROUTINE_SKIP -> routineLogs.skip(user,id(payload,"routineId"),LocalDate.parse(text(payload,"date")));
            case ROUTINE_CANCEL -> routineLogs.cancel(user,id(payload,"routineId"),LocalDate.parse(text(payload,"date")));
            case ROUTINE_DELETE -> { routines.delete(user,id(payload,"routineId")); yield "OK"; }
            case ONBOARD_GOALS -> onboarding.replaceGoals(user,dto(payload,OnboardingGoalsRequest.class));
            case ONBOARD_CHARACTER -> onboarding.selectCharacter(user,id(payload,"characterId"));
            case ONBOARD_HOUSE -> onboardingHouse.select(user,OnboardingHouseChoice.valueOf(text(payload,"choice")));
            case HOUSE_JOIN -> joins.joinByCode(user,text(payload,"inviteCode"));
            case HOUSE_REQUEST -> joins.requestJoin(user,id(payload,"houseId"));
            case HOUSE_LEAVE -> { members.leave(user,id(payload,"houseId")); yield "OK"; }
            case HOUSE_CREATE -> houses.create(user,dto(payload,HouseCreateRequest.class));
            case MISSION_CREATE -> missions.create(user,id(payload,"houseId"),dto(payload.path("request"),HouseMissionCreateRequest.class));
            case MISSION_CONTRIBUTE -> missions.contribute(user,id(payload,"houseId"),id(payload,"missionId"));
            case MISSION_CLAIM -> missions.claim(user,id(payload,"houseId"),id(payload,"missionId"));
            case SHOP_PURCHASE -> shop.purchase(user,id(payload,"itemId"));
            case GACHA_DRAW -> gacha.draw(user,id(payload,"gachaId"),dto(payload.path("request"),GachaDrawRequest.class));
            case ROOM_LAYOUT -> room.updateLayout(user,dto(payload,RoomLayoutUpdateRequest.class));
            case CHARACTER_SELECT -> characters.select(user,id(payload,"characterId"));
            case ATTENDANCE -> attendance.checkIn(user);
            case MARKET_ORDER -> {
                var p=mapper.createObjectNode().setAll((tools.jackson.databind.node.ObjectNode) payload);
                p.put("requestId",key.toString());
                yield orders.place(user,dto(p,PlaceOrderRequest.class));
            }
            case MARKET_CANCEL -> orders.cancel(user,id(payload,"orderId"),key.toString());
            case MARKET_AMEND -> {
                int quantity=payload.path("quantity").asInt(0);
                if (quantity<1 || quantity>10) bad("Invalid quantity");
                yield orders.amend(user,id(payload,"orderId"),key.toString(),quantity);
            }
            case MARKET_ISSUE -> {
                int supply=payload.path("totalSupply").asInt(0);
                if (supply<1 || supply>10) bad("Invalid supply");
                yield assets.issue(user,id(payload,"userItemId"),supply);
            }
            case HOUSE_CHEER -> cheers.cheer(user,id(payload,"houseId"),id(payload,"membershipId"),text(payload,"typeCode"));
            case GUESTBOOK_WRITE -> guestbooks.write(user,id(payload,"roomOwnerId"),
                    valid(new GuestbookCreateRequest(id(payload,"houseId"),text(payload,"content"))));
            case ROOM_CLEAN -> cobwebs.cleanMyRoom(user);
            case MEMBER_ROOM_CLEAN -> cobwebs.cleanHouseMemberRoom(user,id(payload,"houseId"),id(payload,"membershipId"));
            case ACCESSORY_EQUIP -> accessories.equip(user,id(payload,"userCharacterId"),id(payload,"userItemId"));
            case ACCESSORY_UNEQUIP -> accessories.unequip(user,id(payload,"userCharacterId"),text(payload,"characterSlotType"));
            case PROFILE_UPDATE -> {
                var updated=profiles.updateMe(user,dto(payload,MemberUpdateRequest.class));
                // 전체 MeResponse 대신 모델에 필요한 공개 프로필 결과만 실행 이력에 남김.
                yield new ProfileResult(updated.nickname(),updated.bio());
            }
        };
    }
    public record ProfileResult(String nickname,String bio) {}
    private <T> T dto(JsonNode payload,Class<T> type) { return valid(mapper.convertValue(payload,type)); }
    private <T> T valid(T request) {
        if (request == null || !validator.validate(request).isEmpty()) bad("Invalid action request");
        return request;
    }
    static long id(JsonNode node,String key) {
        long value=node.path(key).asLong(0);
        if (value<=0) bad("Invalid ID: " + key);
        return value;
    }
    static String text(JsonNode node,String key) {
        String value=node.path(key).asString("");
        if (value.isBlank()) bad("Missing " + key);
        return value;
    }
    static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
