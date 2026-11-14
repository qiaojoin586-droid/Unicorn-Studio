"""Build the two deployment SQL files from a reviewed structure-only manifest.

This tool has no database connection and never executes SQL.  Business exports
such as 02-data.sql are deliberately not inputs.
"""
from __future__ import annotations

import argparse
import collections
import json
import re
from pathlib import Path
from cache_change_tracking import extend_manifest, tracking_sql, allowed_metadata_mutation, trigger_names, dependencies


SQL_ROOT = Path(__file__).resolve().parents[1]
MANIFEST = SQL_ROOT / "schema" / "deployment-schema.json"
OUTPUT = SQL_ROOT / "server-migration"


def sql_string(value):
    if value is None:
        return "NULL"
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''").replace("\n", "\\n").replace("\r", "\\r") + "'"


def identifier(value):
    if not re.fullmatch(r"[a-zA-Z_][a-zA-Z0-9_]*", value):
        raise ValueError(f"Unexpected identifier: {value!r}")
    return f"`{value}`"


def split_sql(text, separator=";", remove_comments=True):
    """Split outside strings, quoted identifiers, comments and parentheses."""
    pieces, current = [], []
    quote, depth, pos = None, 0, 0
    while pos < len(text):
        char = text[pos]
        nxt = text[pos + 1:pos + 2]
        if quote:
            current.append(char)
            if char == "\\" and quote != "`" and nxt:
                pos += 1
                current.append(text[pos])
            elif char == quote:
                if nxt == quote:
                    pos += 1
                    current.append(text[pos])
                else:
                    quote = None
        elif char in "'\"`":
            quote = char
            current.append(char)
        elif char == "#" or (char == "-" and nxt == "-" and (pos + 2 == len(text) or text[pos + 2].isspace())):
            end = text.find("\n", pos)
            end = len(text) if end < 0 else end
            current.append("\n" if remove_comments else text[pos:end])
            pos = end - 1
        elif char == "/" and nxt == "*":
            end = text.find("*/", pos + 2)
            if end < 0:
                raise ValueError("Unterminated SQL comment")
            current.append(" " if remove_comments else text[pos:end + 2])
            pos = end + 1
        elif char == "(":
            depth += 1
            current.append(char)
        elif char == ")":
            depth -= 1
            if depth < 0:
                raise ValueError("Unbalanced SQL parentheses")
            current.append(char)
        elif char == separator and depth == 0:
            piece = "".join(current).strip()
            if piece:
                pieces.append(piece)
            current = []
        else:
            current.append(char)
        pos += 1
    if quote or depth:
        raise ValueError("Unterminated SQL string or parentheses")
    if "".join(current).strip():
        pieces.append("".join(current).strip())
    return pieces


def statement_guard(condition, message):
    # PREPARE cannot prepare SIGNAL in MySQL 8.0. A deliberately unresolved
    # column in a SELECT without FROM reliably stops mysql batch execution.
    # Both branches are read-only and cannot touch application records.
    return (
        f"SET @us_sql := IF(({condition}), 'DO 0', {sql_string('SELECT ' + identifier(message))});\n"
        "PREPARE us_stmt FROM @us_sql;\nEXECUTE us_stmt;\nDEALLOCATE PREPARE us_stmt;\n"
    )


def conditional_ddl(exists, ddl):
    return (
        f"SET @us_sql := IF(EXISTS ({exists}), 'DO 0', {sql_string(ddl)});\n"
        "PREPARE us_stmt FROM @us_sql;\nEXECUTE us_stmt;\nDEALLOCATE PREPARE us_stmt;\n"
    )


def table_order(manifest):
    dependencies = {name: set() for name in manifest["tables"]}
    for fk in manifest["foreign_keys"]:
        if fk["table"] != fk["ref_table"]:
            dependencies[fk["table"]].add(fk["ref_table"])
    ordered = []
    while dependencies:
        ready = sorted(name for name, parents in dependencies.items() if not parents)
        if not ready:
            raise ValueError("Cyclic foreign keys need a reviewed migration")
        ordered.extend(ready)
        for name in ready:
            del dependencies[name]
        for parents in dependencies.values():
            parents.difference_update(ready)
    return ordered


