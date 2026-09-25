#!/usr/bin/env bash

# 生产联合备份涉及真实数据和停机窗口，禁止命令跟踪，避免环境变量或命令参数进入日志。
set +x
set -Eeuo pipefail
umask 077

PROJECT_NAME='campus-resource-platform'
DATABASE_NAME='campus_resource_platform'
DEFAULT_OUTPUT_DIR='/srv/campusshare/backups'
LOCK_DIRECTORY='/run/campusshare-deploy08'
LOCK_FILE="$LOCK_DIRECTORY/backup.lock"
MIN_FREE_BYTES=5368709120
QUIESCE_TIMEOUT_SECONDS=120
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)
COMPOSE_FILE="$SCRIPT_DIR/../docker-compose.yml"
HTTPS_COMPOSE_FILE="$SCRIPT_DIR/../docker-compose.https.yml"
ENV_FILE="$SCRIPT_DIR/../.env"
OUTPUT_DIR=$DEFAULT_OUTPUT_DIR
GPG_RECIPIENT=''
SIGNING_KEY=''
EXECUTE=0
ACKNOWLEDGE_DOWNTIME=0
RUN_ID=''
RUN_DIRECTORY=''
ARTIFACT_INDEX=''
SERVICES_TOUCHED=0
BACKUP_COMPLETED=0
RESTORE_FAILED=0
declare -a PARTIAL_FILES=()

usage() {
    cat <<'EOF'
用法：sudo ./deploy08-backup.sh --gpg-recipient <指纹或UID> [选项]

默认只执行生产备份预检，不停止服务、不创建备份。

选项：
  --gpg-recipient VALUE       必填；服务器上已导入的离线恢复公钥收件人
  --signing-key ABSOLUTE_PATH 必填；root-only 的专用 Ed25519 备份签名私钥
  --output-dir ABSOLUTE_PATH  加密备份目录，默认 /srv/campusshare/backups
  --execute                   执行真实联合备份
  --acknowledge-downtime      确认本次操作会停止公网入口和应用服务
  --help                      显示帮助

真实执行必须同时提供 --execute 与 --acknowledge-downtime。
EOF
}

fail() {
    printf 'DEPLOY-08 联合备份失败：%s\n' "$1" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "缺少必需命令：$1"
}

validate_safe_name() {
    case "$1" in
        ''|*[!A-Za-z0-9_.-]*) fail "$2 包含不安全字符。" ;;
    esac
}

compose() {
    docker compose --env-file "$ENV_FILE" \
        -f "$COMPOSE_FILE" -f "$HTTPS_COMPOSE_FILE" \
        -p "$PROJECT_NAME" "$@"
}

get_service_id() {
    local service=$1
    local raw count
    raw=$(compose ps --all --quiet "$service")
    count=$(printf '%s\n' "$raw" | awk 'NF { count++ } END { print count+0 }')
    [ "$count" -eq 1 ] || fail "必须且只能定位一个 $PROJECT_NAME/$service 容器。"
    printf '%s\n' "$raw" | awk 'NF { print; exit }'
}

get_volume() {
    local logical_name=$1
    local raw count volume project_label logical_label
    raw=$(docker volume ls --quiet \
        --filter "label=com.docker.compose.project=$PROJECT_NAME" \
        --filter "label=com.docker.compose.volume=$logical_name")
    count=$(printf '%s\n' "$raw" | awk 'NF { count++ } END { print count+0 }')
    [ "$count" -eq 1 ] || fail "必须且只能定位一个 Compose volume：$logical_name"
    volume=$(printf '%s\n' "$raw" | awk 'NF { print; exit }')
    project_label=$(docker volume inspect --format '{{ index .Labels "com.docker.compose.project" }}' "$volume")
    logical_label=$(docker volume inspect --format '{{ index .Labels "com.docker.compose.volume" }}' "$volume")
    [ "$project_label" = "$PROJECT_NAME" ] || fail "卷项目标签不匹配：$logical_name"
    [ "$logical_label" = "$logical_name" ] || fail "卷逻辑标签不匹配：$logical_name"
    printf '%s\n' "$volume"
}

