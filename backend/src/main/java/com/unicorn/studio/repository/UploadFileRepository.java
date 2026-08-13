package com.unicorn.studio.repository;

import com.unicorn.studio.model.UploadFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UploadFileRepository extends JpaRepository<UploadFile, Long>, JpaSpecificationExecutor<UploadFile> {
}
