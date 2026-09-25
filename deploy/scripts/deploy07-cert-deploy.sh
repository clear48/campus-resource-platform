#!/bin/sh

# 证书部署全过程禁止命令跟踪，避免未来参数扩展时把敏感路径或内容带入日志。
set +x
set -eu

EXPECTED_LINEAGE_NAME='campusshare.online'
ROOT_DOMAIN='campusshare.online'
WWW_DOMAIN='www.campusshare.online'
PROJECT_NAME='campus-resource-platform'
TLS_VOLUME='campus-resource-platform_tls_runtime'
VOLUME_PROJECT_LABEL='com.campusshare.project'
VOLUME_PURPOSE_LABEL='com.campusshare.purpose'
VOLUME_PURPOSE='tls-runtime'
LOCK_FILE='/run/lock/campusshare-deploy07-cert.lock'
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_HTTPS_CONFIG="$SCRIPT_DIR/../nginx/https.conf"
# 安装成 Certbot hook 后脚本已离开仓库，默认回退到服务器固定发布目录；特殊目录可显式覆盖。
if [ -f "$REPOSITORY_HTTPS_CONFIG" ]; then
    DEFAULT_HTTPS_CONFIG=$REPOSITORY_HTTPS_CONFIG
else
    DEFAULT_HTTPS_CONFIG='/srv/campusshare/app/deploy/nginx/https.conf'
fi
HTTPS_CONFIG="${CAMPUSSHARE_HTTPS_CONFIG:-$DEFAULT_HTTPS_CONFIG}"
LINEAGE=''
HOOK_MODE=0
TEMPORARY_DIRECTORY=''
TEMP_ROOT="${TMPDIR:-/tmp}"

usage() {
    printf '%s\n' '用法：deploy07-cert-deploy.sh [--lineage PATH] [--project-name NAME] [--tls-volume NAME]'
    printf '%s\n' 'Certbot deploy hook 也可通过 RENEWED_LINEAGE 自动传入证书目录。'
}

fail() {
    printf 'DEPLOY-07 证书部署失败：%s\n' "$1" >&2
    exit 1
}

