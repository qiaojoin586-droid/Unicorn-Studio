#!/usr/bin/env bash
# Execute one deployment SQL file in fail-fast mysql batch mode.
set -euo pipefail

case "${1:-}" in
  init) sql_name='01-init.sql' ;;
  upgrade) sql_name='02-upgrade.sql' ;;
  *) printf 'Usage: bash run-sql.sh init|upgrade\n' >&2; exit 2 ;;
esac

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
sql_path="${script_dir}/sql/${sql_name}"
mysql_container="${DEPLOY_MYSQL_CONTAINER:-mysql-server}"
mysql_user="${DEPLOY_MYSQL_USER:-root}"

if [[ ! -f "$sql_path" ]]; then
  printf 'SQL file not found: %s\n' "$sql_path" >&2
  exit 1
fi

printf 'Executing %s in container %s. Back up an existing database before upgrading.\n' "$sql_name" "$mysql_container"
IFS= read -r -s -p "MySQL password for ${mysql_user}: " MYSQL_PWD
printf '\n'
export MYSQL_PWD
trap 'unset MYSQL_PWD' EXIT

# Pass the environment variable by NAME, never put its value in process args.
# -i streams the SQL file; omit -t so stdin remains a real batch stream.
# --no-defaults prevents an inherited --force option from hiding SQL errors.
docker exec -i --env MYSQL_PWD "$mysql_container" \
  mysql --no-defaults --default-character-set=utf8mb4 --batch --binary-mode \
  --user="$mysql_user" < "$sql_path"

printf 'SQL completed successfully. The output above must contain DEPLOYMENT_SQL_OK.\n'