assert_container_volume() {
    local container_id=$1
    local destination=$2
    local expected_volume=$3
    local matches
    matches=$(docker inspect --format \
        '{{range .Mounts}}{{printf "%s\t%s\t%s\n" .Destination .Type .Name}}{{end}}' "$container_id" \
        | awk -F '\t' -v destination="$destination" '$1 == destination { print $2 ":" $3 }')
    [ "$matches" = "volume:$expected_volume" ] \
        || fail "容器挂载与预期卷不一致：destination=$destination"
}

paths_overlap() {
    local first=${1%/}
    local second=${2%/}
    [ "$first" = "$second" ] || [[ "$first/" == "$second/"* ]] || [[ "$second/" == "$first/"* ]]
}

assert_container_healthy() {
    local container_id=$1
    local service=$2
    local state health
    state=$(docker inspect --format '{{.State.Status}}' "$container_id")
    health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")
    [ "$state" = 'running' ] || fail "$service 容器未运行：$state"
    [ "$health" = 'healthy' ] || fail "$service 容器不健康：$health"
}

wait_service_healthy() {
    local service=$1
    local timeout_seconds=${2:-240}
    local deadline container_id state
    deadline=$((SECONDS + timeout_seconds))
    while [ "$SECONDS" -lt "$deadline" ]; do
        container_id=$(compose ps --all --quiet "$service" 2>/dev/null || true)
        if [ -n "$container_id" ]; then
            state=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
                "$container_id" 2>/dev/null || true)
            [ "$state" = 'healthy' ] && return 0
            case "$state" in exited|dead) return 1 ;; esac
        fi
        sleep 2
    done
    return 1
}

redis_cli() {
    local redis_container=$1
    shift
    docker exec "$redis_container" sh -c \
        'exec redis-cli --no-auth-warning -a "$REDIS_PASSWORD" "$@"' deploy08-redis "$@"
}

assert_no_pending_download_delta() {
    local redis_container=$1
    local delta_count syncing_keys
    if ! delta_count=$(redis_cli "$redis_container" HLEN 'crp:stats:resource:download:delta'); then
        return 1
    fi
    delta_count=$(printf '%s' "$delta_count" | tr -d '[:space:]')
    case "$delta_count" in ''|*[!0-9]*) return 1 ;; esac
    [ "$delta_count" = '0' ] || return 1
    if ! syncing_keys=$(redis_cli "$redis_container" --scan \
            --pattern 'crp:stats:resource:download:syncing:*'); then
        return 1
    fi
    [ -z "$syncing_keys" ]
}

wait_download_delta_drained() {
    local redis_container=$1
    local deadline consecutive=0
    deadline=$((SECONDS + QUIESCE_TIMEOUT_SECONDS))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if assert_no_pending_download_delta "$redis_container"; then
            consecutive=$((consecutive + 1))
            [ "$consecutive" -ge 2 ] && return 0
        else
            consecutive=0
        fi
        sleep 5
    done
    return 1
}

volume_helper() {
    local backend_image=$1
    local volume=$2
    local command=$3
    docker run --rm --user 0:0 --entrypoint /bin/sh \
        --mount "type=volume,source=$volume,target=/volume,readonly" \
        "$backend_image" -eu -c "$command"
}

volume_size_bytes() {
    local backend_image=$1
    local volume=$2
    local kib
    kib=$(volume_helper "$backend_image" "$volume" 'du -sk /volume | cut -f1')
    printf '%s\n' "$((kib * 1024))"
}

encrypt_stream() {
    local final_path=$1
    local partial_path="$final_path.partial"
    gpg --batch --yes --trust-model always --recipient "$GPG_RECIPIENT" \
        --encrypt --output "$partial_path"
}

finalize_artifact() {
    local final_path=$1
    local partial_path="$final_path.partial"
    [ -s "$partial_path" ] || fail "加密产物为空：$(basename -- "$final_path")"
    chmod 0600 "$partial_path"
    mv -- "$partial_path" "$final_path"
    record_artifact "$final_path"
}

record_artifact() {
    local path=$1
    local name sha bytes
    name=$(basename -- "$path")
    sha=$(sha256sum "$path" | awk '{ print $1 }')
    bytes=$(stat -c '%s' "$path")
    printf '%s\t%s\t%s\n' "$name" "$sha" "$bytes" >>"$ARTIFACT_INDEX"
}

