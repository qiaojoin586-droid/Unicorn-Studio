package com.unicorn.studio.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** 每日聚合统计（由定时任务按 visit_log 等汇总，stat_date 唯一）。（已迁移至 MyBatis Plus） */
@TableName("stats_daily")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatsDaily {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private LocalDate statDate;

    private Integer pv;

    private Integer uv;

    private Integer newUsers;

    private Integer newMessages;

    private Integer newComments;
}
