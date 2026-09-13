package com.unicorn.studio.AIWorker.vo;

import java.util.List;

/**
 * AI 助手能力概览 VO（前台/后台通用，用于展示「当前有哪些能力」）。
 *
 * @param name         助手名称
 * @param available    服务是否可用（配置齐全）
 * @param streaming    是否支持流式输出（当前恒为 true）
 * @param tools        已注册的工具名列表（工具层扩展位，为空表示未装配）
 * @param model        当前使用的模型
 */
public record AiCapabilitiesVO(String name,
                               boolean available,
                               boolean streaming,
                               List<String> tools,
                               String model) {

    public static final String ASSISTANT_NAME = "小独角兽";
}