case "$TEMP_ROOT" in
    /*) ;;
    *) fail 'TMPDIR 必须是绝对目录。' ;;
esac
[ -d "$TEMP_ROOT" ] || fail 'TMPDIR 不存在或不是目录。'
[ -w "$TEMP_ROOT" ] || fail 'TMPDIR 不可写。'
# 规范化临时目录，避免多余斜杠或符号链接让清理边界比较失真；根目录绝不允许作为临时根。
TEMP_ROOT=$(CDPATH= cd -- "$TEMP_ROOT" && pwd -P) || fail '无法规范化 TMPDIR。'
[ "$TEMP_ROOT" != '/' ] || fail 'TMPDIR 不得指向根目录。'

cleanup() {
    cleanup_failed=0
    set +e
    if [ -n "$TEMPORARY_DIRECTORY" ] && [ -d "$TEMPORARY_DIRECTORY" ]; then
        temporary_parent=$(CDPATH= cd -- "$(dirname -- "$TEMPORARY_DIRECTORY")" && pwd -P)
        temporary_name=$(basename -- "$TEMPORARY_DIRECTORY")
        # 同时核对记录的父目录和固定前缀，避免异常变量导致扩大删除范围。
        case "$temporary_name" in
            campusshare-deploy07.*)
                if [ "$temporary_parent" = "$TEMP_ROOT" ]; then
                    rm -rf -- "$TEMPORARY_DIRECTORY" || cleanup_failed=1
                else
                    printf '%s\n' '拒绝清理父目录不匹配的证书临时目录。' >&2
                    cleanup_failed=1
                fi
                ;;
            *)
                printf '%s\n' '拒绝清理名称前缀不匹配的证书临时目录。' >&2
                cleanup_failed=1
                ;;
        esac
    fi
    set -e
    [ "$cleanup_failed" -eq 0 ]
}

on_exit() {
    exit_status=$?
    trap - EXIT HUP INT TERM
    if ! cleanup && [ "$exit_status" -eq 0 ]; then
        exit_status=1
    fi
    exit "$exit_status"
}

on_signal() {
    signal_status=$1
    trap - EXIT HUP INT TERM
    cleanup || true
    exit "$signal_status"
}

trap on_exit EXIT
trap 'on_signal 129' HUP
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

while [ "$#" -gt 0 ]; do
    case "$1" in
        --lineage)
            [ "$#" -ge 2 ] || fail '--lineage 缺少目录参数。'
            LINEAGE=$2
            shift 2
            ;;
        --project-name)
            [ "$#" -ge 2 ] || fail '--project-name 缺少参数。'
            PROJECT_NAME=$2
            shift 2
            ;;
        --tls-volume)
            [ "$#" -ge 2 ] || fail '--tls-volume 缺少参数。'
            TLS_VOLUME=$2
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            fail "未知参数：$1"
            ;;
    esac
done

# 参数最终会进入 Docker label/filter/name，只允许 Docker Compose 与卷名的安全字符集合。
case "$PROJECT_NAME" in
    ''|[!a-z0-9]*|*[!a-z0-9_-]*) fail '--project-name 只允许小写字母、数字、下划线和连字符，且必须以字母或数字开头。' ;;
esac
case "$TLS_VOLUME" in
    ''|[!A-Za-z0-9]*|*[!A-Za-z0-9_.-]*) fail '--tls-volume 包含不安全字符。' ;;
esac

if [ -z "$LINEAGE" ] && [ -n "${RENEWED_LINEAGE:-}" ]; then
    LINEAGE=$RENEWED_LINEAGE
    HOOK_MODE=1
fi
[ -n "$LINEAGE" ] || fail '必须通过 --lineage 或 RENEWED_LINEAGE 提供证书目录。'

# Certbot 会对所有续期证书调用 deploy hook；非本站 lineage 必须安全跳过。
LINEAGE_NAME=$(basename -- "$LINEAGE")
if [ "$LINEAGE_NAME" != "$EXPECTED_LINEAGE_NAME" ]; then
    if [ "$HOOK_MODE" -eq 1 ]; then
        printf 'DEPLOY-07：跳过非本站证书 lineage：%s\n' "$LINEAGE_NAME"
        exit 0
    fi
    fail "只允许部署 basename 为 $EXPECTED_LINEAGE_NAME 的 lineage。"
fi

[ "$(id -u)" -eq 0 ] || fail '必须以 root 运行，避免 Docker 与私钥权限边界失效。'
command -v docker >/dev/null 2>&1 || fail '未找到 docker。'
command -v openssl >/dev/null 2>&1 || fail '未找到 openssl。'
command -v flock >/dev/null 2>&1 || fail '未找到 flock。'
[ -f "$HTTPS_CONFIG" ] || fail '找不到 deploy/nginx/https.conf。'

mkdir -p "$(dirname -- "$LOCK_FILE")"
exec 9>"$LOCK_FILE"
flock -n 9 || fail '已有证书部署进程正在运行。'

CERTIFICATE_SOURCE="$LINEAGE/fullchain.pem"
PRIVATE_KEY_SOURCE="$LINEAGE/privkey.pem"
[ -f "$CERTIFICATE_SOURCE" ] || fail 'lineage 缺少 fullchain.pem。'
[ -f "$PRIVATE_KEY_SOURCE" ] || fail 'lineage 缺少 privkey.pem。'

umask 077
TEMPORARY_DIRECTORY=$(mktemp -d "$TEMP_ROOT/campusshare-deploy07.XXXXXX")
TEMPORARY_PARENT=$(CDPATH= cd -- "$(dirname -- "$TEMPORARY_DIRECTORY")" && pwd -P) \
    || fail '无法确认临时目录父路径。'
[ "$TEMPORARY_PARENT" = "$TEMP_ROOT" ] || fail '临时目录不在已确认的 TMPDIR 内。'
# install 会解引用 Certbot live 下的符号链接，短生命周期副本始终保持 root-only。
install -m 0600 "$CERTIFICATE_SOURCE" "$TEMPORARY_DIRECTORY/fullchain.pem"
install -m 0600 "$PRIVATE_KEY_SOURCE" "$TEMPORARY_DIRECTORY/privkey.pem"

openssl x509 -in "$TEMPORARY_DIRECTORY/fullchain.pem" -noout -checkend 86400 >/dev/null 2>&1 \
    || fail '证书有效期不足 1 天。'
SAN_TEXT=$(openssl x509 -in "$TEMPORARY_DIRECTORY/fullchain.pem" -noout -ext subjectAltName 2>/dev/null) \
    || fail '无法读取证书 SAN。'
printf '%s\n' "$SAN_TEXT" | tr ',' '\n' | sed 's/^[[:space:]]*//' | grep -Fx "DNS:$ROOT_DOMAIN" >/dev/null \
    || fail "证书 SAN 不包含 $ROOT_DOMAIN。"
