package com.unicorn.studio.AIWorker.mapper;

import com.unicorn.studio.mapper.SiteSettingsMapper;
import com.unicorn.studio.model.SiteSettings;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * {@link AiConfigMapper} 的实现 —— 委托给全局唯一的 {@link SiteSettingsMapper}。
 * <p>
 * 这样做的两个理由：
 * <ol>
 *   <li>MyBatis-Plus 对同一实体（{@code SiteSettings}）只允许注册一个 Mapper 接口，
 *       再声明一个 {@code extends BaseMapper<SiteSettings>} 的接口会与既有 Mapper 冲突；</li>
 *   <li>模块边界清晰：AIWorker 有自己的 Mapper 抽象，但不重复持有数据访问能力，
 *       底层仍复用项目既有的 MyBatis-Plus 通道。</li>
 * </ol>
 */
@Repository
public class AiConfigMapperImpl implements AiConfigMapper {

    private final SiteSettingsMapper delegate;

    public AiConfigMapperImpl(SiteSettingsMapper delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<SiteSettings> findByGroupName(String groupName) {
        return delegate.findByGroupName(groupName);
    }

    @Override
    public Optional<SiteSettings> findBySettingKey(String key) {
        return delegate.findBySettingKey(key);
    }

    @Override
    public int insert(SiteSettings setting) {
        return delegate.insert(setting);
    }

    @Override
    public int updateById(SiteSettings setting) {
        return delegate.updateById(setting);
    }

    @Override
    public int batchUpsert(List<SiteSettings> items) {
        return delegate.batchUpsert(items);
    }
}
