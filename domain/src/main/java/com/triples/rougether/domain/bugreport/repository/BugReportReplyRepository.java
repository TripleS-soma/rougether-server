package com.triples.rougether.domain.bugreport.repository;

import com.triples.rougether.domain.bugreport.entity.BugReportReply;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BugReportReplyRepository extends JpaRepository<BugReportReply, Long> {

    // 유저 내 제보 목록용. 제보별 답장을 오래된 순으로 묶는다(대화 흐름 순서).
    List<BugReportReply> findByBugReportIdInOrderByIdAsc(Collection<Long> bugReportIds);

    // 어드민 목록용. 작성 어드민 표시를 위해 adminUser 를 함께 로딩한다.
    @Query("select r from BugReportReply r join fetch r.adminUser"
            + " where r.bugReport.id in :bugReportIds order by r.id asc")
    List<BugReportReply> findWithAdminByBugReportIdIn(@Param("bugReportIds") Collection<Long> bugReportIds);
}
