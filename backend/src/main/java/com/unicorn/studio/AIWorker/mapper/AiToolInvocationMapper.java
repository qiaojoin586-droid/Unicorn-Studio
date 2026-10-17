package com.unicorn.studio.AIWorker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.AIWorker.entity.AiToolInvocation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工具调用审计 Mapper（文档 §11.7）。
 * <p>
 * <b>刻意为空</b>：写入是「每次工具调用一行」，走 {@code insert}（BaseMapper 自带）；
 * messageId 回填（§11.7 第 4 条）用 {@code updateById} 同样够。等 ⑧ 真有工具被调用、
 * 出现查询需求时，再按项目惯例在 Service 里拼 {@code LambdaQueryWrapper}，不在这里堆查询。
 */
@Mapper
public interface AiToolInvocationMapper extends BaseMapper<AiToolInvocation> {
}