def table_parts(ddl):
    # SHOW CREATE TABLE formats each definition on its own line. A newline
    # inside a comment/string must not be mistaken for another definition.
    start, end = ddl.index("("), ddl.rindex(")")
    return split_sql(ddl[start + 1:end], separator=",")


def index_groups(manifest):
    grouped = collections.defaultdict(list)
    for row in manifest["indexes"]:
        grouped[(row["table"], row["name"])].append(row)
    return {key: sorted(rows, key=lambda row: row["seq"]) for key, rows in grouped.items()}


def index_exists(table, name, rows):
    if any(row["expression"] for row in rows):
        raise ValueError("Functional indexes require an explicit reviewed signature")
    signature = ",".join(row["column"] + (f"({row['sub_part']})" if row["sub_part"] else "") for row in rows)
    # Match an equivalent index even when an older installation used another
    # name. PRIMARY KEY remains distinct from ordinary UNIQUE indexes.
    primary_condition = "INDEX_NAME = 'PRIMARY'" if name == "PRIMARY" else "INDEX_NAME <> 'PRIMARY'"
    return (
        "SELECT 1 FROM information_schema.STATISTICS "
        f"WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = {sql_string(table)} AND {primary_condition} "
        "GROUP BY INDEX_NAME "
        f"HAVING MIN(NON_UNIQUE) = {rows[0]['non_unique']} AND MIN(INDEX_TYPE) = {sql_string(rows[0]['type'])} "
        "AND MIN(IS_VISIBLE = 'YES') = 1 "
        "AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) "
        f"ORDER BY SEQ_IN_INDEX SEPARATOR ',') = {sql_string(signature)}"
    )


def foreign_key_exists(table, name, rows):
    rows = sorted(rows, key=lambda row: row["ordinal"])
    first = rows[0]
    normalize_rule = lambda rule: "RESTRICT" if rule == "NO ACTION" else rule
    return (
        "SELECT 1 FROM information_schema.KEY_COLUMN_USAGE k "
        "JOIN information_schema.REFERENTIAL_CONSTRAINTS r "
        "ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.TABLE_NAME = k.TABLE_NAME "
        "AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME "
        f"WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = {sql_string(table)} "
        f"AND k.REFERENCED_TABLE_NAME = {sql_string(first['ref_table'])} "
        "AND k.REFERENCED_TABLE_SCHEMA = DATABASE() "
        "GROUP BY k.CONSTRAINT_NAME "
        f"HAVING REPLACE(MIN(r.UPDATE_RULE), 'NO ACTION', 'RESTRICT') = {sql_string(normalize_rule(first['update_rule']))} "
        f"AND REPLACE(MIN(r.DELETE_RULE), 'NO ACTION', 'RESTRICT') = {sql_string(normalize_rule(first['delete_rule']))} "
        "AND GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ',') = "
        f"{sql_string(','.join(row['column'] for row in rows))} "
        "AND GROUP_CONCAT(k.REFERENCED_COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ',') = "
        f"{sql_string(','.join(row['ref_column'] for row in rows))}"
    )


def derived_rows(rows, columns):
    return "\nUNION ALL\n".join(
        "SELECT " + ", ".join(sql_string(row[column]) + (f" AS {identifier(column)}" if index == 0 else "") for column in columns)
        for index, row in enumerate(rows)
    )


