"""Reviewed cache revision schema and trigger generation (no database access)."""
from __future__ import annotations

import copy
import json
from pathlib import Path

DEPENDENCIES = Path(__file__).resolve().parents[2] / "src/main/resources/cache-dependencies.json"


def dependencies():
    return json.loads(DEPENDENCIES.read_text(encoding="utf-8"))


def extend_manifest(original):
    """Keep the observed base schema separate from this authored addition."""
    manifest = copy.deepcopy(original)
    manifest["tables"]["cache_revision"] = (
        "CREATE TABLE `cache_revision` (\n"
        "  `cache_domain` varchar(32) NOT NULL,\n"
        "  `epoch` char(36) NOT NULL,\n"
        "  `revision` bigint unsigned NOT NULL DEFAULT '1',\n"
        "  PRIMARY KEY (`cache_domain`)\n"
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci"
    )
    for ordinal, (name, kind, default) in enumerate([
        ("cache_domain", "varchar(32)", None), ("epoch", "char(36)", None),
        ("revision", "bigint unsigned", "1"),
    ], 1):
        textual = kind.startswith(("varchar", "char"))
        manifest["columns"].append(dict(
            name=name, type=kind, extra="", table="cache_revision", charset="utf8mb4" if textual else None,
            comment="", default=default, ordinal=ordinal, nullable="NO",
            collation="utf8mb4_0900_ai_ci" if textual else None,
            data_type=kind.split("(")[0].split()[0], generated="",
        ))
    manifest["indexes"].append(dict(
        seq=1, name="PRIMARY", type="BTREE", table="cache_revision", column="cache_domain",
        visible="YES", sub_part=None, expression=None, non_unique=0,
    ))
    return manifest


def trigger_names():
    return {
        f"us_cache_{table}_{event}"
        for table in dependencies()["tables"] for event in ("insert", "update", "delete")
    }


def tracking_sql(manifest):
    definition = dependencies()
    domains = definition["domains"]
    lines = ["\n-- 数据库变更监听：触发器与业务修改共用事务；只存域和版本，不复制业务数据。"]
    lines.append("INSERT IGNORE INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES\n"
                 + ",\n".join(f"('{domain}', UUID(), 1)" for domain in sorted(domains)) + ";")
    for table, targets in sorted(definition["tables"].items()):
        if table not in manifest["tables"] or not set(targets).issubset(domains):
            raise ValueError(f"Invalid cache dependency: {table}")
        values = ", ".join(f"('{domain}', UUID(), 1)" for domain in sorted(targets))
        for event in ("INSERT", "UPDATE", "DELETE"):
            name = f"us_cache_{table}_{event.lower()}"
            # Single-statement trigger bodies work with mysql batch mode, without DELIMITER.
            lines.extend([
                f"DROP TRIGGER IF EXISTS `{name}`;",
                f"CREATE TRIGGER `{name}` AFTER {event} ON `{table}` FOR EACH ROW\n"
                f"INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES {values}\n"
                "ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;",
            ])
    # Reinstalling triggers has a short DDL gap. Rotate epochs afterwards so changes in
    # that gap and restored databases invalidate every previously published generation.
    lines.append("UPDATE `cache_revision` SET `epoch` = UUID(), `revision` = 1;")
    lines.append(f"SELECT COUNT(*) AS cache_revision_domains FROM `cache_revision`;")
    return "\n".join(lines) + "\n"


def allowed_metadata_mutation(statement):
    return statement in {f"DROP TRIGGER IF EXISTS `{name}`" for name in trigger_names()} or statement == (
        "UPDATE `cache_revision` SET `epoch` = UUID(), `revision` = 1"
    )
