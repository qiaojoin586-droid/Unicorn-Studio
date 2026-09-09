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
import java.util.List;
import java.util.Objects;

import static com.unicorn.studio.admin.service.AdminPermissionService.SUPER_ADMIN;

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
        // 锁住内置角色行，使多实例启动时的“检查 + 创建 + 分配”保持串行。
        SysRole superAdmin = roleMapper.findByCodeForUpdate(SUPER_ADMIN);
        if (superAdmin == null) {
            log.warn("SUPER_ADMIN 角色不存在（初始化 SQL 未执行？），跳过超管初始化");
            return;
        }

        List<SysUserRole> assignments = userRoleMapper.findByRoleId(superAdmin.getId());
        if (assignments.size() > 1) {
            throw new IllegalStateException("检测到多个 SUPER_ADMIN 账号，请先清理 sys_user_role 中的重复超管关系");
        }
        if (assignments.size() == 1) {
            SysUser soleSuperAdmin = userMapper.selectById(assignments.getFirst().getUserId());
            if (soleSuperAdmin == null) {
                throw new IllegalStateException("SUPER_ADMIN 角色关联了不存在的管理员账号");
            }
            ensureEnabled(soleSuperAdmin);
            return;
        }

        SysUser existingAdmin = userMapper.findByUsername("admin").orElse(null);
        if (existingAdmin != null) {
            ensureEnabled(existingAdmin);
            userRoleMapper.insert(new SysUserRole(existingAdmin.getId(), superAdmin.getId()));
            log.info("已将现有 admin 账号设为唯一 SUPER_ADMIN");
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

    private void ensureEnabled(SysUser user) {
        if (!Objects.equals(user.getStatus(), 1)) {
            user.setStatus(1);
            userMapper.updateById(user);
            log.warn("唯一 SUPER_ADMIN 账号 {} 曾被禁用，已自动恢复启用", user.getUsername());
        }
    }

    private String randomPassword() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
