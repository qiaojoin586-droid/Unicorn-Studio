package com.unicorn.studio.AIWorker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.AIWorker.entity.AiToolInvocation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 工具调用审计 Mapper（文档 §11.7）。
 * <p>
 * <b>写入侧刻意为空</b>：写入是「每次工具调用一行」，走 {@code insert}（BaseMapper 自带）；
 * messageId 回填（§11.7 第 4 条）用 {@code updateById} 同样够。等 ⑧ 真有工具被调用、
 * 出现查询需求时，再按项目惯例在 Service 里拼 {@code LambdaQueryWrapper}，不在这里堆查询。
 */
@Mapper
public interface AiToolInvocationMapper extends BaseMapper<AiToolInvocation> {

    /**
     * 删除保留期之前的调用记录（分批物理删除）。
     * <p>
     * ⚠️ <b>为什么必须有</b>：这张表是「每次工具调用一行」⇒ 随使用量线性增长，
     * 而它没有别的清理入口 ⇒ 不清就会无限堆积（§7.4 / §11.7 的要求）。
     * <p>
     * ⚠️ <b>为什么用 DELETE ... LIMIT 而不是一条 DELETE</b>：一条 DELETE 会锁全表；
     * 分批（每批 {@code limit} 行）把锁范围和单次事务体积都压住。
     * ⚠️ 走 {@code idx_ai_tool_inv_created} 索引（created_at）。
     *
     * @param cutoff 保留截止时间，严格删除 {@code created_at < cutoff} 的行
     * @param limit  单批最大删除行数
     * @return 本批实际删除行数
     */
    @Delete("DELETE FROM ai_tool_invocation WHERE created_at < #{cutoff} LIMIT #{limit}")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
