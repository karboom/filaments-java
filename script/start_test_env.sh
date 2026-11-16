#!/usr/bin/env bash
# 启动本地测试环境（docker compose，仅 PostgreSQL），首次启动自动导入 base.sql 建好测试表
# 用法: ./script/start_test_env.sh [start|stop|reset|status|env|psql]   默认 start
#   start  启动（等待健康检查通过）
#   stop   停止并删除容器（数据为 tmpfs，不持久化）
#   reset  清理后重建全新环境
#   status 查看容器状态
#   env    打印测试所需环境变量（可 eval 进当前 shell）
#   psql   进入 psql
set -euo pipefail

# region 配置（均可用同名环境变量覆盖）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.test.yml"
PROJECT="filaments-test"
export PG_HOST_PORT="${PG_HOST_PORT:-5433}"
export DB_NAME="${DB_NAME:-filaments}"
export DB_USER="${DB_USER:-test}"
SCHEMA_TEST="filaments_test"        # 测试表所在 schema，与 TestDb 连接串 currentSchema 一致
DDL_SQL="${SCRIPT_DIR}/base.sql"    # 建表脚本唯一真相，测试代码与本机环境共用
OLD_PROJECT="user-micro-test"       # 历史项目名，遗留容器与卷需清理
OLD_PG_CONTAINER="user-micro-test-pg"  # 更早的单容器版本遗留
# endregion

# region 工具函数
dc() {
    "$RT" compose -p "$PROJECT" -f "$COMPOSE_FILE" "$@"
}

pick_runtime() {
    if command -v docker >/dev/null 2>&1; then
        RT="docker"
        docker compose version >/dev/null 2>&1 || { echo "错误: 缺少 docker compose v2" >&2; exit 1; }
    elif command -v podman >/dev/null 2>&1 && command -v podman-compose >/dev/null 2>&1; then
        RT="podman-compose"
        dc() { podman-compose -p "$PROJECT" -f "$COMPOSE_FILE" "$@"; }
    else
        echo "错误: 未找到 docker compose 或 podman-compose" >&2
        exit 1
    fi
}

# 清理旧项目名遗留：整套容器（含已从 compose 文件删掉的孤儿服务）、旧版单容器、旧命名卷
# 不清会占住 5433，新栈起不来
migrate_legacy() {
    if "$RT" ps -a --format '{{.Names}}' 2>/dev/null | grep -qE "^${OLD_PROJECT}-"; then
        echo "清理旧项目容器组 ${OLD_PROJECT} ..."
        "$RT" compose -p "$OLD_PROJECT" -f "$COMPOSE_FILE" down --remove-orphans -v >/dev/null 2>&1 || true
    fi
    "$RT" ps -a --format '{{.Names}}' 2>/dev/null | grep -qx "$OLD_PG_CONTAINER" \
        && { echo "清理旧版独立容器 $OLD_PG_CONTAINER ..."; "$RT" rm -f "$OLD_PG_CONTAINER" >/dev/null 2>&1 || true; }
    local v
    for v in $("$RT" volume ls -q -f name="^${OLD_PROJECT}_" 2>/dev/null); do
        echo "清理旧命名卷 $v ..."
        "$RT" volume rm "$v" >/dev/null 2>&1 || true
    done
}

check_port() {  # check_port <port> <覆盖变量名>
    if ss -ltn 2>/dev/null | grep -qE "[:.]$1([^0-9]|$)"; then
        echo "错误: 端口 $1 已被占用。可通过环境变量 $2 更换端口后重试" >&2
        exit 1
    fi
}

wait_healthy() {
    local name="${PROJECT}-pg-1"
    for _ in $(seq 1 60); do
        [ "$("$RT" inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$name" 2>/dev/null)" = "healthy" ] && return 0
        sleep 2
    done
    echo "错误: 等待服务健康超时，请查看: dc ps" >&2
    dc ps
    exit 1
}

# 空库时初始化：schema 与建表语句都写在 base.sql 里，这里只负责导入（已有表则跳过）
init_database() {
    local probe
    probe="$(dc exec -T pg psql -U "$DB_USER" -d "$DB_NAME" -Atc \
        "SELECT to_regclass('${SCHEMA_TEST}.filaments_user') IS NOT NULL;" 2>/dev/null || echo f)"
    [ "$probe" = "t" ] && return 0
    [ -f "$DDL_SQL" ] || { echo "错误: 找不到 $DDL_SQL" >&2; exit 1; }
    echo "初始化 ${DB_NAME}（导入 $(basename "$DDL_SQL")）..."
    dc exec -T pg psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q < "$DDL_SQL"
}

# 注意: 用 名称+值 分离的方式输出 export 行
emit() { printf 'export %s=%s\n' "$1" "$2"; }

print_env() {
    local jdbc="jdbc:postgresql://127.0.0.1:${PG_HOST_PORT}/${DB_NAME}?currentSchema=${SCHEMA_TEST}&stringtype=unspecified"
    echo "# 测试环境变量（可执行: eval \"\$(./script/start_test_env.sh env)\"）"
    emit DB_JDBC_URL "\"$jdbc\""
    emit DB_USER "$DB_USER"
    emit DB_PASSWORD '""'
}

summary() {
    echo "测试环境就绪 (PostgreSQL):"
    dc ps
    print_env
}
# endregion

# region 子命令
is_stack_running() {
    [ "$("$RT" inspect -f '{{.State.Running}}' "${PROJECT}-pg-1" 2>/dev/null)" = "true" ]
}

do_start() {
    migrate_legacy
    if ! is_stack_running; then
        check_port "$PG_HOST_PORT" "PG_HOST_PORT"
    fi
    dc up -d --wait >/dev/null 2>&1 || dc up -d >/dev/null
    wait_healthy
    init_database
    summary
}

do_stop() {
    dc down >/dev/null
    echo "已停止并删除容器（数据为 tmpfs，不保留；start 即全新环境）"
}

do_reset() {
    dc down -v >/dev/null   # 顺带清掉命名卷
    echo "重建全新环境..."
    do_start
}

do_status() {
    dc ps
}

do_psql() {
    dc exec pg psql -U "$DB_USER" -d "$DB_NAME"
}
# endregion

pick_runtime
case "${1:-start}" in
    start)  do_start ;;
    stop)   do_stop ;;
    reset)  do_reset ;;
    status) do_status ;;
    env)    print_env ;;
    psql)   do_psql ;;
    *) echo "用法: $0 [start|stop|reset|status|env|psql]" >&2; exit 1 ;;
esac