def verification_sql(manifest):
    expected = derived_rows(manifest["columns"], ["table", "name", "type", "nullable", "default", "extra", "charset", "collation", "generated"])
    mismatch = (
        "c.COLUMN_NAME IS NULL OR NOT (LOWER(c.COLUMN_TYPE) <=> e.`type`) "
        "OR NOT (c.IS_NULLABLE <=> e.`nullable`) OR NOT (c.COLUMN_DEFAULT <=> e.`default`) "
        "OR NOT (LOWER(c.EXTRA) <=> LOWER(e.`extra`)) "
        "OR NOT (c.CHARACTER_SET_NAME <=> e.`charset`) OR NOT (c.COLLATION_NAME <=> e.`collation`) "
        "OR NOT (c.GENERATION_EXPRESSION <=> e.`generated`)"
    )
    column_issues = (
        "SELECT e.`table` AS table_name, e.`name` AS column_name, e.`type` AS expected_type, "
        "c.COLUMN_TYPE AS actual_type, e.`nullable` AS expected_nullable, c.IS_NULLABLE AS actual_nullable, "
        "e.`default` AS expected_default, c.COLUMN_DEFAULT AS actual_default, "
        "e.`extra` AS expected_extra, c.EXTRA AS actual_extra, "
        "e.`collation` AS expected_collation, c.COLLATION_NAME AS actual_collation "
        f"FROM (\n{expected}\n) e LEFT JOIN information_schema.COLUMNS c "
        "ON c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = e.`table` AND c.COLUMN_NAME = e.`name` "
        f"WHERE {mismatch}"
    )
    table_names = ", ".join(map(sql_string, sorted(manifest["tables"])))
    checks = []
    for (table, name), rows in sorted(index_groups(manifest).items()):
        checks.append(f"SELECT {sql_string(table + '.' + name)} AS object_name, IF(EXISTS ({index_exists(table, name, rows)}), 'OK', 'FAIL') AS result")
    foreign_keys = collections.defaultdict(list)
    for row in manifest["foreign_keys"]:
        foreign_keys[(row["table"], row["name"])].append(row)
    for (table, name), rows in sorted(foreign_keys.items()):
        checks.append(f"SELECT {sql_string(table + '.' + name)}, IF(EXISTS ({foreign_key_exists(table, name, rows)}), 'OK', 'FAIL')")
    object_checks = "\nUNION ALL\n".join(checks)
    sql = (
        "-- 内置结构核对：仅核对预期对象，保留线上额外表、额外字段及额外索引。\n"
        "SET @us_column_report := (SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT("
        "'table', table_name, 'column', column_name, 'expected_type', expected_type, 'actual_type', actual_type, "
        "'expected_nullable', expected_nullable, 'actual_nullable', actual_nullable, "
        "'expected_default', expected_default, 'actual_default', actual_default, "
        "'expected_extra', expected_extra, 'actual_extra', actual_extra, "
        "'expected_collation', expected_collation, 'actual_collation', actual_collation)), JSON_ARRAY()) "
        f"FROM (\n{column_issues}\n) issues);\n"
        "SET @us_column_issues := JSON_LENGTH(@us_column_report);\n"
        "SELECT IF(@us_column_issues = 0, 'OK', 'FAIL') AS column_structure, JSON_PRETTY(@us_column_report) AS column_differences;\n"
        "SET @us_table_issues := (SELECT COUNT(*) FROM information_schema.TABLES "
        f"WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ({table_names}) "
        "AND (ENGINE <> 'InnoDB' OR TABLE_COLLATION <> 'utf8mb4_0900_ai_ci'));\n"
        f"SET @us_table_count := (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ({table_names}) AND TABLE_TYPE = 'BASE TABLE');\n"
        "SET @us_object_report := (SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('object', object_name, 'result', result)), JSON_ARRAY()) "
        f"FROM (\n{object_checks}\n) objects WHERE result = 'FAIL');\n"
        "SET @us_object_issues := JSON_LENGTH(@us_object_report);\n"
        "SELECT IF(@us_object_issues = 0, 'OK', 'FAIL') AS indexes_and_foreign_keys, JSON_PRETTY(@us_object_report) AS object_differences;\n"
        f"SELECT DATABASE() AS database_name, @us_table_count AS expected_tables_found, {len(manifest['columns'])} AS expected_columns, "
        "@us_table_issues AS table_option_issues, @us_object_issues AS index_or_foreign_key_issues;\n"
    )
    sql += statement_guard(
        f"@us_column_issues = 0 AND @us_table_issues = 0 AND @us_object_issues = 0 AND @us_table_count = {len(manifest['tables'])}",
        "SCHEMA_CHECK_FAILED_REVIEW_DIFFERENCES_ABOVE",
    )
    return sql


