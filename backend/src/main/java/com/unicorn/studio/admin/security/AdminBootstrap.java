package com.unicorn.studio.admin.security;

import com.unicorn.studio.mapper.SysRoleMapper;
import com.unicorn.studio.mapper.SysUserMapper;
import com.unicorn.studio.mapper.SysUserRoleMapper;
import com.unicorn.studio.model.SysRole;
import com.unicorn.studio.model.SysUser;
import com.unicorn.studio.model.SysUserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 超管初始化（实施方案 §5.3，密码不硬编码）：
 * sys_user 无 admin 记录时创建：
 * - 初始密码取环境变量 ADMIN_INIT_PASSWORD；未配置则生成随机密码打印日志（仅本地开发）
 * - 首次登录强制改密（must_change_password=1）
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final String initPassword;

    public AdminBootstrap(SysUserMapper userMapper,
                          SysRoleMapper roleMapper,
                          SysUserRoleMapper userRoleMapper,
                          PasswordEncoder passwordEncoder,
                          @Value("${app.admin.init-password:}") String initPassword) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.initPassword = initPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userMapper.findByUsername("admin").isPresent()) {
            return;
        }
        SysRole superAdmin = roleMapper.findByCode("SUPER_ADMIN").orElse(null);
        if (superAdmin == null) {
            log.warn("SUPER_ADMIN 角色不存在（初始化 SQL 未执行？），跳过超管初始化");
            return;
        }

        String password = initPassword;
        boolean randomPassword = password == null || password.isBlank();
        if (randomPassword) {
            password = randomPassword();
            log.warn("ADMIN_INIT_PASSWORD 未配置，已生成随机初始密码（仅本地开发）");
            log.warn("初始管理员 admin / 初始密码: {}", password);
            log.warn("首次登录后请立即修改密码");
        }

        SysUser admin = SysUser.builder()
                .username("admin")
                .password(passwordEncoder.encode(password))
                .nickname("超级管理员")
                .status(1)
                .mustChangePassword(1)
                .failedLoginAttempts(0)
                .build();
        userMapper.insert(admin);

        userRoleMapper.insert(new SysUserRole(admin.getId(), superAdmin.getId()));
        log.info("初始管理员已创建: admin（角色 SUPER_ADMIN，{}密码）", randomPassword ? "随机" : "环境变量");
    }

    private String randomPassword() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