restore_services() {
    local restored_backend restored_frontend restored_backend_container restored_frontend_container
    [ "$SERVICES_TOUCHED" -eq 1 ] || return 0
    printf '%s\n' '[DEPLOY-08] 正在恢复生产服务到运行状态。'
    if ! compose up -d --no-build --pull never mysql redis backend frontend >/dev/null; then
        RESTORE_FAILED=1
        printf '%s\n' 'DEPLOY-08 严重错误：无法重新启动生产服务。' >&2
        return 1
    fi
    for service in mysql redis backend frontend; do
        if ! wait_service_healthy "$service" 300; then
            RESTORE_FAILED=1
            printf 'DEPLOY-08 严重错误：恢复后服务未健康：%s\n' "$service" >&2
            return 1
        fi
    done
    restored_backend_container=$(compose ps --all --quiet backend 2>/dev/null || true)
    restored_frontend_container=$(compose ps --all --quiet frontend 2>/dev/null || true)
    if [ -z "$restored_backend_container" ] || [ -z "$restored_frontend_container" ]; then
        RESTORE_FAILED=1
        printf '%s\n' 'DEPLOY-08 严重错误：恢复后无法定位应用容器。' >&2
        return 1
    fi
    restored_backend=$(docker inspect --format '{{.Image}}' "$restored_backend_container" 2>/dev/null || true)
    restored_frontend=$(docker inspect --format '{{.Image}}' "$restored_frontend_container" 2>/dev/null || true)
    if [ "$restored_backend" != "$backend_image" ] || [ "$restored_frontend" != "$frontend_image" ]; then
        RESTORE_FAILED=1
        printf '%s\n' 'DEPLOY-08 严重错误：恢复后的应用镜像身份与停机前不一致。' >&2
        return 1
    fi
    return 0
}

