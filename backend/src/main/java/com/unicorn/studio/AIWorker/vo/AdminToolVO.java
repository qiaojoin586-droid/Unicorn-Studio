package com.unicorn.studio.AIWorker.vo;

import java.time.LocalDateTime;

/**
 * 后台工具列表的一行（§6.5 接口 6）。
 * <p>
 * ⚠️ <b>不含完整描述与请求头</b>：列表只给"够判断状态"的字段。
 * 请求头里可能有解密后的敏感值，不该整表拉出来。
 *
 * @param id                主键
 * @param toolName          工具名（模型调用时引用）
 * @param toolType          builtin / api / mcp
 * @param displayName       后台显示名
 * @param description       用途描述（模型路由依据）
 * @param scope             public / admin / mcp
 * @param enabled           是否启用
 * @param priority          单轮装配优先级（越小越优先）
 * @param timeoutMs         超时（毫秒）
 * @param httpUrl           目标地址（编辑弹窗要回填它；不是敏感值）
 * @param paramSchema       参数 JSON Schema（编辑弹窗要回填它）
 * @param fingerprint       当前指纹
 * @param pendingConfirm    是否「描述已变更、待确认」（仅 api/mcp 可能为 true）
 * @param circuitRemainingSeconds 熔断剩余秒数（0 = 未熔断）
 * @param confirmedAt       上次确认时间
 * @param updatedAt         最后修改时间
 */
public record AdminToolVO(Long id,
                          String toolName,
                          String toolType,
                          String displayName,
                          String description,
                          String scope,
                          boolean enabled,
                          int priority,
                          int timeoutMs,
                          String httpUrl,
                          String paramSchema,
                          String fingerprint,
                          boolean pendingConfirm,
                          long circuitRemainingSeconds,
                          LocalDateTime confirmedAt,
                          LocalDateTime updatedAt) {
}
