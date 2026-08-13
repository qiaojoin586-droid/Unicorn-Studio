package com.unicorn.studio.repository;

import com.unicorn.studio.model.PublicUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface PublicUserRepository extends JpaRepository<PublicUser, Long>, JpaSpecificationExecutor<PublicUser> {

    Optional<PublicUser> findByEmail(String email);

    boolean existsByEmail(String email);

    long countByStatus(Integer status);
}
