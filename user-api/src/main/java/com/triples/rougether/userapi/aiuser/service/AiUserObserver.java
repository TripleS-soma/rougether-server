package com.triples.rougether.userapi.aiuser.service;

import com.triples.rougether.userapi.category.service.CategoryService;
import com.triples.rougether.userapi.character.service.MyCharacterQueryService;
import com.triples.rougether.userapi.chat.service.ChatQueryService;
import com.triples.rougether.userapi.feed.service.FeedQueryService;
import com.triples.rougether.userapi.gacha.service.GachaService;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import com.triples.rougether.userapi.house.service.HouseMissionService;
import com.triples.rougether.userapi.house.dto.HouseMemberListResponse.MemberSummary;
import com.triples.rougether.userapi.market.service.MarketQueryService;
import com.triples.rougether.userapi.onboarding.service.OnboardingQueryService;
import com.triples.rougether.userapi.room.service.RoomQueryService;
import com.triples.rougether.userapi.shop.service.ShopQueryService;
import com.triples.rougether.userapi.today.service.TodayService;
import com.triples.rougether.userapi.wallet.service.WalletQueryService;
import java.time.Clock;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AiUserObserver {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TodayService today;
    private final WalletQueryService wallets;
    private final RoomQueryService rooms;
    private final HouseQueryService houses;
    private final HouseMissionService missions;
    private final FeedQueryService feed;
    private final ChatQueryService chat;
    private final OnboardingQueryService onboarding;
    private final CategoryService categories;
    private final ShopQueryService shop;
    private final GachaService gacha;
    private final MarketQueryService market;
    private final MyCharacterQueryService myCharacters;
    private final AiUserSocialActivity social;
    private final tools.jackson.databind.ObjectMapper mapper;

    public record SourceReference(String type,Long id) {}
    public record HouseMembersSnapshot(Long houseId,List<MemberSummary> members,List<Long> cleanableMembershipIds) {}
    public record CharacterSnapshot(Long id,String code,String name) {}
    public record OwnedCharacterSnapshot(Long userCharacterId,Long characterId,String code,String name,
            boolean selected,List<AccessorySnapshot> accessories) {}
    public record AccessorySnapshot(Long userItemId,Long itemId,String name,String characterSlotType) {}

    public Map<String,Object> snapshot(AiUserJobs.Job job) {
        long user=job.userId();
        Map<String,Object> state=new LinkedHashMap<>();
        state.put("aiUserId",user);
        state.put("persona",jdbc.queryForObject("SELECT persona FROM ai_user_profiles WHERE user_id=?",String.class,user));
        state.put("personaKey",jdbc.queryForObject("SELECT ai_key FROM ai_user_profiles WHERE user_id=?",String.class,user));
        state.put("date",clock.instant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate());
        state.put("localTime",clock.instant().atZone(ZoneId.of("Asia/Seoul")).toLocalTime());
        state.put("source",source(user,job.sourceType(),job.sourceId()));
        if (job.sourceType().equals("SOCIAL_BROWSE")) return socialSnapshot(job,state);
        state.put("wallets",wallets.getWallets(user));
        state.put("today",today.today(user));
        state.put("onboarding",onboarding.getOnboarding(user));
        state.put("goals",onboarding.getGoals());
        // 텍스트 판단에는 이미지 포즈·합성 좌표가 필요하지 않으므로 식별·착용 상태만 전달함.
        state.put("characters",Map.of("items",onboarding.getCharacters().items().stream()
                .map(character -> new CharacterSnapshot(character.id(),character.code(),character.name())).toList()));
        state.put("myCharacters",Map.of("items",myCharacters.getMyCharacters(user).items().stream()
                .map(character -> new OwnedCharacterSnapshot(character.userCharacterId(),character.characterId(),
                        character.code(),character.name(),character.selected(),character.accessories().stream()
                        .map(accessory -> new AccessorySnapshot(accessory.userItemId(),accessory.itemId(),
                                accessory.name(),accessory.characterSlotType())).toList())).toList()));
        state.put("profile",jdbc.queryForMap("SELECT nickname,bio FROM users WHERE id=?",user));
        state.put("categories",categories.list(user,false));
        state.put("room",rooms.getMyRoom(user));
        state.put("houses",houses.getMyHouses(user));
        state.put("inventory",shop.getMyItems(user,null));
        state.put("shop",shop.getItems(user,null).items().stream().limit(12).toList());
        state.put("gacha",gacha.getGachaList());
        state.put("market",market.listAssets(user,0,5));
        state.put("publicHouses",houses.explore(user,0,5,null,true));
        var memberships=jdbc.queryForList("SELECT hm.house_id FROM house_members hm JOIN house h ON h.id=hm.house_id "
                + "WHERE hm.user_id=? AND hm.status='ACTIVE' AND h.deleted_at IS NULL ORDER BY hm.id LIMIT 5",Long.class,user);
        state.put("missions",memberships.stream().map(id -> missions.getMissions(user,id)).toList());
        var memberSnapshots=new java.util.ArrayList<HouseMembersSnapshot>();
        int memberBudget=32;
        for (Long houseId:memberships) {
            var visible=houses.getMembers(user,houseId).items().stream()
                    .filter(member -> !member.bot() && unblocked(user,member.userId()))
                    .limit(memberBudget).toList();
            var visibleIds=visible.stream().map(MemberSummary::membershipId).collect(java.util.stream.Collectors.toSet());
            var cleanable=jdbc.queryForList("SELECT hm.id FROM house_members hm JOIN room_cobwebs c ON c.room_user_id=hm.user_id "
                    + "WHERE hm.house_id=? AND hm.status='ACTIVE' AND c.cleaned_at IS NULL",Long.class,houseId)
                    .stream().filter(visibleIds::contains).toList();
            memberSnapshots.add(new HouseMembersSnapshot(houseId,visible,cleanable));
            memberBudget-=visible.size();
            if (memberBudget==0) break;
        }
        state.put("houseMembers",memberSnapshots);
        // source가 없는 주기 작업도 최근 글을 관찰함. 사람이 AI를 차단한 관계는 읽지 않음.
        List<Long> posts=jdbc.queryForList("SELECT f.id FROM feed_posts f JOIN users a ON a.id=f.author_id "
                + "WHERE f.deleted_at IS NULL AND a.deleted_at IS NULL AND a.is_bot=FALSE AND NOT EXISTS "
                + "(SELECT 1 FROM user_blocks b WHERE (b.blocker_user_id=? AND b.blocked_user_id=a.id) "
                + "OR (b.blocker_user_id=a.id AND b.blocked_user_id=?)) ORDER BY f.id DESC LIMIT 3",Long.class,user,user);
        state.put("feed",posts.stream().map(id -> feed.get(user,id)).toList());
        List<Map<String,Object>> memories=jdbc.queryForList("SELECT m.content,m.kind,m.source_type,m.source_id,j.context_sources "
                + "FROM ai_user_memories m JOIN ai_user_jobs j ON j.id=m.job_id "
                + "WHERE m.user_id=? AND m.expires_at>? ORDER BY m.id DESC LIMIT 12",user,AiUserJobs.timestamp(clock.instant()));
        state.put("actions",java.util.Arrays.stream(AiUserActionExecutor.Action.values()).map(Enum::name).toList());
        // 회고가 다른 사람 원문을 담을 수 있으므로 전달한 모든 원문의 접근권에 기억을 묶음.
        var references=new java.util.LinkedHashSet<SourceReference>();
        for (var house:memberSnapshots) for (var member:house.members())
            references.add(new SourceReference("MEMBERSHIP",member.membershipId()));
        if (job.sourceId()!=null) references.add(new SourceReference(job.sourceType(),job.sourceId()));
        for (Long id:posts) references.add(new SourceReference("FEED_POST",id));
        if (job.sourceType().equals("CHAT")) {
            var source=(Map<?,?>)state.get("source");
            for (var message:(List<?>)source.get("recentMessages"))
                references.add(new SourceReference("CHAT",((Number)((Map<?,?>)message).get("id")).longValue()));
        }
        var accessCache=new java.util.HashMap<SourceReference,Boolean>();
        var visibleMemories=new java.util.ArrayList<Map<String,Object>>();
        for (var memory:memories) {
            var origins=memoryReferences(memory);
            var combined=new java.util.LinkedHashSet<>(references);
            combined.addAll(origins);
            // 이전 회고를 다시 요약하더라도 원문의 권한을 잃지 않되, 참조가 무한히 늘어나지 않게 함.
            if (combined.size()>64 || origins.stream().anyMatch(ref ->
                    !accessCache.computeIfAbsent(ref,key -> accessible(user,key.type(),key.id())))) continue;
            references=combined;
            var recalled=new LinkedHashMap<String,Object>();
            recalled.put("content",memory.get("content"));
            recalled.put("kind",memory.get("kind"));
            var evidence=memoryEvidence(memory);
            if (evidence!=null) recalled.put("evidence",evidence);
            visibleMemories.add(recalled);
        }
        state.put("memories",visibleMemories);
        jdbc.update("UPDATE ai_user_jobs SET context_sources=?,memory_sources=NULL WHERE id=?",mapper.writeValueAsString(references),job.id());
        return state;
    }

    private Map<String,Object> socialSnapshot(AiUserJobs.Job job,Map<String,Object> state) {
        long user=job.userId();
        var context=social.observe(user);
        String persona=(String)state.get("personaKey");
        context.state().put("preference",persona.startsWith("social-")?"관심 있는 기존 글에 구체적인 댓글로 먼저 참여. 질문은 필요할 때만 함.":
                persona.startsWith("observe-")?"읽는 비중이 높음. 관심사와 직접 연결되는 글에만 가끔 댓글이나 좋아요.":
                persona.startsWith("decorate-")?"실제 앱의 구매, 배치, 거래와 관련된 주제에 참여. 소유하지 않은 물건이나 거래 경험은 만들지 않음.":
                "루틴, 기록, 작은 계획의 구체적인 새 주제를 공유하거나 관련 글에 반응.");
        var references=new java.util.LinkedHashSet<>(context.references());
        state.put("social",context.state());
        state.put("actions",context.state().get("allowedActions"));
        state.put("profile",jdbc.queryForMap("SELECT nickname,bio FROM users WHERE id=?",user));
        state.put("onboarding",onboarding.getOnboarding(user));
        state.put("today",today.today(user));
        var recent=new java.util.ArrayList<Map<String,Object>>();
        for (var row:jdbc.queryForList("SELECT id,action_name,action_payload,finished_at FROM ai_user_jobs "
                + "WHERE user_id=? AND status='DONE' AND action_name<>'WAIT' ORDER BY id DESC LIMIT 6",user)) {
            var payload=mapper.readTree(row.get("action_payload") instanceof byte[] bytes?
                    new String(bytes,java.nio.charset.StandardCharsets.UTF_8):row.get("action_payload").toString());
            var activity=new LinkedHashMap<String,Object>();
            activity.put("action",row.get("action_name"));
            activity.put("at",row.get("finished_at"));
            // 실행한 기능과 본인 계획 제목만 전달하고 타인의 원문/거래 상세를 재전송하지 않음.
            String title=payload.path("title").asText("");
            if (!title.isBlank()) activity.put("title",title.substring(0,Math.min(160,title.length())));
            recent.add(activity);
            references.add(new SourceReference("AI_JOB",((Number)row.get("id")).longValue()));
        }
        state.put("recentOwnActivity",recent);
        jdbc.update("UPDATE ai_user_jobs SET context_sources=?,memory_sources=NULL WHERE id=?",mapper.writeValueAsString(references),job.id());
        return state;
    }

    // 회고가 고유명사·의미를 빠뜨릴 수 있어 접근 가능한 원문을 짧게 함께 제공함.
    // 위 origins 접근 검사 이후에만 조회하며, 삭제/차단된 원문이나 회고는 전달하지 않음.
    private Map<String,Object> memoryEvidence(Map<String,Object> memory) {
        Object sourceId=memory.get("source_id");
        if (sourceId==null) return null;
        String type=(String)memory.get("source_type");
        List<Map<String,Object>> rows;
        if (type.equals("FEED_COMMENT")) {
            rows=jdbc.queryForList("SELECT c.author_id,c.content FROM feed_comments c JOIN users u ON u.id=c.author_id "
                    + "WHERE c.id=? AND c.deleted_at IS NULL AND u.deleted_at IS NULL",sourceId);
        } else if (type.equals("CHAT")) {
            rows=jdbc.queryForList("SELECT m.sender_user_id AS author_id,m.content FROM chat_messages m JOIN users u ON u.id=m.sender_user_id "
                    + "WHERE m.id=? AND u.deleted_at IS NULL",sourceId);
        } else return null;
        if (rows.isEmpty()) return null;
        var row=rows.getFirst();
        String content=(String)row.get("content");
        int end=content.offsetByCodePoints(0,Math.min(256,content.codePointCount(0,content.length())));
        return Map.of("sourceType",type,"sourceId",sourceId,"authorUserId",row.get("author_id"),
                "text",content.substring(0,end),"truncated",end<content.length());
    }

    public Object source(long user,String type,Long sourceId) {
        if (type.equals("SCHEDULE") || type.equals("SOCIAL_BROWSE")) return Map.of("type",type);
        if (sourceId==null) unavailable();
        if (type.equals("FEED_POST")) {
            requireUnblocked(user,jdbc.queryForObject("SELECT author_id FROM feed_posts WHERE id=? AND deleted_at IS NULL",Long.class,sourceId));
            return Map.of("type",type,"post",feed.get(user,sourceId));
        }
        if (type.equals("FEED_COMMENT")) {
            var comment=jdbc.queryForMap("SELECT post_id,author_id,content FROM feed_comments WHERE id=? AND deleted_at IS NULL",sourceId);
            requireUnblocked(user,((Number)comment.get("author_id")).longValue());
            long post=((Number)comment.get("post_id")).longValue();
            requireUnblocked(user,jdbc.queryForObject("SELECT author_id FROM feed_posts WHERE id=? AND deleted_at IS NULL",Long.class,post));
            return Map.of("type",type,"comment",comment,"post",feed.get(user,post));
        }
        if (type.equals("CHAT")) {
            var message=jdbc.queryForMap("SELECT room_id,sender_user_id,content FROM chat_messages WHERE id=?",sourceId);
            requireUnblocked(user,((Number)message.get("sender_user_id")).longValue());
            long room=((Number)message.get("room_id")).longValue();
            chat.requireReadable(user,room);
            var recent=jdbc.queryForList("SELECT m.id,m.sender_user_id,m.content,m.message_sequence FROM chat_messages m "
                    + "JOIN users u ON u.id=m.sender_user_id WHERE m.room_id=? AND u.deleted_at IS NULL AND NOT EXISTS "
                    + "(SELECT 1 FROM user_blocks b WHERE (b.blocker_user_id=? AND b.blocked_user_id=u.id) "
                    + "OR (b.blocker_user_id=u.id AND b.blocked_user_id=?)) ORDER BY m.message_sequence DESC LIMIT 12",room,user,user);
            return Map.of("type",type,"message",message,"room",chat.get(user,room),"recentMessages",recent);
        }
        unavailable();
        return null;
    }

    public void requireResponseTarget(AiUserJobs.Job job,AiUserActionExecutor.Action action,tools.jackson.databind.JsonNode payload) {
        if (job.sourceType().startsWith("FEED_") && !java.util.Set.of(AiUserActionExecutor.Action.FEED_COMMENT,
                AiUserActionExecutor.Action.FEED_LIKE,AiUserActionExecutor.Action.WAIT).contains(action)) unavailable();
        if (job.sourceType().equals("SOCIAL_BROWSE")) {
            if (!java.util.Set.of(AiUserActionExecutor.Action.WAIT,AiUserActionExecutor.Action.FEED_POST,
                    AiUserActionExecutor.Action.FEED_COMMENT,AiUserActionExecutor.Action.FEED_LIKE).contains(action)) unavailable();
            if (action==AiUserActionExecutor.Action.FEED_COMMENT || action==AiUserActionExecutor.Action.FEED_LIKE) {
                Long post=AiUserActionExecutor.id(payload,"postId");
                String raw=jdbc.queryForObject("SELECT context_sources FROM ai_user_jobs WHERE id=?",String.class,job.id());
                if (!contextReferences(raw).contains(new SourceReference("FEED_POST",post))) unavailable();
            }
        }
        if (action==AiUserActionExecutor.Action.HOUSE_CHEER || action==AiUserActionExecutor.Action.MEMBER_ROOM_CLEAN) {
            var target=memberTarget(job.userId(),AiUserActionExecutor.id(payload,"membershipId"));
            if (((Number)target.get("house_id")).longValue()!=AiUserActionExecutor.id(payload,"houseId")) unavailable();
        }
        if (action==AiUserActionExecutor.Action.GUESTBOOK_WRITE) {
            long owner=AiUserActionExecutor.id(payload,"roomOwnerId");
            requireUnblocked(job.userId(),owner);
            Integer members=jdbc.queryForObject("SELECT COUNT(*) FROM house_members WHERE house_id=? "
                    + "AND user_id IN (?,?) AND status='ACTIVE'",Integer.class,AiUserActionExecutor.id(payload,"houseId"),job.userId(),owner);
            if (members!=(owner==job.userId()?1:2)) unavailable();
        }
        if (action==AiUserActionExecutor.Action.CHAT_SEND && job.sourceType().equals("CHAT")) {
            Long room=jdbc.queryForObject("SELECT room_id FROM chat_messages WHERE id=?",Long.class,job.sourceId());
            if (room!=AiUserActionExecutor.id(payload,"roomId")) unavailable();
        }
        if (action==AiUserActionExecutor.Action.FEED_COMMENT && job.sourceType().startsWith("FEED_")) {
            Long post=job.sourceType().equals("FEED_POST")?job.sourceId():
                    jdbc.queryForObject("SELECT post_id FROM feed_comments WHERE id=?",Long.class,job.sourceId());
            if (post!=AiUserActionExecutor.id(payload,"postId")) unavailable();
        }
    }
    boolean accessible(long user,String type,Long id) {
        try {
            if (type.equals("SCHEDULE") || type.equals("SOCIAL_BROWSE")) return true;
            if (id==null) return false;
            if (type.equals("FEED_POST")) {
                requireUnblocked(user,jdbc.queryForObject("SELECT author_id FROM feed_posts WHERE id=? AND deleted_at IS NULL",Long.class,id));
            } else if (type.equals("FEED_COMMENT")) {
                var comment=jdbc.queryForMap("SELECT post_id,author_id FROM feed_comments WHERE id=? AND deleted_at IS NULL",id);
                requireUnblocked(user,((Number)comment.get("author_id")).longValue());
                requireUnblocked(user,jdbc.queryForObject("SELECT author_id FROM feed_posts WHERE id=? AND deleted_at IS NULL",Long.class,comment.get("post_id")));
            } else if (type.equals("CHAT")) {
                var message=jdbc.queryForMap("SELECT room_id,sender_user_id FROM chat_messages WHERE id=?",id);
                requireUnblocked(user,((Number)message.get("sender_user_id")).longValue());
                chat.requireReadable(user,((Number)message.get("room_id")).longValue());
            } else if (type.equals("MEMBERSHIP")) {
                memberTarget(user,id);
            } else if (type.equals("AI_JOB")) {
                if (jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_jobs WHERE id=? AND user_id=? AND status='DONE'",Integer.class,id,user)!=1)
                    return false;
            } else return false;
            return true;
        } catch (org.springframework.dao.TransientDataAccessException
                | org.springframework.dao.DataAccessResourceFailureException
                | org.springframework.dao.RecoverableDataAccessException transientFailure) { throw transientFailure; }
        catch (RuntimeException unavailable) { return false; }
    }
    public void requireContextAccessible(AiUserJobs.Job job) {
        String raw=jdbc.queryForObject("SELECT context_sources FROM ai_user_jobs WHERE id=?",String.class,job.id());
        for (var reference:contextReferences(raw))
            if (!accessible(job.userId(),reference.type(),reference.id())) unavailable();
    }
    private java.util.List<SourceReference> memoryReferences(Map<String,Object> memory) {
        var references=new java.util.ArrayList<>(contextReferences(memory.get("context_sources")));
        references.add(new SourceReference((String)memory.get("source_type"),
                memory.get("source_id")==null?null:((Number)memory.get("source_id")).longValue()));
        return references;
    }
    private java.util.List<SourceReference> contextReferences(Object raw) {
        if (raw==null) return java.util.List.of();
        String json=raw instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):raw.toString();
        return java.util.Arrays.asList(mapper.readValue(json,SourceReference[].class));
    }
    private Map<String,Object> memberTarget(long viewer,long membershipId) {
        var target=jdbc.queryForMap("SELECT hm.house_id,hm.user_id FROM house_members hm JOIN house h ON h.id=hm.house_id "
                + "JOIN users u ON u.id=hm.user_id WHERE hm.id=? AND hm.status='ACTIVE' AND h.deleted_at IS NULL "
                + "AND u.deleted_at IS NULL AND u.is_bot=FALSE AND EXISTS (SELECT 1 FROM house_members own "
                + "WHERE own.house_id=hm.house_id AND own.user_id=? AND own.status='ACTIVE')",membershipId,viewer);
        requireUnblocked(viewer,((Number)target.get("user_id")).longValue());
        return target;
    }
    private boolean unblocked(long viewer,long author) {
        try { requireUnblocked(viewer,author); return true; }
        catch (ResponseStatusException unavailable) { return false; }
    }
    private void requireUnblocked(long viewer,long author) {
        Integer blocked=jdbc.queryForObject("SELECT COUNT(*) FROM user_blocks WHERE "
                + "(blocker_user_id=? AND blocked_user_id=?) OR (blocker_user_id=? AND blocked_user_id=?)",Integer.class,viewer,author,author,viewer);
        Integer active=jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND deleted_at IS NULL",Integer.class,author);
        if (blocked!=0 || active==0) unavailable();
    }
    private static void unavailable() { throw new ResponseStatusException(HttpStatus.CONFLICT,"AI source no longer accessible"); }
}
