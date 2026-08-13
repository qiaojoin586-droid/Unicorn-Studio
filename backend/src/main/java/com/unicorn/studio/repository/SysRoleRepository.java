package com.unicorn.studio.repository;

import com.unicorn.studio.model.SysRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SysRoleRepository extends JpaRepository<SysRole, Long> {

    Optional<SysRole> findByCode(String code);
}
