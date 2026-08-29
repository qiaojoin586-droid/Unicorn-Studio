package com.unicorn.studio.admin.security;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.mapper.SysUserMapper;
import com.unicorn.studio.model.SysUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 账号锁定（实施方案 §5.2）：连续失败 5 次锁 30 分钟。
 * 状态存 sys_user（数据库持久化，重启/多实例不丢），与 IP 级内存限流独立生效。
 */
@Service
public class LoginLockService {

    private static final int MAX_FAILURES = 5;
    private static final int LOCK_MINUTES = 30;

    private final SysUserMapper userMapper;

    public LoginLockService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /** 锁定判定：锁定中（未到期）抛 423。 */
    public void checkLocked(SysUser user) {
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new BusinessException(HttpStatus.LOCKED, "账号已锁定，请 " + minutesLeft(user) + " 分钟后重试");
        }
    }

    @Transactional
    public void onFailure(SysUser user) {
        // 原子自增避免并发失败丢计数，再回读判断是否触达锁定阈值
        userMapper.incrementFailedAttempts(user.getId());
        SysUser fresh = userMapper.selectById(user.getId());
        if (fresh != null && fresh.getFailedLoginAttempts() != null
                && fresh.getFailedLoginAttempts() >= MAX_FAILURES) {
            fresh.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
            userMapper.updateById(fresh);
        }
    }

    @Transactional
    public void onSuccess(SysUser user) {
        if (user.getFailedLoginAttempts() != null && user.getFailedLoginAttempts() > 0) {
            user.setFailedLoginAttempts(0);
        }
        user.setLockedUntil(null);
        userMapper.updateById(user);
    }

    private long minutesLeft(SysUser user) {
        long millis = java.time.Duration.between(LocalDateTime.now(), user.getLockedUntil()).toMillis();
        return Math.max(1, millis / 60_000 + 1);
    }
}
