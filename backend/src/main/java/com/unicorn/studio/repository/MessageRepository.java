package com.unicorn.studio.repository;

import com.unicorn.studio.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MessageRepository extends JpaRepository<Message, Long>, JpaSpecificationExecutor<Message> {

    long countByStatus(String status);
}