def build(manifest):
    if any(manifest.get(key) for key in ["triggers", "routines", "events", "checks"]):
        raise ValueError("Non-table objects require an explicit reviewed export")
    manifest = extend_manifest(manifest)
    groups = index_groups(manifest)
    order = table_order(manifest)
    common_header = (
        "-- Unicorn Studio / MySQL 8.0.19+ / utf8mb4_0900_ai_ci\n"
        f"-- 基础结构来源：{manifest['source']['date']} 只读核对 MySQL {manifest['source']['version']}；新增缓存版本表为代码定义。当前共 "
        f"{len(order)} 表 / {len(manifest['columns'])} 列 / {len(groups)} 索引 / {len(manifest['foreign_keys'])} 外键。\n"
        "-- 本文件仅为部署代码；整理时未执行任何 DDL/DML，未读取本地业务记录。\n"
        "-- 系统默认值来自仓库 SQL，不包含数据库中的用户、内容、密码或 API Key。\n"
        "-- 使用 run-sql.sh 或 mysql 批处理标准输入执行；不要加 --force，也不要在交互窗口 SOURCE。\n"
        "-- 批处理遇到 ERROR 必须停止；交互 SOURCE 可能继续后续语句，不适用于本文件的失败保护。\n"
        "-- DDL 会隐式提交；已有数据库升级前请先备份，事务不能替代备份。\n"
        "SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n"
        "SET @us_original_sql_mode := @@SESSION.sql_mode;\n"
        "SET SESSION sql_mode = TRIM(BOTH ',' FROM REPLACE(CONCAT(',', @@SESSION.sql_mode, ','), ',NO_BACKSLASH_ESCAPES,', ','));\n"
        "SET @us_original_group_concat_max_len := @@SESSION.group_concat_max_len;\n"
        "SET SESSION group_concat_max_len = CAST(GREATEST(@@SESSION.group_concat_max_len, 8192) AS UNSIGNED);\n"
    )
    init = common_header + (
        "\n-- 01：只用于不存在或尚无表的数据库；已有业务库使用 02-upgrade.sql。\n"
        "CREATE DATABASE IF NOT EXISTS `unicorn_studio` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n"
        "USE `unicorn_studio`;\n"
    )
    init += statement_guard(
        "NOT EXISTS (SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE())",
        "INIT_REQUIRES_EMPTY_DATABASE_USE_02_UPGRADE_SQL",
    )
    upgrade = common_header + (
        "\n-- 02：只用于已有数据库；自动补齐缺表、可安全新增的列和缺失的索引/外键。\n"
        "-- 不自动 MODIFY 已有列，不缩窄类型，不删除记录，也不重建已有索引。\n"
        "-- 类型、默认值等不一致会在内置核对处中止；请按差异人工评审。\n"
        "USE `unicorn_studio`;\n"
    )
    for table in order:
        ddl = manifest["tables"][table]
        init += f"\n-- {table}\n{ddl};\n"
        upgrade += f"\n-- 补齐缺表：{table}\n{ddl.replace('CREATE TABLE ', 'CREATE TABLE IF NOT EXISTS ', 1)};\n"
    upgrade += "\n-- 补齐缺失列。无默认值的非空基础列不自动填充虚构数据，交由核对步骤拒绝。\n"
    for table in order:
        definitions = {re.match(r"`([^`]+)`", part).group(1): part for part in table_parts(manifest["tables"][table]) if part.startswith('`')}
        previous = None
        for row in sorted((row for row in manifest["columns"] if row["table"] == table), key=lambda row: row["ordinal"]):
            safe_to_add = row["nullable"] == "YES" or row["default"] is not None
            if safe_to_add and "auto_increment" not in row["extra"].lower():
                position = f" AFTER {identifier(previous)}" if previous else " FIRST"
                ddl = f"ALTER TABLE {identifier(table)} ADD COLUMN {definitions[row['name']]}{position}"
                exists = "SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() " + f"AND TABLE_NAME = {sql_string(table)} AND COLUMN_NAME = {sql_string(row['name'])}"
                upgrade += f"\n-- {table}.{row['name']}\n" + conditional_ddl(exists, ddl)
            previous = row["name"]
    upgrade += "\n-- 按结构识别等价索引，已有其他名称但结构相同的索引不会重复创建。\n"
    for table in order:
        for part in table_parts(manifest["tables"][table]):
            match = re.match(r"(?:UNIQUE )?KEY `([^`]+)`", part)
            if not match and not part.startswith("PRIMARY KEY"):
                continue
            name = match.group(1) if match else "PRIMARY"
            exists = index_exists(table, name, groups[(table, name)])
            upgrade += f"\n-- {table}.{name}\n" + conditional_ddl(exists, f"ALTER TABLE {identifier(table)} ADD {part}")
    foreign_keys = collections.defaultdict(list)
    for row in manifest["foreign_keys"]:
        foreign_keys[(row["table"], row["name"])].append(row)
    for table in order:
        for part in table_parts(manifest["tables"][table]):
            match = re.match(r"CONSTRAINT `([^`]+)` FOREIGN KEY", part)
            if match:
                name = match.group(1)
                upgrade += f"\n-- {table}.{name}\n" + conditional_ddl(foreign_key_exists(table, name, foreign_keys[(table, name)]), f"ALTER TABLE {identifier(table)} ADD {part}")
    verify = "\n" + verification_sql(manifest) + tracking_sql(manifest)
    trigger_list = ", ".join(sql_string(name) for name in sorted(trigger_names()))
    verify += statement_guard(
        "(SELECT COUNT(*) FROM cache_revision) = " + str(len(dependencies()["domains"]))
        + " AND (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE() "
        + f"AND TRIGGER_NAME IN ({trigger_list})) = {len(trigger_names())}",
        "CACHE_CHANGE_TRACKING_CHECK_FAILED",
    )
    seed_statements = []
    for seed_statement in manifest["seed_statements"]:
        if re.match(r"INSERT\s+IGNORE\s+INTO\s+`?sys_role_permission`?\b", seed_statement, re.I):
            mapping = re.fullmatch(r"(.*?\bWHERE\s+)(.*);", seed_statement, re.S | re.I)
            if mapping is None:
                raise ValueError("Role mapping must have an explicit reviewed WHERE clause")
            seed_statement = (
                mapping.group(1) + "(" + mapping.group(2).strip() + ")\n"
                "AND (JSON_CONTAINS(@us_existing_roles, JSON_QUOTE(r.code)) = 0 "
                "OR JSON_CONTAINS(@us_existing_permissions, JSON_QUOTE(p.code)) = 0);"
            )
        seed_statements.append(seed_statement)
    seeds = (
        "\n-- 系统初始化：只补仓库中已定义但目标库尚不存在的配置、权限和默认角色映射。\n"
        "-- INSERT IGNORE 保留已有同键配置，不导入作品、新闻、用户等业务记录。\n"
        "-- 默认授权只应用于本次新增角色或权限，不恢复已被管理员撤销的旧授权。\n"
        "START TRANSACTION;\n"
        "SET @us_existing_roles := (SELECT COALESCE(JSON_ARRAYAGG(code), JSON_ARRAY()) FROM sys_role);\n"
        "SET @us_existing_permissions := (SELECT COALESCE(JSON_ARRAYAGG(code), JSON_ARRAY()) FROM sys_permission);\n"
        + "\n\n".join(seed_statements) + "\nCOMMIT;\n"
        "SET SESSION group_concat_max_len = CAST(@us_original_group_concat_max_len AS UNSIGNED);\n"
        "SET SESSION sql_mode = @us_original_sql_mode;\n"
        "SELECT 'DEPLOYMENT_SQL_OK' AS result;\n"
    )
    return {"01-init.sql": init + verify + seeds, "02-upgrade.sql": upgrade + verify + seeds}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    files = build(manifest)
    args.output.mkdir(parents=True, exist_ok=True)
    for name, text in files.items():
        statements = split_sql(text)
        if any(re.match(r"(?:DROP|DELETE|TRUNCATE|REPLACE|UPDATE)\b", statement, re.I)
               and not allowed_metadata_mutation(statement) for statement in statements):
            raise ValueError("Destructive/data-overwriting statement found")
        (args.output / name).write_text(text, encoding="utf-8", newline="\n")
        print(f"{name}: {len(statements)} statements; {len(text.encode('utf-8'))} bytes")


if __name__ == "__main__":
    main()
