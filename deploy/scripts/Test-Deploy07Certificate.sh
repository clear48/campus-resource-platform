#!/bin/sh

set +x
set -eu

fail() {
    printf 'DEPLOY-07 证书脚本测试失败：%s\n' "$1" >&2
    exit 1
}

[ "$(id -u)" -eq 0 ] || fail '该隔离测试必须以 root 运行。'
command -v docker >/dev/null 2>&1 || fail '未找到 docker。'
command -v openssl >/dev/null 2>&1 || fail '未找到 openssl。'
docker info >/dev/null 2>&1 || fail 'Docker Engine 不可用。'

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CERT_DEPLOY_SCRIPT="$SCRIPT_DIR/deploy07-cert-deploy.sh"
DEFAULT_NGINX_CONFIG="$SCRIPT_DIR/../nginx/default.conf"
[ -f "$CERT_DEPLOY_SCRIPT" ] || fail '找不到证书部署脚本。'
[ -f "$DEFAULT_NGINX_CONFIG" ] || fail '找不到基础 Nginx 配置。'

RUN_ID=$(printf '%s-%s' "$(date +%s)" "$$")
PROJECT_NAME="campus-deploy07-cert-$RUN_ID"
NETWORK_NAME="$PROJECT_NAME-network"
TLS_VOLUME="${PROJECT_NAME}_tls"
FRONTEND_NAME="$PROJECT_NAME-frontend"
BACKEND_NAME="$PROJECT_NAME-backend"
TEST_LABEL='com.campusshare.deploy07-cert-test'
FRONTEND_IMAGE="${FRONTEND_IMAGE:-campus-resource-platform-frontend:deploy-02}"
TEMP_ROOT="${TMPDIR:-/tmp}"
case "$TEMP_ROOT" in
    /*) ;;
    *) fail 'TMPDIR 必须是绝对目录。' ;;
esac
[ -d "$TEMP_ROOT" ] || fail 'TMPDIR 不存在或不是目录。'
[ -w "$TEMP_ROOT" ] || fail 'TMPDIR 不可写。'
TEMP_ROOT=$(CDPATH= cd -- "$TEMP_ROOT" && pwd -P) || fail '无法规范化 TMPDIR。'
[ "$TEMP_ROOT" != '/' ] || fail 'TMPDIR 不得指向根目录。'
TEMPORARY_DIRECTORY=$(mktemp -d "$TEMP_ROOT/campusshare-deploy07-cert.XXXXXX")
TEMPORARY_PARENT=$(CDPATH= cd -- "$(dirname -- "$TEMPORARY_DIRECTORY")" && pwd -P) \
    || fail '无法确认临时目录父路径。'
[ "$TEMPORARY_PARENT" = "$TEMP_ROOT" ] || fail '临时目录不在已确认的 TMPDIR 内。'
FRONTEND_CONTAINER=''
BACKEND_CONTAINER=''
NETWORK_CREATED=0

cleanup() {
    cleanup_failed=0
    set +e
    for container in "$FRONTEND_CONTAINER" "$BACKEND_CONTAINER"; do
        [ -n "$container" ] || continue
        actual_label=$(docker inspect --format "{{ index .Config.Labels \"$TEST_LABEL\" }}" "$container" 2>/dev/null)
        if [ "$actual_label" = "$RUN_ID" ]; then
            docker rm -f "$container" >/dev/null 2>&1 || cleanup_failed=1
        else
            printf '拒绝清理标签不匹配的测试容器：%s\n' "$container" >&2
            cleanup_failed=1
        fi
    done
    if docker volume inspect "$TLS_VOLUME" >/dev/null 2>&1; then
        project_label=$(docker volume inspect --format '{{ index .Labels "com.campusshare.project" }}' "$TLS_VOLUME" 2>/dev/null)
        purpose_label=$(docker volume inspect --format '{{ index .Labels "com.campusshare.purpose" }}' "$TLS_VOLUME" 2>/dev/null)
        if [ "$project_label" = "$PROJECT_NAME" ] && [ "$purpose_label" = 'tls-runtime' ]; then
            docker volume rm "$TLS_VOLUME" >/dev/null 2>&1 || cleanup_failed=1
        else
            printf '拒绝清理标签不匹配的测试 TLS 卷：%s\n' "$TLS_VOLUME" >&2
            cleanup_failed=1
        fi
    fi
    if [ "$NETWORK_CREATED" -eq 1 ]; then
        network_label=$(docker network inspect --format "{{ index .Labels \"$TEST_LABEL\" }}" "$NETWORK_NAME" 2>/dev/null)
        if [ "$network_label" = "$RUN_ID" ]; then
            docker network rm "$NETWORK_NAME" >/dev/null 2>&1 || cleanup_failed=1
        else
            printf '拒绝清理标签不匹配的测试网络：%s\n' "$NETWORK_NAME" >&2
            cleanup_failed=1
        fi
    fi
    temporary_parent=$(CDPATH= cd -- "$(dirname -- "$TEMPORARY_DIRECTORY")" && pwd -P)
    temporary_name=$(basename -- "$TEMPORARY_DIRECTORY")
    case "$temporary_name" in
        campusshare-deploy07-cert.*)
            if [ "$temporary_parent" = "$TEMP_ROOT" ]; then
                rm -rf -- "$TEMPORARY_DIRECTORY" || cleanup_failed=1
            else
                printf '拒绝清理父目录不匹配的临时目录：%s\n' "$TEMPORARY_DIRECTORY" >&2
                cleanup_failed=1
            fi
            ;;
        *) printf '拒绝清理名称前缀不匹配的临时目录：%s\n' "$TEMPORARY_DIRECTORY" >&2; cleanup_failed=1 ;;
    esac
    set -e
    [ "$cleanup_failed" -eq 0 ]
}
trap 'cleanup || true' EXIT HUP INT TERM

generate_lineage() {
    parent=$1
    common_name=$2
    subject_alt_name=$3
    lineage="$parent/campusshare.online"
    mkdir -p "$lineage"
    openssl req -x509 -nodes -newkey rsa:2048 -days 2 \
        -keyout "$lineage/privkey.pem" \
        -out "$lineage/fullchain.pem" \
        -subj "/CN=$common_name" \
        -addext "subjectAltName=$subject_alt_name" >/dev/null 2>&1
    printf '%s\n' "$lineage"
}

volume_command() {
    docker run --rm --user 0:0 --entrypoint /bin/sh \
        -v "$TLS_VOLUME:/tls" "$FRONTEND_IMAGE" -eu -c "$1"
}

current_target() {
    volume_command 'readlink /tls/current'
}

release_count() {
    volume_command 'find /tls/releases -mindepth 1 -maxdepth 1 -type d ! -name ".incoming-*" | wc -l' \
        | tr -d '[:space:]'
}

run_deploy() {
    lineage=$1
    shift
    sh "$CERT_DEPLOY_SCRIPT" \
        --lineage "$lineage" \
        --project-name "$PROJECT_NAME" \
        --tls-volume "$TLS_VOLUME" "$@"
}

docker image inspect "$FRONTEND_IMAGE" >/dev/null 2>&1 \
    || fail "缺少已构建的 frontend 镜像：$FRONTEND_IMAGE"

cat >"$TEMPORARY_DIRECTORY/backend.conf" <<'EOF'
server {
    listen 8080;
    server_name _;
    location / { return 200 "mock backend\n"; }
}
EOF
cat >"$TEMPORARY_DIRECTORY/bad-https.conf" <<'EOF'
this is intentionally invalid nginx syntax;
EOF
chmod 0644 "$TEMPORARY_DIRECTORY/backend.conf" "$TEMPORARY_DIRECTORY/bad-https.conf"

docker network create --label "$TEST_LABEL=$RUN_ID" "$NETWORK_NAME" >/dev/null
NETWORK_CREATED=1
BACKEND_CONTAINER=$(docker run --detach \
    --name "$BACKEND_NAME" \
    --label "$TEST_LABEL=$RUN_ID" \
    --network "$NETWORK_NAME" \
    --network-alias backend \
    -v "$TEMPORARY_DIRECTORY/backend.conf:/etc/nginx/conf.d/default.conf:ro" \
    "$FRONTEND_IMAGE")
FRONTEND_CONTAINER=$(docker run --detach \
    --name "$FRONTEND_NAME" \
    --label "$TEST_LABEL=$RUN_ID" \
    --label "com.docker.compose.project=$PROJECT_NAME" \
    --label 'com.docker.compose.service=frontend' \
    --network "$NETWORK_NAME" \
    -v "$DEFAULT_NGINX_CONFIG:/etc/nginx/conf.d/default.conf:ro" \
    "$FRONTEND_IMAGE")

LINEAGE_ONE=$(generate_lineage "$TEMPORARY_DIRECTORY/valid-one" 'campusshare.online' \
    'DNS:campusshare.online,DNS:www.campusshare.online')
LINEAGE_TWO=$(generate_lineage "$TEMPORARY_DIRECTORY/valid-two" 'campusshare.online' \
    'DNS:campusshare.online,DNS:www.campusshare.online')
LINEAGE_THREE=$(generate_lineage "$TEMPORARY_DIRECTORY/valid-three" 'campusshare.online' \
    'DNS:campusshare.online,DNS:www.campusshare.online')
LINEAGE_WRONG_SAN=$(generate_lineage "$TEMPORARY_DIRECTORY/wrong-san" 'campusshare.online' \
    'DNS:campusshare.online')

run_deploy "$LINEAGE_ONE"
FIRST_TARGET=$(current_target)
[ -n "$FIRST_TARGET" ] || fail '首次部署未创建 current。'
[ "$(release_count)" -eq 1 ] || fail '首次部署后的 release 数量不是 1。'
PRIVATE_KEY_STAT=$(volume_command 'target=$(readlink /tls/current); stat -c "%a:%u:%g" "/tls/$target/privkey.pem"')
FULLCHAIN_STAT=$(volume_command 'target=$(readlink /tls/current); stat -c "%a:%u:%g" "/tls/$target/fullchain.pem"')
[ "$PRIVATE_KEY_STAT" = '400:101:101' ] || fail "私钥权限或所有者错误：$PRIVATE_KEY_STAT"
[ "$FULLCHAIN_STAT" = '444:101:101' ] || fail "证书权限或所有者错误：$FULLCHAIN_STAT"

run_deploy "$LINEAGE_ONE"
[ "$(current_target)" = "$FIRST_TARGET" ] || fail '相同证书幂等部署改变了 current。'
[ "$(release_count)" -eq 1 ] || fail '相同证书幂等部署创建了多余 release。'

run_deploy "$LINEAGE_TWO"
SECOND_TARGET=$(current_target)
[ "$SECOND_TARGET" != "$FIRST_TARGET" ] || fail '第二张证书没有切换 current。'
[ "$(release_count)" -eq 2 ] || fail '第二张证书部署后未同时保留当前和上一 release。'
volume_command "test -d '/tls/$FIRST_TARGET' && test -d '/tls/$SECOND_TARGET' && test ! -L '/tls/$FIRST_TARGET/current.next'"

if run_deploy "$LINEAGE_WRONG_SAN" >"$TEMPORARY_DIRECTORY/wrong-san.log" 2>&1; then
    fail '缺少 www SAN 的证书未被拒绝。'
fi
[ "$(current_target)" = "$SECOND_TARGET" ] || fail '错误 SAN 导致 current 发生变化。'
[ "$(release_count)" -eq 2 ] || fail '错误 SAN 导致 release 集合发生变化。'

if CAMPUSSHARE_HTTPS_CONFIG="$TEMPORARY_DIRECTORY/bad-https.conf" \
        run_deploy "$LINEAGE_THREE" >"$TEMPORARY_DIRECTORY/bad-config.log" 2>&1; then
    fail '候选 nginx -t 使用坏配置时未失败。'
fi
[ "$(current_target)" = "$SECOND_TARGET" ] || fail '候选 nginx -t 失败后没有恢复旧 current。'
volume_command "test ! -e /tls/current.next && test ! -L /tls/current.next && test ! -L '/tls/$SECOND_TARGET/current.next'"

trap - EXIT HUP INT TERM
cleanup || fail '隔离资源无法确认清理。'
printf '%s\n' 'DEPLOY-07 证书部署脚本隔离测试全部通过。'
