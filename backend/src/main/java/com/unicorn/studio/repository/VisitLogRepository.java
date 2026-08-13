package com.unicorn.studio.repository;

import com.unicorn.studio.model.VisitLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface VisitLogRepository extends JpaRepository<VisitLog, Long>, JpaSpecificationExecutor<VisitLog> {

    /** 某段时间内按 ip_hash 去重的 UV（按路径过滤） */
    @Query("select distinct v.ipHash from VisitLog v where v.visitedAt >= :from and v.visitedAt < :to and (:path is null or v.pagePath = :path)")
    List<String> findDistinctIpHashes(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("path") String path);

    long countByVisitedAtGreaterThanEqualAndVisitedAtLessThan(LocalDateTime from, LocalDateTime to);
}