printf '%s\n' "$SAN_TEXT" | tr ',' '\n' | sed 's/^[[:space:]]*//' | grep -Fx "DNS:$WWW_DOMAIN" >/dev/null \
    || fail "证书 SAN 不包含 $WWW_DOMAIN。"

openssl x509 -in "$TEMPORARY_DIRECTORY/fullchain.pem" -pubkey -noout 2>/dev/null \
    | openssl pkey -pubin -outform DER 2>/dev/null >"$TEMPORARY_DIRECTORY/cert-public.der" \
    || fail '无法提取证书公钥。'
openssl pkey -in "$TEMPORARY_DIRECTORY/privkey.pem" -pubout -outform DER 2>/dev/null \
    >"$TEMPORARY_DIRECTORY/key-public.der" || fail '无法提取私钥公钥。'
cmp -s "$TEMPORARY_DIRECTORY/cert-public.der" "$TEMPORARY_DIRECTORY/key-public.der" \
    || fail '证书与私钥不匹配。'

FINGERPRINT=$(openssl x509 -in "$TEMPORARY_DIRECTORY/fullchain.pem" -noout -fingerprint -sha256 \
    | sed 's/^.*=//; s/://g' | tr 'A-F' 'a-f')
case "$FINGERPRINT" in
    ''|*[!0-9a-f]*) fail '无法生成安全的证书指纹目录名。' ;;
esac

# 外部卷必须由本脚本创建且带项目专属标签，拒绝复用无法证明归属的同名卷。
if ! docker volume inspect "$TLS_VOLUME" >/dev/null 2>&1; then
    docker volume create \
        --label "$VOLUME_PROJECT_LABEL=$PROJECT_NAME" \
        --label "$VOLUME_PURPOSE_LABEL=$VOLUME_PURPOSE" \
        "$TLS_VOLUME" >/dev/null
fi
ACTUAL_PROJECT_LABEL=$(docker volume inspect --format "{{ index .Labels \"$VOLUME_PROJECT_LABEL\" }}" "$TLS_VOLUME")
ACTUAL_PURPOSE_LABEL=$(docker volume inspect --format "{{ index .Labels \"$VOLUME_PURPOSE_LABEL\" }}" "$TLS_VOLUME")
[ "$ACTUAL_PROJECT_LABEL" = "$PROJECT_NAME" ] \
    || fail 'TLS 卷项目标签不匹配，拒绝写入。'
[ "$ACTUAL_PURPOSE_LABEL" = "$VOLUME_PURPOSE" ] \
    || fail 'TLS 卷用途标签不匹配，拒绝写入。'

FRONTEND_CONTAINERS=$(docker ps -q \
    --filter "label=com.docker.compose.project=$PROJECT_NAME" \
    --filter 'label=com.docker.compose.service=frontend')
FRONTEND_COUNT=$(printf '%s\n' "$FRONTEND_CONTAINERS" | awk 'NF { count++ } END { print count+0 }')
[ "$FRONTEND_COUNT" -eq 1 ] \
    || fail "必须且只能找到一个运行中的 $PROJECT_NAME/frontend 容器。"
FRONTEND_CONTAINER=$(printf '%s\n' "$FRONTEND_CONTAINERS" | awk 'NF { print; exit }')
# 使用运行容器记录的 immutable image ID，避免 helper 被可变 tag 悄然替换。
FRONTEND_IMAGE_ID=$(docker inspect --format '{{.Image}}' "$FRONTEND_CONTAINER")
case "$FRONTEND_IMAGE_ID" in
    sha256:*) ;;
    *) fail '无法取得 frontend 的 immutable image ID。' ;;
esac

# 先在卷内创建不可变 release；私钥和目录仅允许 Nginx UID/GID 101 读取/穿越。
docker run --rm --user 0:0 --entrypoint /bin/sh \
    -v "$TLS_VOLUME:/tls" \
    -v "$TEMPORARY_DIRECTORY:/incoming:ro" \
    "$FRONTEND_IMAGE_ID" -eu -c '
        fingerprint=$1
        release="/tls/releases/$fingerprint"
        staging="/tls/releases/.incoming-$fingerprint"
        mkdir -p /tls/releases
        chown 101:101 /tls/releases
        chmod 0500 /tls/releases
        rm -rf -- "$staging"
        mkdir "$staging"
        cp /incoming/fullchain.pem "$staging/fullchain.pem"
        cp /incoming/privkey.pem "$staging/privkey.pem"
        chown -R 101:101 "$staging"
        chmod 0500 "$staging"
        chmod 0444 "$staging/fullchain.pem"
        chmod 0400 "$staging/privkey.pem"
        if [ -e "$release" ]; then
            cmp -s "$staging/fullchain.pem" "$release/fullchain.pem"
            cmp -s "$staging/privkey.pem" "$release/privkey.pem"
            rm -rf -- "$staging"
        else
            mv "$staging" "$release"
        fi
    ' -- "$FINGERPRINT"

