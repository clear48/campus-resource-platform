#!/usr/bin/env bash

# 独立恢复机预检只读取备份介质、密钥环和 Docker 元数据，不创建容器、网络或卷。
set +x
set -Eeuo pipefail
umask 077

PRODUCTION_PROJECT='campus-resource-platform'
SIGNING_IDENTITY='campusshare-deploy08-backup'
SIGNING_NAMESPACE='campusshare-deploy08'
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)
BACKUP_DIR=''
ALLOWED_SIGNERS=''
ENV_FILE=''
GPG_HOMEDIR=''
PREVIOUS_BACKEND_IMAGE=''
PREVIOUS_FRONTEND_IMAGE=''
PREVIOUS_COMMIT=''

usage() {
    cat <<'EOF'
用法：sudo ./deploy08-restore-preflight.sh [必填参数]

在独立 Docker 宿主机上验证 DEPLOY-08 备份介质、签名信任链、解密能力、
恢复环境 Secret 和当前/上一版镜像是否齐备。脚本只做预检，不创建或删除资源。

必填参数：
  --backup-dir ABSOLUTE_PATH             完整 run-id 备份目录
  --allowed-signers ABSOLUTE_PATH         独立固定的 OpenSSH allowed_signers 文件
  --env-file ABSOLUTE_PATH                恢复专用 root-only 环境变量文件
  --gpg-homedir ABSOLUTE_PATH             包含恢复私钥的 root-only GPG home
  --previous-backend-image sha256:...     上一版后端 immutable image ID
  --previous-frontend-image sha256:...    上一版前端 immutable image ID
  --previous-commit 40_HEX                  上一版镜像的源码 revision
  --help                                  显示帮助

安全要求：该 Docker daemon 不得存在生产 Compose 项目的容器、卷或网络。
EOF
}

fail() {
    printf 'DEPLOY-08 独立恢复预检失败：%s\n' "$1" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "缺少必需命令：$1"
}

