#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
TARGET="$SCRIPT_DIR/deploy08-restore-preflight.sh"
passed=0
failed=0

check() {
    local name=$1
    shift
    if "$@"; then
        printf '[PASS] %s\n' "$name"
        passed=$((passed + 1))
    else
        printf '[FAIL] %s\n' "$name" >&2
        failed=$((failed + 1))
    fi
}

assert_contains() {
    grep -F -- "$1" "$TARGET" >/dev/null
}

assert_not_contains() {
    ! grep -F -- "$1" "$TARGET" >/dev/null
}

test_help_is_read_only() {
    local output
    output=$(bash "$TARGET" --help)
    printf '%s\n' "$output" | grep -F -- '只做预检，不创建或删除资源' >/dev/null || return 1
    ! printf '%s\n' "$output" | grep -F -- '--execute' >/dev/null
}

test_unknown_argument_fails() {
    local output status
    set +e
    output=$(bash "$TARGET" --unknown 2>&1)
    status=$?
    set -e
    [ "$status" -ne 0 ] || return 1
    printf '%s\n' "$output" | grep -F -- '未知参数' >/dev/null
}

test_required_inputs_precede_commands() {
    local required_guard command_guard
    required_guard=$(grep -nF -- '缺少必填参数' "$TARGET" | tail -n 1 | cut -d: -f1)
    command_guard=$(grep -nF -- 'for command_name in realpath' "$TARGET" | cut -d: -f1)
    [ -n "$required_guard" ] && [ -n "$command_guard" ] && [ "$required_guard" -lt "$command_guard" ]
}

test_signature_and_manifest_chain() {
    local signature_line checksum_line
    assert_contains 'sha256sum -c' || return 1
    assert_contains 'ssh-keygen -Y verify' || return 1
    assert_contains 'getattr(os, "O_NOFOLLOW", 0)' || return 1
    assert_contains 'digest.hexdigest() != expected_sha' || return 1
    assert_contains 'set(images) != {"mysql", "redis", "backend", "frontend"}' || return 1
    assert_contains 'manifest["schemaVersion"] != 2' || return 1
    assert_contains 'set(item) != {"name", "sha256", "bytes"}' || return 1
    assert_contains 'datetime.strptime(manifest["cutAt"]' || return 1
    assert_contains '备份项不得是硬链接' || return 1
    assert_contains 'object_pairs_hook=reject_duplicate_keys' || return 1
    assert_contains '必须使用无符号链接的规范绝对路径' || return 1
    signature_line=$(grep -nF -- 'ssh-keygen -Y verify' "$TARGET" | tail -n 1 | cut -d: -f1)
    checksum_line=$(grep -nF -- "sha256sum -c 'manifest.json.sha256'" "$TARGET" | cut -d: -f1)
    [ "$signature_line" -lt "$checksum_line" ] || return 1
}

test_isolated_daemon_gate() {
    assert_contains 'label=com.docker.compose.project=$PRODUCTION_PROJECT' || return 1
    assert_contains 'docker container ls -aq' || return 1
    assert_contains 'docker volume ls -q' || return 1
    assert_contains 'docker network ls -q' || return 1
    assert_contains 'campus-resource-platform_tls_runtime' || return 1
    assert_contains 'label=com.campusshare.project=$PRODUCTION_PROJECT' || return 1
    assert_not_contains 'docker run' || return 1
    assert_not_contains 'docker create' || return 1
    assert_not_contains 'docker volume create' || return 1
}

test_secret_boundaries() {
    assert_contains '恢复环境文件不得位于仓库或备份目录内' || return 1
    assert_contains 'JWT_SECRET 少于 32 字节' || return 1
    assert_contains '恢复环境中的 Secret 不得复用' || return 1
    assert_not_contains 'source "$ENV_FILE"' || return 1
}

test_decryption_and_images() {
    assert_contains '--list-secret-keys "$recipient_fingerprint"' || return 1
    assert_contains 'secret_key_count' || return 1
    assert_contains '主指纹与 manifest 完全匹配' || return 1
    assert_contains 'docker image inspect --format' || return 1
    assert_contains '上一版后端镜像不得与当前镜像相同' || return 1
    assert_contains '上一版前后端镜像 ID 不得相同' || return 1
    assert_contains '当前前后端镜像 ID 不得相同' || return 1
    assert_contains 'org.opencontainers.image.revision' || return 1
    assert_contains '上一版镜像 revision 与 --previous-commit 不一致' || return 1
    assert_contains '上一版 commit 不得与当前应用 revision 相同' || return 1
    assert_contains '当前应用镜像 revision 与签名 manifest 不一致' || return 1
    assert_contains 'gpg --homedir "$GPG_HOMEDIR" --batch --quiet --decrypt' || return 1
    assert_contains 'PREFLIGHT_PASSED：未创建容器、网络或卷' || return 1
}

check '恢复预检脚本 Bash 语法通过' bash -n "$TARGET"
check '帮助明确只读且无执行开关' test_help_is_read_only
check '未知参数失败关闭' test_unknown_argument_fails
check '必填参数门禁早于外部命令' test_required_inputs_precede_commands
check '签名、manifest 与产物摘要链齐全' test_signature_and_manifest_chain
check '生产 daemon 隔离且没有写 Docker 命令' test_isolated_daemon_gate
check 'Secret 文件和内容边界齐全' test_secret_boundaries
check '私钥、六产物和当前/上一镜像预检齐全' test_decryption_and_images

printf 'DEPLOY-08 恢复预检脚本测试完成：passed=%s failed=%s\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
