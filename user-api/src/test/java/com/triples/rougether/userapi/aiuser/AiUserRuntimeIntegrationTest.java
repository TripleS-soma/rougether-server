package com.triples.rougether.userapi.aiuser;

import com.triples.rougether.userapi.aiuser.service.*;
import com.triples.rougether.userapi.aiuser.config.AiUserProperties;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.CharacterAccessoryRenderProfile;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.character.repository.CharacterAccessoryRenderProfileRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.domain.shared.CurrencyType;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.retention.repository.AdminRetentionMetricRepository;
import com.triples.rougether.domain.activity.repository.UserDailyActivityRepository;
import com.triples.rougether.userapi.auth.service.AuthService;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.feed.dto.*;
import com.triples.rougether.userapi.feed.service.FeedCommandService;
import com.triples.rougether.domain.feed.entity.FeedBoardType;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties={"ai-users.enabled=true","ai-users.control-token=ai-integration-secret-at-least-32-chars",
        "spring.datasource.url=jdbc:tc:mysql:8.4:///rougether_ai_users?TC_DAEMON=true",
        "ai-users.scheduler-delay=3600000"})
@AutoConfigureMockMvc
class AiUserRuntimeIntegrationTest {
    @Autowired AiUserSeedService seed;
    @Autowired AiUserJobs jobs;
    @Autowired AiUserProperties aiUserProperties;
    @Autowired AiUserControlService control;
    @Autowired AiUserMemoryProjection memoryProjection;
    @Autowired UserRepository users;
    @Autowired AdminRetentionMetricRepository retention;
    @Autowired UserDailyActivityRepository activity;
    @Autowired FeedCommandService feed;
    @Autowired com.triples.rougether.userapi.chat.service.ChatCommandService chat;
    @Autowired com.triples.rougether.userapi.house.service.HouseJoinService joins;
    @Autowired AuthService auth;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired com.triples.rougether.userapi.member.service.MemberWithdrawalService withdrawal;
    @Autowired com.triples.rougether.userapi.room.service.RoomQueryService rooms;
    @Autowired CharacterRepository characterCatalog;
    @Autowired UserCharacterRepository ownedCharacters;
    @Autowired CharacterAccessoryRenderProfileRepository renderProfiles;
    @Autowired ThemeRepository themes;
    @Autowired ItemRepository items;
    @Autowired UserItemRepository ownedItems;
    List<Long> ais;
    final List<Long> createdHumans=new ArrayList<>();

    @BeforeEach void prepare() {
        ais=seed.seed();
        jobs.setEnabled(false);
        jdbc.update("DELETE FROM ai_user_memories");
        jdbc.update("DELETE FROM ai_user_jobs");
        jdbc.update("UPDATE ai_user_runtime SET current_job_id=NULL,lease_owner=NULL,lease_until=NULL");
        jobs.setEnabled(true);
    }
    @AfterEach void disable() {
        jobs.setEnabled(false);
        jdbc.update("UPDATE ai_user_runtime SET current_job_id=NULL,lease_owner=NULL,lease_until=NULL");
        jdbc.update("DELETE FROM ai_user_memories"); jdbc.update("DELETE FROM ai_user_jobs"); jdbc.update("DELETE FROM ai_user_profiles");
        var testUsers=new ArrayList<>(ais);
        testUsers.addAll(createdHumans);
        for (Long id:testUsers) jdbc.update("DELETE FROM room_cobwebs WHERE room_user_id=? OR cleaned_by_user_id=?",id,id);
        for(Long id:ais) withdrawal.withdraw(id);
        for(Long id:createdHumans) withdrawal.withdraw(id);
    }

    @Test void seedIsDistinctIdempotentAndHumanStatisticsExcludeAi() {
        assertThat(ais).hasSize(10).doesNotHaveDuplicates();
        assertThat(seed.seed()).containsExactlyElementsOf(ais);
        assertThat(users.findAllById(ais)).allSatisfy(user -> {
            assertThat(user.isAi()).isTrue(); assertThat(user.isBot()).isFalse(); assertThat(user.isHuman()).isFalse();
        });
        long before=users.countByDeletedAtIsNullAndBotFalse();
        User human=tx.execute(s -> users.saveAndFlush(User.signUp()));
        createdHumans.add(human.getId());
        assertThat(users.countByDeletedAtIsNullAndBotFalse()).isEqualTo(before+1);
        assertThat(retention.findActiveUsers()).extracting(AdminRetentionMetricRepository.ActiveUserMetricRow::getUserId)
                .contains(human.getId()).doesNotContainAnyElementsOf(ais);
        LocalDate date=LocalDate.now(ZoneId.of("Asia/Seoul"));
        tx.executeWithoutResult(s -> activity.insertBatchIfActiveUsers(List.of(human.getId(),ais.getFirst()),date));
        assertThat(activity.findRecordedActiveUserIds(List.of(human.getId(),ais.getFirst()),date)).containsExactly(human.getId());
    }

