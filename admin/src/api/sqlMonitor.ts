import { http } from '@/utils/request'

/** 单条 SQL 统计（Druid 内存统计，服务启动后累计） */
export interface SqlStatItem {
  /** SQL 语句（原始文本，已截断 2000 字符） */
  sql: string
  /** 执行次数 */
  executeCount: number
  /** 执行错误次数 */
  errorCount: number
  /** 累计耗时（ms） */
  totalTime: number
  /** 最慢单次耗时（ms） */
  maxTimespan: number
  /** 最慢单次发生时间（yyyy-MM-dd HH:mm:ss） */
  maxTimespanOccurTime?: string
  /** 最后执行时间（yyyy-MM-dd HH:mm:ss） */
  lastTime?: string
  /** 最大并发数 */
  concurrentMax: number
  /** 累计影响行数 */
  effectedRowCount: number
  /** 累计读取行数 */
  fetchRowCount: number
  /** 累计结果集持有时间（ms） */
  resultSetHoldTime: number
  /** 是否慢 SQL（≥ 2s） */
  slowSql: boolean
}

/** 连接池概览 */
export interface DataSourceStat {
  /** 活跃连接数 */
  activeCount: number
  /** 池中空闲连接数 */
  poolingCount: number
  /** 等待获取连接的线程数 */
  waitThreadCount: number
  /** 最大活跃连接数配置 */
  maxActive: number
  /** 最小空闲连接数配置 */
  minIdle: number
  /** 初始化连接数配置 */
  initialSize: number
  /** 累计执行 SQL 次数 */
  executeCount: number
  /** 累计错误数 */
  errorCount: number
  /** 累计逻辑连接创建数 */
  logicConnectCount: number
  /** 累计逻辑连接关闭数 */
  logicCloseCount: number
  /** 累计物理连接创建数 */
  physicalConnectCount: number
  /** 累计事务提交数 */
  commitCount: number
  /** 累计事务回滚数 */
  rollbackCount: number
  /** 数据源 URL */
  url?: string
  /** 数据库类型 */
  dbType?: string
}

export const sqlMonitorApi = {
  /** 连接池概览 */
  stat: () => http.get<DataSourceStat>('/api/admin/sql-monitor/stat'),
  /** SQL 统计列表（keyword 模糊 / sortKey 白名单排序 / 分页） */
  sqls: (params: Record<string, any>) =>
    http.get<{ list: SqlStatItem[]; total: number }>('/api/admin/sql-monitor/sqls', params),
  /** 清空统计 */
  reset: () => http.post('/api/admin/sql-monitor/reset'),
}
