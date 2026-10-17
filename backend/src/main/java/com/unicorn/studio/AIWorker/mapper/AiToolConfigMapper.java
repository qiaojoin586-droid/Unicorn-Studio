package com.unicorn.studio.AIWorker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.AIWorker.entity.AiToolConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工具配置 Mapper（文档 §11.2）。
 * <p>
 * <b>本类刻意是空的</b>：工具配置的读取都要带条件（scope + enabled + priority 排序），
 * 而按项目惯例「列表 + 可选条件 + 分页一律在 Service 用 {@code LambdaQueryWrapper} 拼」——
 * mapper 里既不放分页方法，也不放"一句话能说完"的查询（目前一个都没用到）。
 * 后台 CRUD（⑪）落地时，按需在此加 {@code @Update} 之类 wrapper 表达不了的方法。
 */
@Mapper
public interface AiToolConfigMapper extends BaseMapper<AiToolConfig> {
}