    @Test void socialVisitsAreSeparateIdempotentAndWaitsDoNotConsumeActionBudget() {
        var now=Instant.parse("2026-10-17T11:20:00Z");
        var scheduled=new AiUserJobs(jdbc,Clock.fixed(now,ZoneOffset.UTC),aiUserProperties);
        jdbc.update("UPDATE ai_user_profiles SET next_run_at=?,next_social_at=NULL",java.sql.Timestamp.from(now.plusSeconds(3600)));
        tx.executeWithoutResult(s -> scheduled.schedule());
        int initial=jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_jobs WHERE source_type='SOCIAL_BROWSE'",Integer.class);
        assertThat(initial).isPositive();
        tx.executeWithoutResult(s -> scheduled.schedule());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_jobs WHERE source_type='SOCIAL_BROWSE'",Integer.class)).isEqualTo(initial);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_jobs WHERE source_type='SCHEDULE'",Integer.class)).isZero();
        jdbc.update("DELETE FROM ai_user_jobs");
        for (int i=0;i<17;i++) {
            scheduled.enqueue(ais.getFirst(),"wait-budget-"+i,20,"SOCIAL_BROWSE",null);
            var job=tx.execute(s -> scheduled.claim("wait-budget"));
            assertThat(job).isNotNull();
            jdbc.update("UPDATE ai_user_jobs SET action_name='WAIT',status='DONE',wait_reason='NO_NEW_TOPIC',finished_at=? WHERE id=?",
                    java.sql.Timestamp.from(now),job.id());
            jdbc.update("UPDATE ai_user_runtime SET current_job_id=NULL,lease_owner=NULL,lease_until=NULL");
        }
        scheduled.enqueue(ais.getFirst(),"after-waits",20,"SOCIAL_BROWSE",null);
        var realAction=tx.execute(s -> scheduled.claim("real-action"));
        assertThat(realAction).isNotNull();
    }

    @Test void socialPostIsCommittedOnceAndDailyLimitProducesRecordedWait() {
        var first=beginSocial(ais.getFirst());
        var payload=mapper.readTree("{\"boardType\":\"FREE\",\"content\":\"오늘은 앱에서 기록할 작은 계획부터 정했어요\",\"imageIds\":[],\"topic\":\"첫 기록 계획\"}");
        String receipt=control.execute(first.id(),first.owner(),first.fencingToken(),AiUserActionExecutor.Action.FEED_POST,payload,"첫 계획을 공유함");
        assertThat(control.execute(first.id(),first.owner(),first.fencingToken(),AiUserActionExecutor.Action.FEED_POST,payload,"재시도"))
                .isEqualTo(receipt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_social_actions WHERE user_id=?",Integer.class,first.userId())).isEqualTo(1);
        var second=beginSocial(first.userId());
        control.execute(second.id(),second.owner(),second.fencingToken(),AiUserActionExecutor.Action.FEED_POST,
                mapper.readTree("{\"boardType\":\"FREE\",\"content\":\"두 번째 글은 제한돼야 함\",\"imageIds\":[]}"),"게시했다고 남기면 안 됨");
        assertThat(jdbc.queryForMap("SELECT action_name,wait_reason FROM ai_user_jobs WHERE id=?",second.id()))
                .containsEntry("action_name","WAIT").containsEntry("wait_reason","DAILY_LIMIT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_memories WHERE job_id=?",Integer.class,second.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_social_actions WHERE user_id=?",Integer.class,first.userId())).isEqualTo(1);
        assertThat(memoryProjection.pending(10)).anySatisfy(export -> {
            assertThat(export.get("jobId")).isEqualTo(first.id());
            assertThat(export.get("body").toString()).contains("SOCIAL_BROWSE");
        });
    }

    @Test void proactiveCommentsAreUniquePerAccountAndGloballyBoundedButHumanReplyIsAllowed() {
        User human=quietHuman();
        long post=quietPost(human,"독서 기록을 주제별로 정리하는 방법이 궁금해요");
        var first=beginSocial(ais.getFirst());
        var payload=mapper.readTree("{\"postId\":"+post+",\"content\":\"기록을 주제로 나누면 다시 찾기 좋겠네요\"}");
        control.execute(first.id(),first.owner(),first.fencingToken(),AiUserActionExecutor.Action.FEED_COMMENT,payload,"");
        jdbc.update("UPDATE ai_user_social_actions SET created_at=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1900)));
        var duplicate=beginSocial(first.userId());
        control.execute(duplicate.id(),duplicate.owner(),duplicate.fencingToken(),AiUserActionExecutor.Action.FEED_COMMENT,payload,"");
        assertThat(jdbc.queryForObject("SELECT wait_reason FROM ai_user_jobs WHERE id=?",String.class,duplicate.id())).isEqualTo("RECENT_PARTICIPATION");
        var second=beginSocial(ais.get(1));
        control.execute(second.id(),second.owner(),second.fencingToken(),AiUserActionExecutor.Action.FEED_COMMENT,
                mapper.readTree("{\"postId\":"+post+",\"content\":\"저는 분류할 주제부터 작게 정해보려고요\"}"),"");
        jobs.enqueue(ais.get(2),"crowd-check",10,"SCHEDULE",null);
        var crowded=jobs.claim("crowd-check");
        control.execute(crowded.id(),crowded.owner(),crowded.fencingToken(),AiUserActionExecutor.Action.FEED_COMMENT,
                mapper.readTree("{\"postId\":"+post+",\"content\":\"세 번째 선제 댓글\"}"),"");
        assertThat(jdbc.queryForObject("SELECT wait_reason FROM ai_user_jobs WHERE id=?",String.class,crowded.id())).isEqualTo("RECENT_PARTICIPATION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_social_actions WHERE post_id=? AND proactive=TRUE",Integer.class,post)).isEqualTo(2);
        feed.comment(human.getId(),post,new FeedCommentRequest(UUID.randomUUID(),"분류 기준을 두 가지로 시작해볼까요?"));
        var reply=jobs.claim("human-first");
        assertThat(reply.sourceType()).isEqualTo("FEED_COMMENT");
        control.snapshot(reply.id(),reply.owner(),reply.fencingToken());
        control.execute(reply.id(),reply.owner(),reply.fencingToken(),AiUserActionExecutor.Action.FEED_COMMENT,
                mapper.readTree("{\"postId\":"+post+",\"content\":\"네, 두 가지면 유지하기에도 부담이 덜하겠어요\"}"),"");
        assertThat(jdbc.queryForObject("SELECT proactive FROM ai_user_social_actions WHERE job_id=?",Boolean.class,reply.id())).isFalse();
    }

    @Test void socialContextIncludesOldPostAndCommentsAndRevocationPreventsWriting() {
        User human=quietHuman();
        long post=quietPost(human,"방 꾸미기 전에 어떤 가구부터 고르면 좋을까요?");
        jdbc.update("UPDATE feed_posts SET created_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(172800)),post);
        jobs.setEnabled(false);
        var comment=feed.comment(human.getId(),post,new FeedCommentRequest(UUID.randomUUID(),"작은 책상부터 보고 있어요"));
        jobs.setEnabled(true);
        var job=beginSocial(ais.getFirst());
        var snapshot=mapper.writeValueAsString(control.snapshot(job.id(),job.owner(),job.fencingToken()));
        assertThat(snapshot).contains("recentComments","작은 책상부터 보고 있어요");
        assertThat(jdbc.queryForObject("SELECT context_sources FROM ai_user_jobs WHERE id=?",String.class,job.id()))
                .contains("FEED_COMMENT",comment.commentId().toString());
        feed.delete(human.getId(),post);
        assertThatThrownBy(() -> control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.FEED_COMMENT,
                mapper.readTree("{\"postId\":"+post+",\"content\":\"삭제된 글에는 쓰면 안 됨\"}"),""))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_social_actions WHERE job_id=?",Integer.class,job.id())).isZero();
    }

    @Test void copiedPostIsRejectedAfterDailyWindowAndCommentDailyLimitIsIndependent() {
        long ai=ais.getFirst();
        var first=beginSocial(ai);
        var postPayload=mapper.readTree("{\"boardType\":\"FREE\",\"content\":\"기록을 작게 나눠볼 계획이에요\",\"imageIds\":[]}");
        control.execute(first.id(),first.owner(),first.fencingToken(),AiUserActionExecutor.Action.FEED_POST,postPayload,"");
        jdbc.update("UPDATE ai_user_social_actions SET created_at=?",java.sql.Timestamp.from(Instant.now().minusSeconds(172800)));
        var copy=beginSocial(ai);
        control.execute(copy.id(),copy.owner(),copy.fencingToken(),AiUserActionExecutor.Action.FEED_POST,postPayload,"");
        assertThat(jdbc.queryForObject("SELECT wait_reason FROM ai_user_jobs WHERE id=?",String.class,copy.id())).isEqualTo("RECENT_PARTICIPATION");
        User human=quietHuman();
        var posts=new ArrayList<Long>();
        for(int i=0;i<4;i++) posts.add(quietPost(human,"기록 방법에 대한 질문 "+i));
        Collections.reverse(posts);
        for(int i=0;i<4;i++) {
            var job=beginSocial(ai);
            control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.FEED_COMMENT,
                    mapper.readTree("{\"postId\":"+posts.get(i)+",\"content\":\"주제 "+i+"에 대한 구체적인 반응\"}"),"");
            if(i==3) assertThat(jdbc.queryForObject("SELECT wait_reason FROM ai_user_jobs WHERE id=?",String.class,job.id())).isEqualTo("DAILY_LIMIT");
            jdbc.update("UPDATE ai_user_social_actions SET created_at=? WHERE action_name='FEED_COMMENT'",java.sql.Timestamp.from(Instant.now().minusSeconds(1900)));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_social_actions WHERE user_id=? AND action_name='FEED_COMMENT'",Integer.class,ai)).isEqualTo(3);
    }

    @Test void socialCommentsAreLatestAndExcludeAuthorsWhoBlockedTheAi() {
        User human=quietHuman(),blocked=quietHuman();
        long post=quietPost(human,"최근 댓글과 차단 관계를 검증하는 글");
        jobs.setEnabled(false);
        var comments=new ArrayList<FeedCommentResponse>();
        for(int i=0;i<4;i++) comments.add(feed.comment(human.getId(),post,new FeedCommentRequest(UUID.randomUUID(),"공개 댓글 "+i)));
        feed.comment(blocked.getId(),post,new FeedCommentRequest(UUID.randomUUID(),"차단한 작성자의 원문은 전달하면 안 됨"));
        jobs.setEnabled(true);
        long ai=ais.getFirst();
        jdbc.update("INSERT INTO user_blocks(blocker_user_id,blocked_user_id,created_at) VALUES (?,?,CURRENT_TIMESTAMP)",blocked.getId(),ai);
        try {
            var job=beginSocial(ai);
            var snapshot=mapper.valueToTree(control.snapshot(job.id(),job.owner(),job.fencingToken()));
            var seen=snapshot.path("social").path("candidates").get(0).path("recentComments");
            assertThat(seen.size()).isEqualTo(3);
            assertThat(seen.get(0).path("commentId").asLong()).isEqualTo(comments.getLast().commentId());
            assertThat(snapshot.toString()).doesNotContain("차단한 작성자의 원문");
        } finally { jdbc.update("DELETE FROM user_blocks WHERE blocker_user_id=? AND blocked_user_id=?",blocked.getId(),ai); }
    }

    private AiUserJobs.Job beginSocial(long user) {
        jobs.enqueue(user,"social-test:"+UUID.randomUUID(),20,"SOCIAL_BROWSE",null);
        var job=jobs.claim("social-test");
        assertThat(job.userId()).isEqualTo(user);
        control.snapshot(job.id(),job.owner(),job.fencingToken());
        return job;
    }
    private User quietHuman() {
        User human=tx.execute(s -> users.saveAndFlush(User.signUp()));
        createdHumans.add(human.getId());
        return human;
    }
    private long quietPost(User human,String content) {
        jobs.setEnabled(false);
        Long post=feed.create(human.getId(),new FeedCreateRequest(UUID.randomUUID(),content,List.of(),FeedBoardType.FREE));
        jobs.setEnabled(true);
        return post;
    }
    @Test void onlyOneConcurrentWorkerAndHumanJobWins() throws Exception {
        jobs.enqueue(ais.getFirst(),"scheduled",10,"SCHEDULE",null);
        jobs.enqueue(ais.get(1),"human",100,"SCHEDULE",null);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var latch=new CountDownLatch(1);
            var a=pool.submit(() -> { latch.await(); return jobs.claim("worker-a"); });
            var b=pool.submit(() -> { latch.await(); return jobs.claim("worker-b"); });
            latch.countDown();
            List<AiUserJobs.Job> claimed=new ArrayList<>();
            var first=a.get(10,TimeUnit.SECONDS); var second=b.get(10,TimeUnit.SECONDS);
            if(first!=null)claimed.add(first); if(second!=null)claimed.add(second);
            assertThat(claimed).hasSize(1);
            assertThat(claimed.getFirst().userId()).isEqualTo(ais.get(1));
        }
    }
    @Test void expiredWorkerCannotWriteAndNewOwnerIsFenced() {
        var preciseClock=Clock.fixed(Instant.now().truncatedTo(ChronoUnit.SECONDS).plusNanos(123456789),ZoneOffset.UTC);
        var preciseJobs=new AiUserJobs(jdbc,preciseClock,aiUserProperties);
        tx.executeWithoutResult(s -> preciseJobs.enqueue(ais.getFirst(),"retry",10,"SCHEDULE",null));
        var old=tx.execute(s -> preciseJobs.claim("old"));
        jdbc.update("UPDATE ai_user_runtime SET lease_until=?",java.sql.Timestamp.from(preciseClock.instant().minusSeconds(1)));
        var next=tx.execute(s -> preciseJobs.claim("new"));
        assertThat(next.id()).isEqualTo(old.id());
        assertThat(next.fencingToken()).isGreaterThan(old.fencingToken());
        assertThatThrownBy(() -> tx.execute(s -> preciseJobs.requireClaim(old.id(),old.owner(),old.fencingToken()))).isInstanceOf(RuntimeException.class);
        var current=tx.execute(s -> preciseJobs.requireClaim(next.id(),next.owner(),next.fencingToken()));
        assertThat(current).isEqualTo(next);
    }
    @Test void postMemoryAndReceiptCommitOnceAndSurviveNewRequest() {
        jobs.enqueue(ais.getFirst(),"post-once",10,"SCHEDULE",null);
        var job=jobs.claim("writer");
        var payload=mapper.readTree("{\"boardType\":\"FREE\",\"content\":\"작게 시작해봐요\",\"imageIds\":[]}");
        String first=control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.FEED_POST,payload,"짧은 글을 남겼음");
        String replay=control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.FEED_POST,payload,"다시 생성하면 안 됨");
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM feed_posts WHERE client_post_id=?",Integer.class,
                UUID.nameUUIDFromBytes(("ai-action:"+job.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_memories WHERE job_id=?",Integer.class,job.id())).isEqualTo(1);
        assertThat(jobs.completedResult(job.id(),"unrelated",job.fencingToken())).isNull();
        jobs.enqueue(job.userId(),"memory-next",10,"SCHEDULE",null);
        var next=jobs.claim("after-restart");
        assertThat(mapper.writeValueAsString(control.snapshot(next.id(),next.owner(),next.fencingToken())))
                .contains("짧은 글을 남겼음");
    }
    @Test void graphProjectionSurvivesReflectionExpiryAndUsesOriginalUserText() {
        User human=tx.execute(s -> users.saveAndFlush(User.signUp()));
        createdHumans.add(human.getId());
        Long post=feed.create(ais.getFirst(),new FeedCreateRequest(UUID.randomUUID(),"독서 이야기",List.of(),FeedBoardType.FREE,null));
        feed.comment(human.getId(),post,new FeedCommentRequest(UUID.randomUUID(),"내 독서 기록 이름은 달빛서랍이야"));
        var job=jobs.claim("memory-export");
        control.snapshot(job.id(),job.owner(),job.fencingToken());
        control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.WAIT,mapper.createObjectNode(),"부정확한 회고");
        jdbc.update("DELETE FROM ai_user_memories");
        var pending=memoryProjection.pending(10);
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().get("aiUserId")).isEqualTo(job.userId());
        assertThat(pending.getFirst().get("body").toString()).contains("달빛서랍",human.getId().toString()).doesNotContain("부정확한 회고");
        memoryProjection.acknowledge(job.id(),true,false);
        memoryProjection.acknowledge(job.id(),true,false);
        assertThat(memoryProjection.pending(10)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_user_jobs WHERE id=?",String.class,job.id())).isEqualTo("DONE");
    }

    @Test void graphFactsCannotCrossAccountsAndEditedSourcesAreRejectedBeforeExecution() {
        User human=tx.execute(s -> users.saveAndFlush(User.signUp()));
        createdHumans.add(human.getId());
        Long post=feed.create(ais.getFirst(),new FeedCreateRequest(UUID.randomUUID(),"독서 이야기",List.of(),FeedBoardType.FREE,null));
        feed.comment(human.getId(),post,new FeedCommentRequest(UUID.randomUUID(),"기록 이름은 달빛서랍"));
        var first=jobs.claim("memory-source");
        control.snapshot(first.id(),first.owner(),first.fencingToken());
        control.execute(first.id(),first.owner(),first.fencingToken(),AiUserActionExecutor.Action.WAIT,mapper.createObjectNode(),null);
        var export=mapper.valueToTree(memoryProjection.pending(10).getFirst());
        var fact=new AiUserMemoryProjection.Fact("독서 기록은 달빛서랍",mapper.convertValue(export.get("sources"),
                mapper.getTypeFactory().constructCollectionType(List.class,AiUserMemoryProjection.Source.class)),List.of("00000000-0000-0000-0000-000000000001"));
        jobs.enqueue(ais.get(1),"other-memory",10,"SCHEDULE",null);
        var other=jobs.claim("other-memory");
        control.snapshot(other.id(),other.owner(),other.fencingToken());
        assertThat(memoryProjection.authorize(other.id(),other.owner(),other.fencingToken(),List.of(fact))).isEmpty();
        jobs.fail(other.id(),other.owner(),other.fencingToken());
        jobs.enqueue(first.userId(),"recall-own",10,"SCHEDULE",null);
        var next=jobs.claim("recall-own");
        control.snapshot(next.id(),next.owner(),next.fencingToken());
        assertThat(memoryProjection.authorize(next.id(),next.owner(),next.fencingToken(),List.of(fact))).containsExactly(fact);
        jdbc.update("UPDATE feed_comments SET content='기록 이름을 변경했어' WHERE id=?",first.sourceId());
        assertThatThrownBy(() -> control.execute(next.id(),next.owner(),next.fencingToken(),AiUserActionExecutor.Action.WAIT,mapper.createObjectNode(),null))
                .isInstanceOf(RuntimeException.class);
        assertThat(memoryProjection.authorize(next.id(),next.owner(),next.fencingToken(),List.of(fact))).isEmpty();
    }

    @Test void graphSyncFailuresBackOffWithoutReexecutingBusinessAction() {
        jobs.enqueue(ais.getFirst(),"sync-retry",10,"SCHEDULE",null);
        var job=jobs.claim("sync-retry");
        control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.WAIT,mapper.createObjectNode(),null);
        memoryProjection.acknowledge(job.id(),false,false);
        assertThat(memoryProjection.pending(10)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_user_jobs WHERE id=?",String.class,job.id())).isEqualTo("DONE");
        for(int i=0;i<2;i++) memoryProjection.acknowledge(job.id(),false,false);
        assertThat(jdbc.queryForObject("SELECT memory_status FROM ai_user_jobs WHERE id=?",String.class,job.id())).isEqualTo("FAILED");
    }

    @Test void humanCommentEnqueuesPriorityJobInSameTransaction() {
        User human=tx.execute(s -> users.saveAndFlush(User.signUp()));
        createdHumans.add(human.getId());
        Long post=feed.create(ais.getFirst(),new FeedCreateRequest(UUID.randomUUID(),"댓글로 이야기해요",List.of(),FeedBoardType.FREE,null));
        feed.comment(human.getId(),post,new FeedCommentRequest(UUID.randomUUID(),"오늘 어떻게 시작할까요?"));
        var job=jobs.claim("reader");
        assertThat(job.userId()).isEqualTo(ais.getFirst());
        assertThat(job.sourceType()).isEqualTo("FEED_COMMENT");
        assertThat(jdbc.queryForObject("SELECT priority FROM ai_user_jobs WHERE id=?",Integer.class,job.id())).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_jobs",Integer.class)).isEqualTo(1);
    }
    @Test void failedActionRollsBackAndPauseStopsClaimedAction() {
        jobs.enqueue(ais.getFirst(),"rollback",10,"SCHEDULE",null);
        var job=jobs.claim("writer");
        var payload=mapper.readTree("{\"boardType\":\"FREE\",\"content\":\"롤백되어야 함\",\"imageIds\":[]}");
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM feed_posts",Long.class);
        assertThatThrownBy(() -> control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.FEED_POST,payload,"x".repeat(1001))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM feed_posts",Long.class)).isEqualTo(count);
        jobs.setEnabled(false);
        assertThatThrownBy(() -> control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.WAIT,mapper.createObjectNode(),null)).isInstanceOf(RuntimeException.class);
        jobs.setEnabled(true);
        assertThatThrownBy(() -> control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.WAIT,mapper.createObjectNode(),null)).isInstanceOf(RuntimeException.class);
        assertThat(jobs.claim("after-resume").fencingToken()).isGreaterThan(job.fencingToken());
    }

    @Test void humanChatWinsOverScheduleAndAiReplyDoesNotCreateLoop() {
        User human=human();
        Long house=jdbc.queryForObject("SELECT house_id FROM house_members WHERE user_id=? AND status='ACTIVE' ORDER BY id LIMIT 1",Long.class,ais.getFirst());
        joins.join(human.getId(),house);
        Long room=chat.openHouse(human.getId(),house).roomId();
        jobs.enqueue(ais.get(1),"chat-background",10,"SCHEDULE",null);
        var message=chat.send(human.getId(),room,new com.triples.rougether.userapi.chat.dto.ChatSendRequest(UUID.randomUUID(),"오늘은 어떤 계획이야?"));
        var job=jobs.claim("chat-worker");
        assertThat(job.sourceType()).isEqualTo("CHAT");
        assertThat(job.sourceId()).isEqualTo(message.messageId());
        assertThat(mapper.writeValueAsString(control.snapshot(job.id(),job.owner(),job.fencingToken())))
                .contains("오늘은 어떤 계획이야?","recentMessages");
        control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.CHAT_SEND,
                mapper.readTree("{\"roomId\":"+room+",\"content\":\"작은 계획부터 시작해봐요\"}"),"집에서 대화함");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_jobs WHERE source_type='CHAT'",Integer.class)).isEqualTo(1);
    }

    @Test void humanWriteRollbackAlsoRemovesQueuedEvent() {
        User human=human();
        Long post=feed.create(ais.getFirst(),new FeedCreateRequest(UUID.randomUUID(),"원자적 댓글",List.of(),FeedBoardType.FREE,null));
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM feed_comments WHERE post_id=?",Long.class,post);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            feed.comment(human.getId(),post,new FeedCommentRequest(UUID.randomUUID(),"커밋되지 않는 댓글"));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM feed_comments WHERE post_id=?",Long.class,post)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_jobs",Integer.class)).isZero();
    }

    @Test void blockedAndDeletedSourcesAreNeitherObservedNorRecalled() {
        User human=human();
        Long ai=ais.getFirst();
        Long post=feed.create(ai,new FeedCreateRequest(UUID.randomUUID(),"기억 접근 범위",List.of(),FeedBoardType.FREE,null));
        Long comment=feed.comment(human.getId(),post,new FeedCommentRequest(UUID.randomUUID(),"기록 이름은 달빛서랍; "+"원문".repeat(200))).commentId();
        var job=jobs.claim("memory-worker");
        control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.WAIT,mapper.createObjectNode(),"삭제할 사람 대화를 기억함");
        jobs.enqueue(ai,"recalled-after-delete",10,"SCHEDULE",null);
        var next=jobs.claim("memory-reader");
        String memoryJson=mapper.writeValueAsString(control.snapshot(next.id(),next.owner(),next.fencingToken()).get("memories"));
        assertThat(memoryJson).contains("달빛서랍","authorUserId","\"truncated\":true");
        assertThat(mapper.writeValueAsString(control.snapshot(next.id(),next.owner(),next.fencingToken())))
                .contains("삭제할 사람 대화를 기억함");
        jdbc.update("UPDATE feed_comments SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",comment);
        assertThat(mapper.writeValueAsString(control.snapshot(next.id(),next.owner(),next.fencingToken()).get("memories")))
                .doesNotContain("달빛서랍");
        assertThat(mapper.writeValueAsString(control.snapshot(next.id(),next.owner(),next.fencingToken())))
                .doesNotContain("삭제할 사람 대화를 기억함");
        jobs.fail(next.id(),next.owner(),next.fencingToken());
        feed.comment(human.getId(),post,new FeedCommentRequest(UUID.randomUUID(),"차단 이후 보이면 안 됨"));
        var blocked=jobs.claim("blocked-reader");
        jdbc.update("INSERT INTO user_blocks(blocker_user_id,blocked_user_id,created_at) VALUES (?,?,CURRENT_TIMESTAMP)",human.getId(),ai);
        try {
            assertThatThrownBy(() -> control.snapshot(blocked.id(),blocked.owner(),blocked.fencingToken())).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> control.execute(blocked.id(),blocked.owner(),blocked.fencingToken(),
                    AiUserActionExecutor.Action.FEED_COMMENT,mapper.readTree("{\"postId\":"+post+",\"content\":\"차단된 답변\"}"),null)).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.update("DELETE FROM user_blocks WHERE blocker_user_id=? AND blocked_user_id=?",human.getId(),ai);
        }
    }

    private User human() {
        User human=tx.execute(s -> users.saveAndFlush(User.signUp()));
        createdHumans.add(human.getId());
        return human;
    }

    @Test void scheduleMemoryAlsoLosesAccessWhenObservedHumanPostIsDeleted() {
        User human=human();
        jobs.setEnabled(false);
        Long post=feed.create(human.getId(),new FeedCreateRequest(UUID.randomUUID(),"주기 관찰에서 읽은 글",List.of(),FeedBoardType.FREE,null));
        jobs.setEnabled(true);
        jobs.enqueue(ais.getFirst(),"observe-periodic",10,"SCHEDULE",null);
        var job=jobs.claim("observer");
        control.snapshot(job.id(),job.owner(),job.fencingToken());
        control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.WAIT,mapper.createObjectNode(),"주기 관찰에서 읽은 사람 이야기");
        jobs.enqueue(ais.getFirst(),"recall-periodic",10,"SCHEDULE",null);
        var next=jobs.claim("reader");
        assertThat(mapper.writeValueAsString(control.snapshot(next.id(),next.owner(),next.fencingToken())))
                .contains("주기 관찰에서 읽은 사람 이야기");
        jdbc.update("UPDATE feed_posts SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",post);
        assertThatThrownBy(() -> control.execute(next.id(),next.owner(),next.fencingToken(),
                AiUserActionExecutor.Action.WAIT,mapper.createObjectNode(),"이전 관찰로 계속 실행하면 안 됨"))
                .isInstanceOf(RuntimeException.class);
        assertThat(mapper.writeValueAsString(control.snapshot(next.id(),next.owner(),next.fencingToken())))
                .doesNotContain("주기 관찰에서 읽은 사람 이야기");
    }

    @Test void routineRewardAndJobMemoryAreAtomicAndUseHumanDomainRules() {
        Long ai=ais.getFirst();
        jobs.enqueue(ai,"routine-create",10,"SCHEDULE",null);
        var create=jobs.claim("routine-worker");
        String receipt=control.execute(create.id(),create.owner(),create.fencingToken(),AiUserActionExecutor.Action.ROUTINE_CREATE,
                mapper.readTree("{\"title\":\"작은 걷기 계획\",\"authType\":\"CHECK\",\"repeatType\":\"DAILY\"}"),"걷기 계획을 만듦");
        long routine=mapper.readTree(receipt).path("id").asLong();
        assertThat(routine).isPositive();
        jobs.enqueue(ai,"routine-complete",10,"SCHEDULE",null);
        var job=jobs.claim("routine-worker");
        var payload=mapper.readTree("{\"routineId\":"+routine+",\"request\":{}}");
        long balance=jdbc.queryForObject("SELECT balance FROM user_wallets WHERE user_id=? AND currency_type='COIN'",Long.class,ai);
        assertThatThrownBy(() -> control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.ROUTINE_COMPLETE,
                payload,"x".repeat(1001))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM routine_logs WHERE routine_id=?",Integer.class,routine)).isZero();
        assertThat(jdbc.queryForObject("SELECT balance FROM user_wallets WHERE user_id=? AND currency_type='COIN'",Long.class,ai)).isEqualTo(balance);
        String result=control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.ROUTINE_COMPLETE,payload,"계획을 체크함");
        assertThat(control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.ROUTINE_COMPLETE,payload,null)).isEqualTo(result);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM routine_logs WHERE routine_id=?",Integer.class,routine)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT balance FROM user_wallets WHERE user_id=? AND currency_type='COIN'",Long.class,ai)).isEqualTo(balance+10);
        assertThat(retention.findCompletionEvents(Instant.now().minusSeconds(60),Instant.now().plusSeconds(60),
                com.triples.rougether.domain.routine.entity.RoutineLogStatus.COMPLETED))
                .extracting(AdminRetentionMetricRepository.CompletionEventMetricRow::getUserId).doesNotContain(ai);
    }

    @Test void periodicLimitLeavesRoomForHumanJobs() {
        Long ai=ais.getFirst();
        var now=java.sql.Timestamp.from(Instant.now());
        for(int i=0;i<16;i++) jdbc.update("INSERT INTO ai_user_jobs(user_id,job_key,priority,source_type,status,action_name,ready_at,created_at,finished_at) "
                + "VALUES (?,? ,10,'SCHEDULE','DONE','ROUTINE_CREATE',?,?,?)",ai,"quota:"+i,now,now,now);
        jobs.enqueue(ai,"periodic-over-limit",10,"SCHEDULE",null);
        assertThat(jobs.claim("limited")).isNull();
        jobs.enqueue(ai,"human-reserved-quota",100,"SCHEDULE",null);
        var job=jobs.claim("human-first");
        assertThat(job).isNotNull();
        assertThat(jdbc.queryForObject("SELECT job_key FROM ai_user_jobs WHERE id=?",String.class,job.id())).isEqualTo("human-reserved-quota");
    }

    @Test void normalLoginAndMissingControlSecretAreRejected() throws Exception {
        assertThatThrownBy(() -> auth.devLogin(ais.getFirst())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> tokens.issueAccessToken(ais.getFirst(),com.triples.rougether.userapi.global.security.MemberRole.NORMAL)).isInstanceOf(RuntimeException.class);
        mvc.perform(post("/internal/ai-users/claim").contentType("application/json").content("{\"owner\":\"worker\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/ai-users/seed").header("Authorization","Bearer human-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/ai-users/seed")
                        .header("Authorization","Bearer ai-integration-secret-at-least-32-chars"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(10));
    }

    @Test void profileChangeUsesClaimedAccountAndRollsBackWithMemoryFailure() {
        Long ai=ais.getFirst();
        String original=users.findById(ai).orElseThrow().getNickname();
        Long house=ownHouse(ai);
        var job=scheduled(ai,"profile-edit");
        var payload=mapper.readTree("{\"nickname\":\"산책노트수정\",\"bio\":\"작은 계획을 이어가요\"}");
        assertThatThrownBy(() -> control.execute(job.id(),job.owner(),job.fencingToken(),
                AiUserActionExecutor.Action.PROFILE_UPDATE,payload,"x".repeat(1001))).isInstanceOf(RuntimeException.class);
        assertThat(users.findById(ai).orElseThrow().getNickname()).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT name FROM house WHERE id=?",String.class,house)).isEqualTo("나의 집");
        String first=control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.PROFILE_UPDATE,payload,"소개를 수정함");
        assertThat(control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.PROFILE_UPDATE,payload,null)).isEqualTo(first);
        assertThat(users.findById(ai).orElseThrow().getNickname()).isEqualTo("산책노트수정");
        assertThat(users.findById(ais.get(1)).orElseThrow().getNickname()).isEqualTo("책틈");
        assertThat(jdbc.queryForObject("SELECT name FROM house WHERE id=?",String.class,house)).isEqualTo("산책노트수정의 집");
    }

    @Test void cheerAndGuestbookCommitOnlyOnceAndUseExistingHousePermissions() {
        Long ai=ais.getFirst();
        User peer=human();
        Long house=ownHouse(ai);
        joins.join(peer.getId(),house);
        Long membership=membership(peer.getId(),house);
        var cheer=scheduled(ai,"cheer-once");
        var payload=mapper.readTree("{\"houseId\":"+house+",\"membershipId\":"+membership+",\"typeCode\":\"support\"}");
        String receipt=control.execute(cheer.id(),cheer.owner(),cheer.fencingToken(),AiUserActionExecutor.Action.HOUSE_CHEER,payload,"집 친구를 응원함");
        assertThat(control.execute(cheer.id(),cheer.owner(),cheer.fencingToken(),AiUserActionExecutor.Action.HOUSE_CHEER,payload,null)).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM house_member_cheers WHERE sender_user_id=? AND target_user_id=?",Integer.class,ai,peer.getId())).isEqualTo(1);
        var guestbook=scheduled(ai,"guestbook-once");
        var content=mapper.readTree("{\"houseId\":"+house+",\"roomOwnerId\":"+peer.getId()+",\"content\":\"함께 작은 계획을 이어가봐요\"}");
        control.execute(guestbook.id(),guestbook.owner(),guestbook.fencingToken(),AiUserActionExecutor.Action.GUESTBOOK_WRITE,content,"방명록을 남김");
        control.execute(guestbook.id(),guestbook.owner(),guestbook.fencingToken(),AiUserActionExecutor.Action.GUESTBOOK_WRITE,content,null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_guestbooks WHERE author_id=? AND room_owner_id=?",Integer.class,ai,peer.getId())).isEqualTo(1);
        var invalid=scheduled(ai,"guestbook-outside-house");
        var otherHouse=mapper.readTree("{\"houseId\":"+ownHouse(ais.get(1))+",\"roomOwnerId\":"+peer.getId()+",\"content\":\"권한 없는 방문\"}");
        assertThatThrownBy(() -> control.execute(invalid.id(),invalid.owner(),invalid.fencingToken(),AiUserActionExecutor.Action.GUESTBOOK_WRITE,otherHouse,null))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_guestbooks WHERE author_id=? AND room_owner_id=?",Integer.class,ai,peer.getId())).isEqualTo(1);
    }

    @Test void cleanupRewardHistoryAndMemoryRollBackTogetherAndRetryPaysOnce() {
        Long ai=ais.getFirst();
        User peer=human();
        Long house=ownHouse(ai);
        joins.join(peer.getId(),house);
        Long member=membership(peer.getId(),house);
        activateCobweb(peer.getId());
        var job=scheduled(ai,"clean-peer-once");
        var snapshot=mapper.valueToTree(control.snapshot(job.id(),job.owner(),job.fencingToken()));
        assertThat(snapshot.path("houseMembers").get(0).path("cleanableMembershipIds").toString()).contains(member.toString());
        var payload=mapper.readTree("{\"houseId\":"+house+",\"membershipId\":"+member+"}");
        int balance=coinBalance(ai);
        assertThatThrownBy(() -> control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.MEMBER_ROOM_CLEAN,payload,"x".repeat(1001)))
                .isInstanceOf(RuntimeException.class);
        assertThat(coinBalance(ai)).isEqualTo(balance);
        assertThat(jdbc.queryForObject("SELECT cleaned_at IS NULL FROM room_cobwebs WHERE room_user_id=?",Boolean.class,peer.getId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_histories WHERE user_id=? AND reason='COBWEB_CLEAN'",Integer.class,ai)).isZero();
        control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.MEMBER_ROOM_CLEAN,payload,"집 친구 방을 청소함");
        control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.MEMBER_ROOM_CLEAN,payload,null);
        assertThat(coinBalance(ai)).isEqualTo(balance+3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_histories WHERE user_id=? AND reason='COBWEB_CLEAN'",Integer.class,ai)).isEqualTo(1);
        activateCobweb(ai);
        var own=scheduled(ai,"clean-own");
        control.execute(own.id(),own.owner(),own.fencingToken(),AiUserActionExecutor.Action.ROOM_CLEAN,mapper.createObjectNode(),"내 방을 청소함");
        assertThat(coinBalance(ai)).isEqualTo(balance+6);
    }

    @Test void memberObservationsAndTheirMemoryLoseAccessAfterLeavingOrBlocking() {
        Long ai=ais.getFirst();
        User peer=human();
        tx.executeWithoutResult(s -> users.findById(peer.getId()).orElseThrow().changeNickname("같은집친구"));
        Long house=ownHouse(ai);
        joins.join(peer.getId(),house);
        var job=scheduled(ai,"member-observe");
        assertThat(mapper.writeValueAsString(control.snapshot(job.id(),job.owner(),job.fencingToken()))).contains("같은집친구");
        control.execute(job.id(),job.owner(),job.fencingToken(),AiUserActionExecutor.Action.WAIT,mapper.createObjectNode(),"같은집친구의 소개를 기억함");
        var next=scheduled(ai,"member-recall");
        jdbc.update("INSERT INTO user_blocks(blocker_user_id,blocked_user_id,created_at) VALUES (?,?,CURRENT_TIMESTAMP)",peer.getId(),ai);
        try {
            assertThat(mapper.writeValueAsString(control.snapshot(next.id(),next.owner(),next.fencingToken()))).doesNotContain("같은집친구");
            var cheer=mapper.readTree("{\"houseId\":"+house+",\"membershipId\":"+membership(peer.getId(),house)+",\"typeCode\":\"support\"}");
            assertThatThrownBy(() -> control.execute(next.id(),next.owner(),next.fencingToken(),AiUserActionExecutor.Action.HOUSE_CHEER,cheer,null))
                    .isInstanceOf(RuntimeException.class);
        } finally { jdbc.update("DELETE FROM user_blocks WHERE blocker_user_id=? AND blocked_user_id=?",peer.getId(),ai); }
        control.snapshot(next.id(),next.owner(),next.fencingToken());
        jdbc.update("UPDATE house_members SET status='LEFT',left_at=CURRENT_TIMESTAMP WHERE house_id=? AND user_id=?",house,peer.getId());
        assertThatThrownBy(() -> control.execute(next.id(),next.owner(),next.fencingToken(),AiUserActionExecutor.Action.WAIT,mapper.createObjectNode(),null))
                .isInstanceOf(RuntimeException.class);
        assertThat(mapper.writeValueAsString(control.snapshot(next.id(),next.owner(),next.fencingToken()))).doesNotContain("같은집친구");
    }

    @Test void accessoryActionsUseOwnedCharacterAndItemsAndExposeOnlyOwnCharacters() {
        tx.executeWithoutResult(status -> {
            var ai=users.findById(ais.getFirst()).orElseThrow();
            var other=users.findById(ais.get(1)).orElseThrow();
            var character=characterCatalog.saveAndFlush(new Character("ai-accessory-"+System.nanoTime(),"테스트 캐릭터","characters/test.png",9500,true));
            var own=ownedCharacters.saveAndFlush(UserCharacter.of(ai,character,Instant.now(),true));
            var otherCharacter=ownedCharacters.saveAndFlush(UserCharacter.of(other,character,Instant.now(),true));
            var theme=themes.saveAndFlush(new Theme("ai-accessory-"+System.nanoTime(),"테스트 테마",null,true));
            var item=items.saveAndFlush(new Item(theme,"accessory","character",null,"eyewear","테스트 안경",
                    CurrencyType.COIN,5,"items/test-glasses.png",false,true));
            var mine=ownedItems.saveAndFlush(UserItem.create(ai,item));
            var theirs=ownedItems.saveAndFlush(UserItem.create(other,item));
            renderProfiles.saveAndFlush(new CharacterAccessoryRenderProfile(item,character,"default",item.getAssetKey(),
                    180,172,320,160,new java.math.BigDecimal("0.5"),new java.math.BigDecimal("0.3"),new java.math.BigDecimal("0.5"),0,20));
            var equip=scheduled(ai.getId(),"accessory-equip");
            var state=mapper.valueToTree(control.snapshot(equip.id(),equip.owner(),equip.fencingToken()));
            assertThat(state.path("myCharacters").path("items").size()).isEqualTo(1);
            assertThat(state.path("myCharacters").path("items").get(0).path("userCharacterId").asLong()).isEqualTo(own.getId());
            var payload=mapper.readTree("{\"userCharacterId\":"+own.getId()+",\"userItemId\":"+mine.getId()+"}");
            control.execute(equip.id(),equip.owner(),equip.fencingToken(),AiUserActionExecutor.Action.ACCESSORY_EQUIP,payload,"안경을 착용함");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_character_accessories WHERE user_character_id=?",Integer.class,own.getId())).isEqualTo(1);
            var unequip=scheduled(ai.getId(),"accessory-unequip");
            control.execute(unequip.id(),unequip.owner(),unequip.fencingToken(),AiUserActionExecutor.Action.ACCESSORY_UNEQUIP,
                    mapper.readTree("{\"userCharacterId\":"+own.getId()+",\"characterSlotType\":\"eyewear\"}"),"안경을 해제함");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_character_accessories WHERE user_character_id=?",Integer.class,own.getId())).isZero();
            var denied=scheduled(ai.getId(),"accessory-not-owned");
            assertThatThrownBy(() -> control.execute(denied.id(),denied.owner(),denied.fencingToken(),AiUserActionExecutor.Action.ACCESSORY_EQUIP,
                    mapper.readTree("{\"userCharacterId\":"+otherCharacter.getId()+",\"userItemId\":"+theirs.getId()+"}"),null))
                    .isInstanceOf(com.triples.rougether.common.error.BusinessException.class)
                    .satisfies(error -> assertThat(((com.triples.rougether.common.error.BusinessException)error).getErrorCode())
                            .isEqualTo(com.triples.rougether.userapi.member.error.MemberErrorCode.CHARACTER_NOT_OWNED));
            assertThatThrownBy(() -> control.execute(denied.id(),denied.owner(),denied.fencingToken(),AiUserActionExecutor.Action.ACCESSORY_EQUIP,
                    mapper.readTree("{\"userCharacterId\":"+own.getId()+",\"userItemId\":"+theirs.getId()+"}"),null))
                    .isInstanceOf(com.triples.rougether.common.error.BusinessException.class)
                    .satisfies(error -> assertThat(((com.triples.rougether.common.error.BusinessException)error).getErrorCode())
                            .isEqualTo(com.triples.rougether.userapi.character.error.CharacterAccessoryErrorCode.ACCESSORY_NOT_OWNED));
            status.setRollbackOnly();
        });
    }

    private Long ownHouse(Long ai) {
        return jdbc.queryForObject("SELECT house_id FROM house_members WHERE user_id=? AND status='ACTIVE' ORDER BY id LIMIT 1",Long.class,ai);
    }
    private Long membership(Long user,Long house) {
        return jdbc.queryForObject("SELECT id FROM house_members WHERE house_id=? AND user_id=? AND status='ACTIVE'",Long.class,house,user);
    }
    private AiUserJobs.Job scheduled(Long ai,String key) {
        jobs.enqueue(ai,key,10,"SCHEDULE",null);
        return jobs.claim("extended-worker");
    }
    private void activateCobweb(Long user) {
        rooms.getMyRoom(user);
        var now=java.sql.Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO room_cobwebs(room_user_id,appeared_at,updated_at) VALUES (?,?,?)",user,now,now);
    }
    private int coinBalance(Long user) {
        return jdbc.queryForObject("SELECT balance FROM user_wallets WHERE user_id=? AND currency_type='COIN'",Integer.class,user);
    }
}
