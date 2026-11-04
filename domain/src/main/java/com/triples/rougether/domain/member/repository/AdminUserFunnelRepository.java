package com.triples.rougether.domain.member.repository;

import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 어드민 유저 퍼널(이탈 지점) 전용 읽기 저장소. 개인정보·루틴 본문은 읽지 않고 가입 창 안의 실사용자 id 와
// 이정표(온보딩·루틴/투두 생성·첫 완료·공동집) 도달 여부만 투영함. createdFrom 이 null 이면 전체 기간.
// 실사용자 = 탈퇴하지 않은 비봇·비AI 계정(UserRepository 의 어드민 요약과 같은 기준).
public interface AdminUserFunnelRepository extends Repository<User, Long> {

    @Query("select u.id as userId, u.createdAt as createdAt, u.lastAccessedAt as lastAccessedAt from User u "
            + "where u.deletedAt is null and u.bot = false and u.ai = false "
            + "and (:createdFrom is null or u.createdAt >= :createdFrom) order by u.id")
    List<FunnelUserRow> findHumanUsers(@Param("createdFrom") Instant createdFrom);

    // 온보딩 완료 계약은 UserRepository.countOnboardingCompletedUsers 와 같음(목표 1개 이상 + 미삭제 대표 캐릭터).
    @Query("select u.id from User u "
            + "where u.deletedAt is null and u.bot = false and u.ai = false "
            + "and (:createdFrom is null or u.createdAt >= :createdFrom) "
            + "and exists (select ug.id from UserGoal ug where ug.user = u) "
            + "and exists (select uc.id from UserCharacter uc "
            + "  where uc.user = u and uc.selected = true and uc.deletedAt is null)")
    List<Long> findOnboardingCompletedUserIds(@Param("createdFrom") Instant createdFrom);

    // 루틴 또는 투두를 한 번이라도 만든 사용자. 이후 삭제·보관돼도 "만든 적 있음"은 유지함.
    @Query("select u.id from User u "
            + "where u.deletedAt is null and u.bot = false and u.ai = false "
            + "and (:createdFrom is null or u.createdAt >= :createdFrom) "
            + "and (exists (select r.id from Routine r where r.user = u) "
            + "  or exists (select t.id from Todo t where t.user = u))")
    List<Long> findRoutineOrTodoCreatedUserIds(@Param("createdFrom") Instant createdFrom);

    @Query("select u.id from User u "
            + "where u.deletedAt is null and u.bot = false and u.ai = false "
            + "and (:createdFrom is null or u.createdAt >= :createdFrom) "
            + "and (exists (select l.id from RoutineLog l "
            + "    where l.routine.user = u and l.status = :routineLogStatus) "
            + "  or exists (select t.id from Todo t where t.user = u and t.status = :todoStatus))")
    List<Long> findFirstCompletionUserIds(@Param("createdFrom") Instant createdFrom,
                                          @Param("routineLogStatus") RoutineLogStatus routineLogStatus,
                                          @Param("todoStatus") TodoStatus todoStatus);

    // 첫 완료 = 루틴 로그 COMPLETED 또는 투두 COMPLETED 가 하나라도 "현재 남아 있는" 사용자.
    // 당일 완료 취소는 로그 삭제, 과거 완료 취소는 FAILED, 투두 완료 해제는 PENDING, 카테고리 정리는 로그 삭제로
    // 기록이 사라지므로 "한 번이라도 완료함"과는 다름(생성 단계와 달리 취소분은 빠짐).
    default List<Long> findFirstCompletionUserIds(Instant createdFrom) {
        return findFirstCompletionUserIds(createdFrom, RoutineLogStatus.COMPLETED, TodoStatus.COMPLETED);
    }

    // 공동집 = 현재 ACTIVE·미삭제 집에 나 외 ACTIVE 비봇·비탈퇴 사용자가 한 명 이상인 사용자
    // (AdminRetentionMetricRepository.findSharedUserIds 와 같은 정의 - 동거 봇만 있는 기본 집은 제외).
    @Query("select distinct hm.user.id from HouseMember hm join hm.house h join hm.user u "
            + "where hm.status = :status and h.deletedAt is null "
            + "and u.deletedAt is null and u.bot = false and u.ai = false "
            + "and (:createdFrom is null or u.createdAt >= :createdFrom) "
            + "and exists (select peer.id from HouseMember peer join peer.user peerUser "
            + "  where peer.house = h and peer.user.id <> hm.user.id and peer.status = :status "
            + "  and peerUser.deletedAt is null and peerUser.bot = false and peerUser.ai = false)")
    List<Long> findSharedHouseUserIds(@Param("createdFrom") Instant createdFrom,
                                      @Param("status") HouseMemberStatus status);

    default List<Long> findSharedHouseUserIds(Instant createdFrom) {
        return findSharedHouseUserIds(createdFrom, HouseMemberStatus.ACTIVE);
    }

    // 같은 가입 창에서 탈퇴한 비봇·비AI 계정 수. 탈퇴 정리로 루틴·집 기록이 사라질 수 있어 단계가 아닌 부가 지표로만 셈.
    @Query("select count(u) from User u "
            + "where u.deletedAt is not null and u.bot = false and u.ai = false "
            + "and (:createdFrom is null or u.createdAt >= :createdFrom)")
    long countWithdrawnUsers(@Param("createdFrom") Instant createdFrom);

    interface FunnelUserRow {
        Long getUserId();

        Instant getCreatedAt();

        Instant getLastAccessedAt();
    }
}