OLD_TARGET=$(docker run --rm --user 0:0 --entrypoint /bin/sh \
    -v "$TLS_VOLUME:/tls" "$FRONTEND_IMAGE_ID" -c 'readlink /tls/current 2>/dev/null || true')

switch_current() {
    target=$1
    docker run --rm --user 0:0 --entrypoint /bin/sh \
        -v "$TLS_VOLUME:/tls" "$FRONTEND_IMAGE_ID" -eu -c '
            target=$1
            [ ! -e /tls/current ] || [ -L /tls/current ]
            [ -d "/tls/$target" ]
            rm -f /tls/current.next
            ln -s "$target" /tls/current.next
            # -T 强制把 current 当作普通目标替换；否则 BusyBox 会跟随目录 symlink，误移入旧 release。
            mv -fT /tls/current.next /tls/current
            [ "$(readlink /tls/current)" = "$target" ]
        ' -- "$target"
}

restore_current() {
    if [ -n "$OLD_TARGET" ]; then
        switch_current "$OLD_TARGET"
    else
        docker run --rm --user 0:0 --entrypoint /bin/sh \
            -v "$TLS_VOLUME:/tls" "$FRONTEND_IMAGE_ID" -eu -c '
                [ ! -e /tls/current ] || [ -L /tls/current ]
                rm -f /tls/current /tls/current.next
            '
    fi
}

NEW_TARGET="releases/$FINGERPRINT"
switch_current "$NEW_TARGET"

# 候选检查共享运行容器网络，因此 backend 名称解析与正式 Compose 拓扑一致。
if ! docker run --rm --user 101:101 --network "container:$FRONTEND_CONTAINER" \
    --entrypoint nginx \
    -v "$TLS_VOLUME:/etc/nginx/tls:ro" \
    -v "$HTTPS_CONFIG:/etc/nginx/conf.d/default.conf:ro" \
    "$FRONTEND_IMAGE_ID" -t; then
    restore_current
    fail '候选证书的 nginx -t 失败，已恢复旧 current。'
fi

TLS_MOUNT=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/etc/nginx/tls"}}{{.Type}}:{{.Name}}{{end}}{{end}}' "$FRONTEND_CONTAINER")
if [ "$TLS_MOUNT" = "volume:$TLS_VOLUME" ]; then
    if ! docker exec "$FRONTEND_CONTAINER" nginx -t; then
        restore_current
        docker exec "$FRONTEND_CONTAINER" nginx -t >/dev/null 2>&1 || true
        fail '运行容器 nginx -t 失败，已恢复旧 current。'
    fi
    if ! docker exec "$FRONTEND_CONTAINER" nginx -s reload; then
        restore_current
        # reload 失败后尽力重新加载旧证书；原失败仍作为最终结果返回。
        docker exec "$FRONTEND_CONTAINER" nginx -t >/dev/null 2>&1 \
            && docker exec "$FRONTEND_CONTAINER" nginx -s reload >/dev/null 2>&1 || true
        fail '运行容器 reload 失败，已恢复并尝试重新加载旧证书。'
    fi
    printf '%s\n' 'DEPLOY-07：证书已原子切换，Nginx 配置检查与平滑重载成功。'
else
    printf '%s\n' 'DEPLOY-07：证书已写入 TLS 卷；当前为 HTTP 阶段，未重载 Nginx。'
fi

# 成功后只保留当前与上一 release；首次部署没有上一版本属于正常情况。
docker run --rm --user 0:0 --entrypoint /bin/sh \
    -v "$TLS_VOLUME:/tls" "$FRONTEND_IMAGE_ID" -eu -c '
        current=$1
        previous=$2
        for release in /tls/releases/*; do
            [ -d "$release" ] || continue
            relative="releases/${release##*/}"
            if [ "$relative" != "$current" ] && [ "$relative" != "$previous" ]; then
                rm -rf -- "$release"
            fi
        done
    ' -- "$NEW_TARGET" "$OLD_TARGET"
