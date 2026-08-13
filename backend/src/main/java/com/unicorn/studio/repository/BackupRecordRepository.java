package com.unicorn.studio.repository;

import com.unicorn.studio.model.BackupRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface BackupRecordRepository extends JpaRepository<BackupRecord, Long>, JpaSpecificationExecutor<BackupRecord> {

    List<BackupRecord> findTop7ByOrderByCreatedAtDesc();
}