require_absolute() {
    local value=$1
    local option=$2
    case "$value" in /*) ;; *) fail "$option 必须是绝对路径。" ;; esac
}

validate_image_id() {
    local image=$1
    local label=$2
    local digest
    case "$image" in sha256:*) digest=${image#sha256:} ;; *) fail "$label 必须使用 sha256 image ID。" ;; esac
    [ "${#digest}" -eq 64 ] || fail "$label image ID 长度无效。"
    case "$digest" in *[!0-9a-f]*) fail "$label image ID 包含非十六进制字符。" ;; esac
}

require_root_private_directory() {
    local path=$1
    local label=$2
    local mode
    [ ! -L "$path" ] || fail "$label 不得是符号链接。"
    [ -d "$path" ] || fail "$label 不是目录。"
    [ "$(stat -c '%u:%g' "$path")" = '0:0' ] || fail "$label 必须属于 root:root。"
    mode=$(stat -c '%a' "$path")
    [ $((0$mode & 0077)) -eq 0 ] || fail "$label 不得授予 group/other 权限。"
}

require_root_private_file() {
    local path=$1
    local label=$2
    local mode
    [ ! -L "$path" ] || fail "$label 不得是符号链接。"
    [ -f "$path" ] || fail "$label 不是普通文件。"
    [ "$(stat -c '%u:%g' "$path")" = '0:0' ] || fail "$label 必须属于 root:root。"
    mode=$(stat -c '%a' "$path")
    [ $((0$mode & 0077)) -eq 0 ] || fail "$label 不得授予 group/other 权限。"
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --backup-dir)
            [ "$#" -ge 2 ] || fail '--backup-dir 缺少参数。'
            BACKUP_DIR=$2
            shift 2
            ;;
        --allowed-signers)
            [ "$#" -ge 2 ] || fail '--allowed-signers 缺少参数。'
            ALLOWED_SIGNERS=$2
            shift 2
            ;;
        --env-file)
            [ "$#" -ge 2 ] || fail '--env-file 缺少参数。'
            ENV_FILE=$2
            shift 2
            ;;
        --gpg-homedir)
            [ "$#" -ge 2 ] || fail '--gpg-homedir 缺少参数。'
            GPG_HOMEDIR=$2
            shift 2
            ;;
        --previous-backend-image)
            [ "$#" -ge 2 ] || fail '--previous-backend-image 缺少参数。'
            PREVIOUS_BACKEND_IMAGE=$2
            shift 2
            ;;
        --previous-frontend-image)
            [ "$#" -ge 2 ] || fail '--previous-frontend-image 缺少参数。'
            PREVIOUS_FRONTEND_IMAGE=$2
            shift 2
            ;;
        --previous-commit)
            [ "$#" -ge 2 ] || fail '--previous-commit 缺少参数。'
            PREVIOUS_COMMIT=$2
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *) fail "未知参数：$1" ;;
    esac
done

[ "$(id -u)" -eq 0 ] || fail '必须通过 sudo 以 root 身份运行。'
for required_value in BACKUP_DIR ALLOWED_SIGNERS ENV_FILE GPG_HOMEDIR \
    PREVIOUS_BACKEND_IMAGE PREVIOUS_FRONTEND_IMAGE PREVIOUS_COMMIT; do
    [ -n "${!required_value}" ] || fail "缺少必填参数：$required_value"
done
require_absolute "$BACKUP_DIR" '--backup-dir'
require_absolute "$ALLOWED_SIGNERS" '--allowed-signers'
require_absolute "$ENV_FILE" '--env-file'
require_absolute "$GPG_HOMEDIR" '--gpg-homedir'

for command_name in realpath stat find wc sha256sum ssh-keygen gpg python3 docker awk; do
    require_command "$command_name"
done

backup_dir_input=$BACKUP_DIR
allowed_signers_input=$ALLOWED_SIGNERS
env_file_input=$ENV_FILE
gpg_homedir_input=$GPG_HOMEDIR
BACKUP_DIR=$(realpath -e -- "$backup_dir_input") || fail '无法解析备份目录。'
ALLOWED_SIGNERS=$(realpath -e -- "$allowed_signers_input") || fail '无法解析 allowed_signers。'
ENV_FILE=$(realpath -e -- "$env_file_input") || fail '无法解析恢复环境文件。'
GPG_HOMEDIR=$(realpath -e -- "$gpg_homedir_input") || fail '无法解析 GPG home。'
[ "$backup_dir_input" = "$BACKUP_DIR" ] || fail '备份目录必须使用无符号链接的规范绝对路径。'
[ "$allowed_signers_input" = "$ALLOWED_SIGNERS" ] || fail 'allowed_signers 必须使用无符号链接的规范绝对路径。'
[ "$env_file_input" = "$ENV_FILE" ] || fail '恢复环境文件必须使用无符号链接的规范绝对路径。'
[ "$gpg_homedir_input" = "$GPG_HOMEDIR" ] || fail 'GPG home 必须使用无符号链接的规范绝对路径。'
require_root_private_directory "$BACKUP_DIR" '备份目录'
require_root_private_directory "$GPG_HOMEDIR" 'GPG home'
require_root_private_file "$ALLOWED_SIGNERS" 'allowed_signers'
require_root_private_file "$ENV_FILE" '恢复环境文件'

case "$BACKUP_DIR/" in "$REPOSITORY_ROOT/"*) fail '备份目录不得位于 Git 仓库内。' ;; esac
case "$GPG_HOMEDIR/" in "$REPOSITORY_ROOT/"*|"$BACKUP_DIR/"*) fail 'GPG home 不得位于仓库或备份目录内。' ;; esac
case "$ALLOWED_SIGNERS" in "$REPOSITORY_ROOT/"*|"$BACKUP_DIR/"*) fail 'allowed_signers 不得位于仓库或备份目录内。' ;; esac
case "$ENV_FILE" in "$REPOSITORY_ROOT/"*|"$BACKUP_DIR/"*) fail '恢复环境文件不得位于仓库或备份目录内。' ;; esac

expected_files=(
    manifest.json
    manifest.json.sig
    manifest.json.sha256
    mysql.sql.gz.gpg
    mysql-inventory.tsv.gz.gpg
    redis-inventory.tsv.gz.gpg
    uploads-inventory.tsv.gz.gpg
    redis-data.tar.gz.gpg
    uploads.tar.gz.gpg
)
entry_count=$(find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -printf '.' | wc -c)
[ "$entry_count" -eq "${#expected_files[@]}" ] || fail '备份目录文件数量与协议不一致。'
for file_name in "${expected_files[@]}"; do
    file_path="$BACKUP_DIR/$file_name"
    [ ! -L "$file_path" ] && [ -f "$file_path" ] || fail "备份项不是普通文件：$file_name"
    [ "$(stat -c '%u:%g' "$file_path")" = '0:0' ] || fail "备份项必须属于 root:root：$file_name"
    [ "$(stat -c '%h' "$file_path")" -eq 1 ] || fail "备份项不得是硬链接：$file_name"
done

manifest_checksum=$(<"$BACKUP_DIR/manifest.json.sha256")
manifest_hash=${manifest_checksum%%  *}
[ "${#manifest_hash}" -eq 64 ] || fail 'manifest.json SHA-256 长度无效。'
case "$manifest_hash" in *[!0-9a-f]*) fail 'manifest.json SHA-256 格式无效。' ;; esac
[ "$manifest_checksum" = "$manifest_hash  manifest.json" ] \
    || fail 'manifest.json.sha256 必须只引用相对文件名 manifest.json。'
ssh-keygen -Y verify -f "$ALLOWED_SIGNERS" -I "$SIGNING_IDENTITY" -n "$SIGNING_NAMESPACE" \
    -s "$BACKUP_DIR/manifest.json.sig" <"$BACKUP_DIR/manifest.json" >/dev/null \
    || fail 'manifest detached signature 校验失败。'
(cd "$BACKUP_DIR" && sha256sum -c 'manifest.json.sha256' >/dev/null) \
    || fail 'manifest.json 摘要校验失败。'

# Python 只解析已完成签名认证的 manifest，并以 O_NOFOLLOW 重新读取每个产物做完整哈希。
if ! manifest_values_raw=$(python3 - "$BACKUP_DIR" <<'PY'
import hashlib
import json
import os
import re
import stat
import sys
from datetime import datetime, timezone

backup_dir = sys.argv[1]
expected_artifacts = {
    "mysql.sql.gz.gpg",
    "mysql-inventory.tsv.gz.gpg",
    "redis-inventory.tsv.gz.gpg",
    "uploads-inventory.tsv.gz.gpg",
    "redis-data.tar.gz.gpg",
    "uploads.tar.gz.gpg",
}
def reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"JSON 字段重复：{key}")
        result[key] = value
    return result

with open(os.path.join(backup_dir, "manifest.json"), encoding="utf-8") as source:
    manifest = json.load(source, object_pairs_hook=reject_duplicate_keys)
if set(manifest) != {
    "schemaVersion", "runId", "status", "cutAt", "finishedAt", "project",
    "branch", "commit", "images", "applicationRevision", "gpgRecipientFingerprint", "artifacts",
}:
    raise SystemExit("manifest 顶层字段不符合 schema v2")
if manifest["schemaVersion"] != 2 or manifest["status"] != "PASSED":
    raise SystemExit("manifest 未声明 schema v2/PASSED")
if manifest["project"] != "campus-resource-platform" or manifest["branch"] != "deploy":
    raise SystemExit("manifest 项目或分支不匹配")
run_id = manifest["runId"]
if not re.fullmatch(r"[A-Za-z0-9_.-]+", run_id) or os.path.basename(backup_dir) != run_id:
    raise SystemExit("runId 与备份目录不匹配")
if not re.fullmatch(r"[0-9a-f]{40}", manifest["commit"]):
    raise SystemExit("commit 格式无效")
application_revision = manifest["applicationRevision"]
if not re.fullmatch(r"[0-9a-f]{40}", application_revision):
    raise SystemExit("applicationRevision 格式无效")
try:
    cut_at = datetime.strptime(manifest["cutAt"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    finished_at = datetime.fromisoformat(manifest["finishedAt"])
except (TypeError, ValueError) as error:
    raise SystemExit("manifest 时间字段格式无效") from error
if finished_at.tzinfo is None or finished_at.astimezone(timezone.utc) < cut_at:
    raise SystemExit("manifest 完成时间早于恢复切点")
images = manifest["images"]
if set(images) != {"mysql", "redis", "backend", "frontend"}:
    raise SystemExit("manifest 必须包含四服务镜像")
for image in images.values():
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", image):
        raise SystemExit("immutable image ID 格式无效")
if images["backend"] == images["frontend"]:
    raise SystemExit("当前前后端镜像 ID 不得相同")
fingerprint = manifest["gpgRecipientFingerprint"]
if not re.fullmatch(r"(?:[0-9A-Fa-f]{40}|[0-9A-Fa-f]{64})", fingerprint):
    raise SystemExit("GPG 指纹格式无效")
artifacts = manifest["artifacts"]
if not isinstance(artifacts, list) or any(
    not isinstance(item, dict) or set(item) != {"name", "sha256", "bytes"} for item in artifacts
):
    raise SystemExit("manifest 产物条目字段不符合 schema v2")
if {item["name"] for item in artifacts} != expected_artifacts:
    raise SystemExit("manifest 产物集合不完整")
if len(artifacts) != len(expected_artifacts):
    raise SystemExit("manifest 产物名称重复")
for item in artifacts:
    name = item["name"]
    expected_sha = item.get("sha256")
    expected_size = item.get("bytes")
    if not re.fullmatch(r"[0-9a-f]{64}", str(expected_sha)):
        raise SystemExit(f"产物摘要格式无效：{name}")
    if not isinstance(expected_size, int) or expected_size <= 0:
        raise SystemExit(f"产物大小无效：{name}")
    path = os.path.join(backup_dir, name)
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1 or metadata.st_size != expected_size:
            raise SystemExit(f"产物类型或大小不匹配：{name}")
        digest = hashlib.sha256()
        while chunk := os.read(descriptor, 1024 * 1024):
            digest.update(chunk)
    finally:
        os.close(descriptor)
    if digest.hexdigest() != expected_sha:
        raise SystemExit(f"产物摘要不匹配：{name}")
print(run_id)
print(fingerprint.upper())
print(application_revision)
for service in ("mysql", "redis", "backend", "frontend"):
    print(images[service])
PY
); then
    fail 'manifest schema 或加密产物完整性校验失败。'
fi
mapfile -t manifest_values <<<"$manifest_values_raw"
[ "${#manifest_values[@]}" -eq 7 ] || fail '无法读取 manifest 恢复元数据。'
run_id=${manifest_values[0]}
recipient_fingerprint=${manifest_values[1]}
application_revision=${manifest_values[2]}
mysql_image=${manifest_values[3]}
redis_image=${manifest_values[4]}
backend_image=${manifest_values[5]}
frontend_image=${manifest_values[6]}

if ! python3 - "$ENV_FILE" <<'PY'
import sys

path = sys.argv[1]
required = {
    "MYSQL_ROOT_PASSWORD", "MYSQL_APP_USERNAME", "MYSQL_APP_PASSWORD",
    "REDIS_PASSWORD", "JWT_SECRET",
}
values = {}
with open(path, encoding="utf-8") as source:
    for number, raw in enumerate(source, 1):
        line = raw.rstrip("\r\n")
        if not line or line.lstrip().startswith("#"):
            continue
        if "=" not in line:
            raise SystemExit(f"环境文件第 {number} 行格式无效")
        key, value = line.split("=", 1)
        if not key or key.strip() != key or key in values:
            raise SystemExit(f"环境变量名称无效或重复：{key!r}")
        values[key] = value
missing = sorted(key for key in required if not values.get(key))
if missing:
    raise SystemExit("缺少恢复变量：" + ",".join(missing))
if len(values["JWT_SECRET"].encode("utf-8")) < 32:
    raise SystemExit("JWT_SECRET 少于 32 字节")
secrets = [values[key] for key in required if key != "MYSQL_APP_USERNAME"]
if len(secrets) != len(set(secrets)):
    raise SystemExit("恢复环境中的 Secret 不得复用")
PY
then
    fail '恢复环境文件校验失败。'
fi

secret_listing=$(gpg --homedir "$GPG_HOMEDIR" --batch --with-colons \
    --list-secret-keys "$recipient_fingerprint" 2>/dev/null) \
    || fail 'GPG home 中找不到 manifest 指定的恢复私钥。'
secret_key_count=$(printf '%s\n' "$secret_listing" | awk -F: '$1 == "sec" { count++ } END { print count+0 }')
secret_primary_fingerprint=$(printf '%s\n' "$secret_listing" | awk -F: \
    '$1 == "sec" { primary=1; next } primary && $1 == "fpr" { print toupper($10); exit }')
[ "$secret_key_count" -eq 1 ] && [ "$secret_primary_fingerprint" = "$recipient_fingerprint" ] \
    || fail '恢复私钥必须唯一且主指纹与 manifest 完全匹配。'

validate_image_id "$PREVIOUS_BACKEND_IMAGE" '上一版后端'
validate_image_id "$PREVIOUS_FRONTEND_IMAGE" '上一版前端'
case "$PREVIOUS_COMMIT" in
    *[!0-9a-f]*|'') fail '上一版 commit 必须是 40 位小写十六进制。' ;;
esac
[ "${#PREVIOUS_COMMIT}" -eq 40 ] || fail '上一版 commit 必须是 40 位小写十六进制。'
[ "$PREVIOUS_BACKEND_IMAGE" != "$backend_image" ] || fail '上一版后端镜像不得与当前镜像相同。'
[ "$PREVIOUS_FRONTEND_IMAGE" != "$frontend_image" ] || fail '上一版前端镜像不得与当前镜像相同。'
[ "$PREVIOUS_BACKEND_IMAGE" != "$PREVIOUS_FRONTEND_IMAGE" ] || fail '上一版前后端镜像 ID 不得相同。'
[ "$PREVIOUS_COMMIT" != "$application_revision" ] || fail '上一版 commit 不得与当前应用 revision 相同。'

docker info >/dev/null 2>&1 || fail 'Docker Engine 不可用。'
for resource_type in container volume network; do
    case "$resource_type" in
        container) production_resources=$(docker container ls -aq --filter "label=com.docker.compose.project=$PRODUCTION_PROJECT") ;;
        volume) production_resources=$(docker volume ls -q --filter "label=com.docker.compose.project=$PRODUCTION_PROJECT") ;;
        network) production_resources=$(docker network ls -q --filter "label=com.docker.compose.project=$PRODUCTION_PROJECT") ;;
    esac
    [ -z "$production_resources" ] || fail "当前 Docker daemon 存在生产 $resource_type 资源，拒绝恢复预检。"
done
for production_volume in campus-resource-platform_mysql_data campus-resource-platform_redis_data \
    campus-resource-platform_uploads_data campus-resource-platform_tls_runtime; do
    if docker volume inspect "$production_volume" >/dev/null 2>&1; then
        fail "当前 Docker daemon 存在生产卷：$production_volume"
    fi
done
tls_volumes=$(docker volume ls -q --filter "label=com.campusshare.project=$PRODUCTION_PROJECT")
[ -z "$tls_volumes" ] || fail '当前 Docker daemon 存在生产 TLS 项目标记卷。'

for image in "$mysql_image" "$redis_image" "$backend_image" "$frontend_image" \
    "$PREVIOUS_BACKEND_IMAGE" "$PREVIOUS_FRONTEND_IMAGE"; do
    actual_image=$(docker image inspect --format '{{.Id}}' "$image" 2>/dev/null) \
        || fail "本地缺少恢复所需镜像：$image"
    [ "$actual_image" = "$image" ] || fail "本地镜像身份不匹配：$image"
done
for previous_image in "$PREVIOUS_BACKEND_IMAGE" "$PREVIOUS_FRONTEND_IMAGE"; do
    previous_revision=$(docker image inspect --format \
        '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$previous_image" 2>/dev/null) \
        || fail "无法读取上一版镜像 revision：$previous_image"
    [ "$previous_revision" = "$PREVIOUS_COMMIT" ] \
        || fail "上一版镜像 revision 与 --previous-commit 不一致：$previous_image"
done
for current_image in "$backend_image" "$frontend_image"; do
    current_revision=$(docker image inspect --format \
        '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$current_image" 2>/dev/null) \
        || fail "无法读取当前应用镜像 revision：$current_image"
    [ "$current_revision" = "$application_revision" ] \
        || fail "当前应用镜像 revision 与签名 manifest 不一致：$current_image"
done

for artifact in mysql.sql.gz.gpg mysql-inventory.tsv.gz.gpg redis-inventory.tsv.gz.gpg \
    uploads-inventory.tsv.gz.gpg redis-data.tar.gz.gpg uploads.tar.gz.gpg; do
    gpg --homedir "$GPG_HOMEDIR" --batch --quiet --decrypt "$BACKUP_DIR/$artifact" \
        >/dev/null || fail "加密产物无法完整解密：$artifact"
done

printf '[DEPLOY-08] 独立恢复预检通过：runId=%s applicationRevision=%s currentBackend=%s previousBackend=%s previousCommit=%s\n' \
    "$run_id" "$application_revision" "$backend_image" "$PREVIOUS_BACKEND_IMAGE" "$PREVIOUS_COMMIT"
printf '%s\n' '[DEPLOY-08] PREFLIGHT_PASSED：未创建容器、网络或卷，尚未执行数据恢复和上一镜像验证。'
