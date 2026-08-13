package com.unicorn.studio.repository;

import com.unicorn.studio.model.SysOperationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SysOperationLogRepository extends JpaRepository<SysOperationLog, Long>, JpaSpecificationExecutor<SysOperationLog> {
}