cleanup_partial_files() {
    local path
    for path in "${PARTIAL_FILES[@]:-}"; do
        [ -n "$path" ] || continue
        case "$path" in
            "$RUN_DIRECTORY"/*.partial) rm -f -- "$path" ;;
            *) printf '拒绝清理运行目录外的临时文件：%s\n' "$path" >&2 ;;
        esac
    done
    if [ -n "$RUN_DIRECTORY" ] && [ -d "$RUN_DIRECTORY" ]; then
        case "$RUN_DIRECTORY" in
            "$OUTPUT_DIR"/*)
                find "$RUN_DIRECTORY" -mindepth 1 -maxdepth 1 -type f -name '*.partial' -delete
                ;;
            *) printf '拒绝清理输出目录外的运行目录：%s\n' "$RUN_DIRECTORY" >&2 ;;
        esac
    fi
}

on_exit() {
    local status=$?
    trap - EXIT HUP INT TERM
    set +e
    # 生产可用性优先于失败产物清理，避免异常文件系统扩大停机时间。
    restore_services
    cleanup_partial_files
    [ "$RESTORE_FAILED" -eq 0 ] || status=1
    if [ "$BACKUP_COMPLETED" -eq 0 ] && [ -n "$RUN_DIRECTORY" ]; then
        printf 'DEPLOY-08：本次备份未完成，保留目录供审计：%s\n' "$RUN_DIRECTORY" >&2
    fi
    exit "$status"
}

trap on_exit EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

while [ "$#" -gt 0 ]; do
    case "$1" in
        --gpg-recipient)
            [ "$#" -ge 2 ] || fail '--gpg-recipient 缺少参数。'
            GPG_RECIPIENT=$2
            shift 2
            ;;
        --signing-key)
            [ "$#" -ge 2 ] || fail '--signing-key 缺少参数。'
            SIGNING_KEY=$2
            shift 2
            ;;
        --output-dir)
            [ "$#" -ge 2 ] || fail '--output-dir 缺少参数。'
            OUTPUT_DIR=$2
            shift 2
            ;;
        --execute)
            EXECUTE=1
            shift
            ;;
        --acknowledge-downtime)
            ACKNOWLEDGE_DOWNTIME=1
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *) fail "未知参数：$1" ;;
    esac
done

[ "$(id -u)" -eq 0 ] || fail '必须通过 sudo 以 root 身份运行。'
[ -n "$GPG_RECIPIENT" ] || fail '必须提供 --gpg-recipient。'
case "$OUTPUT_DIR" in /*) ;; *) fail '--output-dir 必须是绝对路径。' ;; esac
[ "$EXECUTE" -eq 0 ] || [ "$ACKNOWLEDGE_DOWNTIME" -eq 1 ] \
    || fail '--execute 必须同时提供 --acknowledge-downtime。'
[ -n "$SIGNING_KEY" ] || fail '必须提供 --signing-key。'
[ ! -L "$SIGNING_KEY" ] || fail '--signing-key 不得是符号链接。'
case "$SIGNING_KEY" in /*) ;; *) fail '--signing-key 必须是绝对路径。' ;; esac

for command_name in docker git gpg flock realpath sha256sum stat awk sed grep sort gzip python3 df \
    openssl base64 tar cut find ssh-keygen install; do
    require_command "$command_name"
done
docker info >/dev/null 2>&1 || fail 'Docker Engine 不可用。'
docker compose version >/dev/null 2>&1 || fail 'Docker Compose 不可用。'
[ -f "$COMPOSE_FILE" ] || fail '找不到基础 Compose 文件。'
[ -f "$HTTPS_COMPOSE_FILE" ] || fail '找不到 HTTPS Compose 文件。'
[ -f "$ENV_FILE" ] || fail '找不到生产 deploy/.env。'
[ "$(stat -c '%a' "$ENV_FILE")" = '600' ] || fail '生产 deploy/.env 权限必须为 600。'

OUTPUT_DIR=$(realpath -e -- "$OUTPUT_DIR") || fail '输出目录必须预先创建。'
[ -d "$OUTPUT_DIR" ] || fail '输出路径不是目录。'
[ "$(stat -c '%u' "$OUTPUT_DIR")" -eq 0 ] || fail '输出目录必须属于 root。'
output_mode=$(stat -c '%a' "$OUTPUT_DIR")
[ $((0$output_mode & 0077)) -eq 0 ] || fail '输出目录不得授予 group/other 权限。'
case "$OUTPUT_DIR/" in "$REPOSITORY_ROOT/"*) fail '输出目录不得位于 Git 仓库内。' ;; esac

SIGNING_KEY=$(realpath -e -- "$SIGNING_KEY") || fail '签名私钥不存在。'
[ -f "$SIGNING_KEY" ] || fail '签名私钥不是普通文件。'
[ "$(stat -c '%u' "$SIGNING_KEY")" -eq 0 ] || fail '签名私钥必须属于 root。'
[ "$(stat -c '%a' "$SIGNING_KEY")" = '600' ] || fail '签名私钥权限必须为 600。'
case "$SIGNING_KEY/" in "$REPOSITORY_ROOT/"*|"$OUTPUT_DIR/"*) fail '签名私钥不得位于仓库或备份目录内。' ;; esac
signing_public_key=$(ssh-keygen -y -P '' -f "$SIGNING_KEY" 2>/dev/null) \
    || fail '签名私钥必须是可非交互读取的有效 OpenSSH 私钥。'
case "$signing_public_key" in
    'ssh-ed25519 '*) ;;
    *) fail '签名私钥必须是用途隔离的 Ed25519 OpenSSH 私钥。' ;;
esac

# 锁目录必须位于不可由普通用户预置文件的 /run 直属路径，避免 FIFO 阻塞和符号链接截断。
[ ! -L "$LOCK_DIRECTORY" ] || fail '锁目录不得是符号链接。'
install -d -o root -g root -m 0700 -- "$LOCK_DIRECTORY"
[ "$(realpath -e -- "$LOCK_DIRECTORY")" = "$LOCK_DIRECTORY" ] || fail '锁目录规范路径不匹配。'
[ -d "$LOCK_DIRECTORY" ] && [ ! -L "$LOCK_DIRECTORY" ] || fail '锁目录不是普通目录。'
[ "$(stat -c '%u:%g:%a' "$LOCK_DIRECTORY")" = '0:0:700' ] \
    || fail '锁目录必须为 root:root 且权限为 700。'
if [ -e "$LOCK_FILE" ] || [ -L "$LOCK_FILE" ]; then
    [ ! -L "$LOCK_FILE" ] && [ -f "$LOCK_FILE" ] \
        || fail '锁文件必须是专用目录中的普通文件。'
    [ "$(stat -c '%u:%g' "$LOCK_FILE")" = '0:0' ] || fail '锁文件必须属于 root:root。'
fi
exec 9>"$LOCK_FILE"
chmod 0600 "$LOCK_FILE"
flock -n 9 || fail '已有 DEPLOY-08 备份进程正在运行。'

branch=$(git -C "$REPOSITORY_ROOT" branch --show-current)
commit=$(git -C "$REPOSITORY_ROOT" rev-parse HEAD)
worktree_status=$(git -C "$REPOSITORY_ROOT" status --porcelain=v1 --untracked-files=all)
[ "$branch" = 'deploy' ] || fail "必须在 deploy 分支运行，当前为 $branch。"
[ -z "$worktree_status" ] || fail '生产备份要求 Git 工作区干净。'

recipient_listing=$(gpg --batch --with-colons --list-keys "$GPG_RECIPIENT" 2>/dev/null) \
    || fail '找不到指定的 GPG 收件人公钥。'
recipient_count=$(printf '%s\n' "$recipient_listing" | awk -F: '$1 == "pub" { count++ } END { print count+0 }')
[ "$recipient_count" -eq 1 ] || fail 'GPG 收件人必须且只能匹配一个主公钥。'
recipient_fingerprint=$(printf '%s\n' "$recipient_listing" | awk -F: '$1 == "fpr" { print $10; exit }')
case "$recipient_fingerprint" in
    ''|*[!0-9A-Fa-f]*) fail '无法唯一解析 GPG 收件人公钥指纹。' ;;
esac

mysql_container=$(get_service_id mysql)
redis_container=$(get_service_id redis)
backend_container=$(get_service_id backend)
frontend_container=$(get_service_id frontend)
assert_container_healthy "$mysql_container" mysql
assert_container_healthy "$redis_container" redis
assert_container_healthy "$backend_container" backend
assert_container_healthy "$frontend_container" frontend

mysql_volume=$(get_volume mysql_data)
redis_volume=$(get_volume redis_data)
uploads_volume=$(get_volume uploads_data)
assert_container_volume "$mysql_container" '/var/lib/mysql' "$mysql_volume"
assert_container_volume "$redis_container" '/data' "$redis_volume"
assert_container_volume "$backend_container" '/data/uploads' "$uploads_volume"

for volume in "$mysql_volume" "$redis_volume" "$uploads_volume"; do
    volume_mountpoint=$(docker volume inspect --format '{{.Mountpoint}}' "$volume")
    volume_mountpoint=$(realpath -e -- "$volume_mountpoint") || fail "无法解析卷挂载点：$volume"
    if paths_overlap "$OUTPUT_DIR" "$volume_mountpoint"; then
        fail "输出目录不得与生产数据卷重叠：$volume"
    fi
done
backend_image=$(docker inspect --format '{{.Image}}' "$backend_container")
frontend_image=$(docker inspect --format '{{.Image}}' "$frontend_container")
case "$backend_image" in sha256:*) ;; *) fail '无法取得后端 immutable image ID。' ;; esac
case "$frontend_image" in sha256:*) ;; *) fail '无法取得前端 immutable image ID。' ;; esac
# 恢复服务时固定使用当前运行中的不可变镜像，避免维护窗口内可变 tag 漂移。
export BACKEND_IMAGE=$backend_image
export FRONTEND_IMAGE=$frontend_image

estimated_bytes=0
for volume in "$mysql_volume" "$redis_volume" "$uploads_volume"; do
    estimated_bytes=$((estimated_bytes + $(volume_size_bytes "$backend_image" "$volume")))
done
available_bytes=$(df -P -B1 "$OUTPUT_DIR" | awk 'NR == 2 { print $4 }')
required_bytes=$((estimated_bytes + MIN_FREE_BYTES))
[ "$available_bytes" -ge "$required_bytes" ] \
    || fail "备份磁盘空间不足：available=$available_bytes required=$required_bytes"

printf '[DEPLOY-08] 预检通过：commit=%s backend=%s frontend=%s estimatedBytes=%s availableBytes=%s\n' \
    "$commit" "$backend_image" "$frontend_image" "$estimated_bytes" "$available_bytes"
if [ "$EXECUTE" -eq 0 ]; then
    printf '%s\n' '[DEPLOY-08] 当前为预检模式；未停止服务，未创建备份。'
    exit 0
fi

RUN_ID=$(date -u '+%Y%m%dT%H%M%SZ')-$(openssl rand -hex 4 2>/dev/null || printf '%08x' "$RANDOM")
validate_safe_name "$RUN_ID" 'run-id'
RUN_DIRECTORY="$OUTPUT_DIR/$RUN_ID"
[ ! -e "$RUN_DIRECTORY" ] || fail '本轮备份目录已经存在。'
mkdir -m 0700 -- "$RUN_DIRECTORY"
ARTIFACT_INDEX="$RUN_DIRECTORY/artifacts.tsv.partial"
PARTIAL_FILES+=("$ARTIFACT_INDEX")
: >"$ARTIFACT_INDEX"

printf '%s\n' '[DEPLOY-08] 停止公网入口，等待下载增量排空。'
SERVICES_TOUCHED=1
compose stop --timeout 45 frontend >/dev/null
wait_download_delta_drained "$redis_container" \
    || fail '下载增量或 syncing 批次未在等待窗口内排空，拒绝创建恢复点。'
compose stop --timeout 45 backend >/dev/null
[ "$(docker inspect --format '{{.State.Status}}' "$frontend_container")" = 'exited' ] \
    || fail 'frontend 未完全停止。'
[ "$(docker inspect --format '{{.State.Status}}' "$backend_container")" = 'exited' ] \
    || fail 'backend 未完全停止。'
assert_no_pending_download_delta "$redis_container" \
    || fail '后端停止后仍存在下载增量或 syncing 批次。'

partial_upload=$(volume_helper "$backend_image" "$uploads_volume" \
    'find /volume -type f -name "*.part" -print -quit')
[ -z "$partial_upload" ] || fail '上传卷存在 .part 临时文件，拒绝备份。'
special_upload=$(volume_helper "$backend_image" "$uploads_volume" \
    'find /volume -mindepth 1 ! -type f ! -type d -print -quit')
[ -z "$special_upload" ] || fail '上传卷存在符号链接或特殊文件，拒绝备份。'
special_redis=$(volume_helper "$backend_image" "$redis_volume" \
    'find /volume -mindepth 1 ! -type f ! -type d -print -quit')
[ -z "$special_redis" ] || fail 'Redis 卷存在符号链接或特殊文件，拒绝备份。'

wait_aof=$(redis_cli "$redis_container" WAITAOF 1 0 5000)
first_wait_value=$(printf '%s\n' "$wait_aof" | awk '/^[0-9]+$/ { print; exit }')
[ "${first_wait_value:-0}" -ge 1 ] || fail 'Redis WAITAOF 未确认本地 AOF 写入。'
redis_info=$(redis_cli "$redis_container" INFO persistence)
for required_state in 'loading:0' 'aof_enabled:1' 'aof_last_write_status:ok' 'aof_pending_bio_fsync:0'; do
    printf '%s\n' "$redis_info" | grep -F "$required_state" >/dev/null \
        || fail "Redis AOF 状态不满足恢复点要求：$required_state"
done
cut_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')

mysql_artifact="$RUN_DIRECTORY/mysql.sql.gz.gpg"
docker exec "$mysql_container" sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --protocol=socket -uroot --single-transaction --quick --routines --triggers --events --hex-blob --set-gtid-purged=OFF --add-drop-database --databases "$1"' \
    deploy08-mysqldump "$DATABASE_NAME" \
    | gzip -c | encrypt_stream "$mysql_artifact"
finalize_artifact "$mysql_artifact"

mysql_inventory="$RUN_DIRECTORY/mysql-inventory.tsv.gz.gpg"
if ! mysql_table_names=$(docker exec "$mysql_container" sh -c \
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot --batch --skip-column-names --database="$1" -e "SELECT TABLE_NAME FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE=0x42415345205441424C45 ORDER BY TABLE_NAME"' \
        deploy08-tables "$DATABASE_NAME"); then
    fail '无法读取 MySQL 表清单。'
fi
[ -n "$mysql_table_names" ] || fail 'MySQL 表清单为空，拒绝生成不完整 inventory。'
{
    printf 'table\trow_count\tchecksum\n'
    while IFS= read -r table_name; do
        case "$table_name" in ''|*[!A-Za-z0-9_]*) fail 'MySQL 返回了不安全的表名。' ;; esac
        if ! row_count=$(docker exec "$mysql_container" sh -c \
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot --batch --skip-column-names --database="$1" -e "$2"' \
                deploy08-mysql "$DATABASE_NAME" "SELECT COUNT(*) FROM \`$table_name\`;"); then
            fail "无法读取 MySQL 表行数：$table_name"
        fi
        case "$row_count" in ''|*[!0-9]*) fail "MySQL 表行数格式无效：$table_name" ;; esac
        if ! checksum_output=$(docker exec "$mysql_container" sh -c \
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot --batch --skip-column-names --database="$1" -e "$2"' \
                deploy08-mysql "$DATABASE_NAME" "CHECKSUM TABLE \`$table_name\`;"); then
            fail "无法读取 MySQL 表校验值：$table_name"
        fi
        checksum=$(printf '%s\n' "$checksum_output" | awk 'NF >= 2 { print $2; exit }')
        case "$checksum" in ''|*[!0-9]*) fail "MySQL 表校验值格式无效：$table_name" ;; esac
        printf '%s\t%s\t%s\n' "$table_name" "$row_count" "$checksum"
    done <<<"$mysql_table_names"
} | gzip -c | encrypt_stream "$mysql_inventory"
finalize_artifact "$mysql_inventory"

redis_inventory="$RUN_DIRECTORY/redis-inventory.tsv.gz.gpg"
if ! redis_key_names=$(redis_cli "$redis_container" --scan | sort); then
    fail '无法读取 Redis Key 清单。'
fi
{
    declare -A volatile_counts=()
    printf 'scope\tkey_base64_or_type\ttype\tcardinality\tdump_sha256\n'
    while IFS= read -r key_name; do
        [ -n "$key_name" ] || continue
        if ! key_type=$(redis_cli "$redis_container" TYPE "$key_name"); then
            fail '无法读取 Redis Key 类型。'
        fi
        key_type=$(printf '%s' "$key_type" | tr -d '\r\n')
        if ! pttl=$(redis_cli "$redis_container" PTTL "$key_name"); then
            fail '无法读取 Redis Key TTL。'
        fi
        pttl=$(printf '%s' "$pttl" | tr -d '\r\n')
        case "$pttl" in
            -2) continue ;;
            -1) ;;
            ''|*[!0-9]*) fail 'Redis PTTL 返回了无效值。' ;;
        esac
        case "$key_type" in
            string) cardinality_command=STRLEN ;;
            hash) cardinality_command=HLEN ;;
            set) cardinality_command=SCARD ;;
            zset) cardinality_command=ZCARD ;;
            list) cardinality_command=LLEN ;;
            stream) cardinality_command=XLEN ;;
            *) fail "Redis Key 类型不受 inventory 支持：$key_type" ;;
        esac
        if ! cardinality=$(redis_cli "$redis_container" "$cardinality_command" "$key_name"); then
            fail '无法读取 Redis Key 基数。'
        fi
        cardinality=$(printf '%s' "$cardinality" | tr -d '\r\n')
        case "$cardinality" in ''|*[!0-9]*) fail 'Redis Key 基数格式无效。' ;; esac
        if [ "$pttl" = '-1' ]; then
            key_base64=$(printf '%s' "$key_name" | base64 | tr -d '\n')
            if ! dump_sha=$(redis_cli "$redis_container" --raw DUMP "$key_name" \
                    | sha256sum | awk '{ print $1 }'); then
                fail '无法生成永久 Redis Key 的内容摘要。'
            fi
            case "$dump_sha" in ''|*[!0-9a-f]*) fail 'Redis DUMP 摘要格式无效。' ;; esac
            [ "${#dump_sha}" -eq 64 ] || fail 'Redis DUMP 摘要长度无效。'
            printf 'persistent\t%s\t%s\t%s\t%s\n' \
                "$key_base64" "$key_type" "$cardinality" "$dump_sha"
        else
            volatile_counts["$key_type"]=$(( ${volatile_counts["$key_type"]:-0} + 1 ))
        fi
    done <<<"$redis_key_names"
    for key_type in "${!volatile_counts[@]}"; do
        printf 'volatile-summary\t%s\t%s\t%s\t-\n' \
            "$key_type" "$key_type" "${volatile_counts[$key_type]}"
    done | sort
} | gzip -c | encrypt_stream "$redis_inventory"
finalize_artifact "$redis_inventory"

uploads_inventory="$RUN_DIRECTORY/uploads-inventory.tsv.gz.gpg"
volume_helper "$backend_image" "$uploads_volume" \
    'cd /volume; find . -type f -print0 | sort -z | xargs -0 -r sha256sum' \
    | gzip -c | encrypt_stream "$uploads_inventory"
finalize_artifact "$uploads_inventory"

compose stop --timeout 45 mysql redis >/dev/null
[ "$(docker inspect --format '{{.State.Status}}' "$mysql_container")" = 'exited' ] || fail 'MySQL 未完全停止。'
[ "$(docker inspect --format '{{.State.Status}}' "$redis_container")" = 'exited' ] || fail 'Redis 未完全停止。'

redis_artifact="$RUN_DIRECTORY/redis-data.tar.gz.gpg"
volume_helper "$backend_image" "$redis_volume" 'tar --numeric-owner -C /volume -czf - .' \
    | encrypt_stream "$redis_artifact"
finalize_artifact "$redis_artifact"

uploads_artifact="$RUN_DIRECTORY/uploads.tar.gz.gpg"
volume_helper "$backend_image" "$uploads_volume" 'tar --numeric-owner -C /volume -czf - .' \
    | encrypt_stream "$uploads_artifact"
finalize_artifact "$uploads_artifact"

restore_services || fail '备份已生成，但生产服务恢复失败。'
SERVICES_TOUCHED=0

manifest_partial="$RUN_DIRECTORY/manifest.json.partial"
manifest_path="$RUN_DIRECTORY/manifest.json"
manifest_signature_partial="$manifest_partial.sig"
PARTIAL_FILES+=("$manifest_partial")
PARTIAL_FILES+=("$manifest_signature_partial")
python3 - "$manifest_partial" "$ARTIFACT_INDEX" "$RUN_ID" "$cut_at" "$branch" "$commit" \
    "$backend_image" "$frontend_image" "$recipient_fingerprint" <<'PY'
import json
import sys
from datetime import datetime, timezone

output, artifact_index, run_id, cut_at, branch, commit, backend_image, frontend_image, fingerprint = sys.argv[1:]
artifacts = []
with open(artifact_index, encoding="utf-8") as source:
    for line in source:
        name, sha256, size = line.rstrip("\n").split("\t")
        artifacts.append({"name": name, "sha256": sha256, "bytes": int(size)})
manifest = {
    "schemaVersion": 1,
    "runId": run_id,
    "status": "PASSED",
    "cutAt": cut_at,
    "finishedAt": datetime.now(timezone.utc).isoformat(),
    "project": "campus-resource-platform",
    "branch": branch,
    "commit": commit,
    "images": {"backend": backend_image, "frontend": frontend_image},
    "gpgRecipientFingerprint": fingerprint,
    "artifacts": artifacts,
}
with open(output, "x", encoding="utf-8", newline="\n") as target:
    json.dump(manifest, target, ensure_ascii=False, indent=2)
    target.write("\n")
PY
chmod 0600 "$manifest_partial"
ssh-keygen -Y sign -f "$SIGNING_KEY" -n 'campusshare-deploy08' "$manifest_partial" >/dev/null
[ -s "$manifest_signature_partial" ] || fail 'manifest detached signature 未生成。'
chmod 0600 "$manifest_signature_partial"
# 先发布签名、最后发布 manifest；异常时绝不遗留一个声称 PASSED 的未签名 manifest。
mv -- "$manifest_signature_partial" "$manifest_path.sig"
mv -- "$manifest_partial" "$manifest_path"
(cd "$RUN_DIRECTORY" && sha256sum 'manifest.json') >"$RUN_DIRECTORY/manifest.json.sha256.partial"
chmod 0600 "$RUN_DIRECTORY/manifest.json.sha256.partial"
mv -- "$RUN_DIRECTORY/manifest.json.sha256.partial" "$RUN_DIRECTORY/manifest.json.sha256"
rm -f -- "$ARTIFACT_INDEX"
BACKUP_COMPLETED=1

printf '[DEPLOY-08] 联合备份完成：%s\n' "$RUN_DIRECTORY"
printf '%s\n' '[DEPLOY-08] 下一步必须把整个目录复制到独立恢复机，核对 manifest 摘要后执行恢复；未完成异地副本与恢复演练前不得标记 DEPLOY-08 完成。'
