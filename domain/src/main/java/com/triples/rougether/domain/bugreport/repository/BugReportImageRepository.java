package com.triples.rougether.domain.bugreport.repository;

import com.triples.rougether.domain.bugreport.entity.BugReportImage;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BugReportImageRepository extends JpaRepository<BugReportImage, Long> {

    // 목록 화면용 일괄 조회 (N+1 회피). 정렬은 제보별 sort_order.
    List<BugReportImage> findByBugReportIdInOrderByBugReportIdDescSortOrderAsc(Collection<Long> bugReportIds);
}
