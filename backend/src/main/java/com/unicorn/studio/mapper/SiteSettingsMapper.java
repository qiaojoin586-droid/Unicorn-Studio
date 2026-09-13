package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.SiteSettings;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

public interface SiteSettingsMapper extends BaseMapper<SiteSettings> {

    /**
     * 批量 upsert（B-11）：<b>一条语句</b>完成 N 个键的「存在则更新、不存在则插入」。
     * <p>
     * <b>为什么需要它</b>：配置保存是本项目后台最高频的写操作，而原先的实现是
     * 「循环内逐条 {@code findBySettingKey} + 逐条 {@code updateById}/{@code insert}」——
     * 保存页脚 11 个 key 要 22 条语句、关于页 6 个要 12 条，事务持有时长与入参长度线性相关。
     * 改为单条语句后，无论提交多少个键都是 <b>1 次往返</b>。
     * <p>
     * 依赖 {@code setting_key} 上的 UNIQUE 约束（见 mysql-init.sql）来判定冲突。
     * <p>
     * <b>语义与原先逐条实现严格一致</b>：
     * <ul>
     *   <li>命中已有键时<b>只更新 setting_value</b> —— 与原先「加载实体后仅 setValue 再 updateById」
     *       的效果相同：{@code group_name} 与 {@code description} 都保持库中原值不被覆盖；</li>
     *   <li>{@code updated_at} 显式置 NOW()：自定义 SQL 不经过 MP 的 {@code MetaObjectHandler}，
     *       不显式写就不会更新（该列无 ON UPDATE CURRENT_TIMESTAMP）。</li>
     * </ul>
     * ⚠️ 调用方必须保证 {@code items} 非空 —— 空列表会生成语法非法的 SQL。
     * <p>
     * 注：{@code VALUES()} 函数自 MySQL 8.0.20 起被标记为废弃（推荐用行别名语法），
     * 但仍可用；此处取兼容性优先，未使用 8.0.19+ 才支持的别名写法。
     */
    @Insert("<script>"
            + "INSERT INTO site_settings (group_name, setting_key, setting_value, updated_at) VALUES "
            + "<foreach collection='items' item='it' separator=','>"
            + "(#{it.groupName}, #{it.settingKey}, #{it.settingValue}, NOW())"
            + "</foreach>"
            + " ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW()"
            + "</script>")
    int batchUpsert(@Param("items") List<SiteSettings> items);

    default Optional<SiteSettings> findBySettingKey(String key) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SiteSettings>().eq(SiteSettings::getSettingKey, key)));
    }

    default List<SiteSettings> findAllByOrderByGroupNameAscSettingKeyAsc() {
        return selectList(new LambdaQueryWrapper<SiteSettings>()
                .orderByAsc(SiteSettings::getGroupName, SiteSettings::getSettingKey));
    }

    /** 按分组读取（V12：专用页面配置，如 about 组）。 */
    default List<SiteSettings> findByGroupName(String groupName) {
        return selectList(new LambdaQueryWrapper<SiteSettings>()
                .eq(SiteSettings::getGroupName, groupName)
                .orderByAsc(SiteSettings::getSettingKey));
    }
}
