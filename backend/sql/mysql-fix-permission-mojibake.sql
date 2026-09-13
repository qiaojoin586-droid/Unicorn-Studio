-- =============================================================
-- 修复 sys_permission 表 id 41-50（learning / service 权限码）中文乱码
-- -------------------------------------------------------------
-- 现象：name / module 字段出现「瀛︿範璧勬枡鍒楄〃」类 mojibake
-- 根因：早期（V9 service、learning 模块）执行迁移时 mysql 客户端未加
--       --default-character-set=utf8mb4，UTF-8 字节被按 GBK 解码后写入。
--       V10 之后的迁移（capability/about/footer/retention）均带 utf8mb4，故未受影响。
-- 修复：以 code（UNIQUE）为准，重写为 mysql-init.sql 中的正确中文。
-- 幂等：重复执行仅再次写入相同正确值，无副作用。
-- =============================================================
SET NAMES utf8mb4;

-- learning 权限码（id 41-46）
UPDATE sys_permission SET name = '学习资料列表', module = '学习资料' WHERE code = 'learning:list';
UPDATE sys_permission SET name = '新增学习资料', module = '学习资料' WHERE code = 'learning:create';
UPDATE sys_permission SET name = '编辑学习资料', module = '学习资料' WHERE code = 'learning:edit';
UPDATE sys_permission SET name = '删除学习资料', module = '学习资料' WHERE code = 'learning:delete';
UPDATE sys_permission SET name = '上下架资料',   module = '学习资料' WHERE code = 'learning:publish';
UPDATE sys_permission SET name = '资料分类管理', module = '学习资料' WHERE code = 'learning:category';

-- service 权限码（id 47-50）
UPDATE sys_permission SET name = '招收方向列表', module = '主要招收方向' WHERE code = 'service:list';
UPDATE sys_permission SET name = '新增招收方向', module = '主要招收方向' WHERE code = 'service:create';
UPDATE sys_permission SET name = '编辑招收方向', module = '主要招收方向' WHERE code = 'service:edit';
UPDATE sys_permission SET name = '删除招收方向', module = '主要招收方向' WHERE code = 'service:delete';
