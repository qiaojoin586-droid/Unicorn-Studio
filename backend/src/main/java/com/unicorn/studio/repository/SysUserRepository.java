package com.unicorn.studio.repository;

import com.unicorn.studio.model.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUser, Long>, JpaSpecificationExecutor<SysUser> {

    Optional<SysUser> findByUsername(String username);

    Optional<SysUser> findByRefreshToken(String refreshToken);

    boolean existsByUsername(String username);
}
