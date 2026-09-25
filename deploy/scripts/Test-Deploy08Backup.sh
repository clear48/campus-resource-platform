#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
TARGET="$SCRIPT_DIR/deploy08-backup.sh"
SYSTEM_TEMP=$(realpath -e -- "${TMPDIR:-/tmp}")
TEMP_ROOT=$(mktemp -d "$SYSTEM_TEMP/campusshare-deploy08-backup-test.XXXXXX")
FAKE_BIN="$TEMP_ROOT/bin"
FAKE_LOG="$TEMP_ROOT/commands.log"
passed=0
failed=0

cleanup() {
    local parent leaf attributes
    parent=$(realpath -e -- "$(dirname -- "$TEMP_ROOT")")
    leaf=$(basename -- "$TEMP_ROOT")
    attributes=$(stat -c '%F' "$TEMP_ROOT")
    [ "$parent" = "$SYSTEM_TEMP" ] || return 1
    case "$leaf" in campusshare-deploy08-backup-test.*) ;; *) return 1 ;; esac
    [ "$attributes" = 'directory' ] || return 1
    rm -rf -- "$TEMP_ROOT"
}

trap cleanup EXIT
mkdir -p -- "$FAKE_BIN"

# 参数负测只替换身份和 Docker 命令；Docker fake 一旦被调用就留下证据并失败。
cat >"$FAKE_BIN/id" <<'EOF'
#!/bin/sh
printf 'id %s\n' "$*" >>"$FAKE_COMMAND_LOG"
if [ "${1:-}" = '-u' ]; then
    printf '%s\n' "$FAKE_ID_UID"
else
    exec /usr/bin/id "$@"
fi
EOF
cat >"$FAKE_BIN/docker" <<'EOF'
#!/bin/sh
printf 'docker %s\n' "$*" >>"$FAKE_COMMAND_LOG"
exit 97
EOF
chmod 0700 "$FAKE_BIN/id" "$FAKE_BIN/docker"

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
    local pattern=$1
    grep -F -- "$pattern" "$TARGET" >/dev/null
}

assert_not_contains() {
    local pattern=$1
    ! grep -F -- "$pattern" "$TARGET" >/dev/null
}

line_number() {
    local pattern=$1
    grep -nF -- "$pattern" "$TARGET" | tail -n 1 | cut -d: -f1
}

run_with_fake_commands() {
    : >"$FAKE_LOG"
    env PATH="$FAKE_BIN:$PATH" FAKE_COMMAND_LOG="$FAKE_LOG" FAKE_ID_UID="$1" \
        bash "$TARGET" "${@:2}" 2>&1
}

assert_fake_docker_not_called() {
    ! grep -F -- 'docker ' "$FAKE_LOG" >/dev/null
}

test_help() {
    local output
    output=$(bash "$TARGET" --help)
    printf '%s\n' "$output" | grep -F -- '--acknowledge-downtime' >/dev/null
    printf '%s\n' "$output" | grep -F -- '默认只执行生产备份预检' >/dev/null
}

test_unknown_argument() {
    local output status
    set +e
    output=$(bash "$TARGET" --definitely-unknown 2>&1)
    status=$?
    set -e
    [ "$status" -ne 0 ]
    printf '%s\n' "$output" | grep -F -- '未知参数' >/dev/null
}

test_requires_root_before_docker() {
    local output status
    set +e
    output=$(run_with_fake_commands 1000 --gpg-recipient 'TEST-RECIPIENT')
    status=$?
    set -e
    [ "$status" -ne 0 ] || return 1
    printf '%s\n' "$output" | grep -F -- '必须通过 sudo 以 root 身份运行' >/dev/null || return 1
    assert_fake_docker_not_called || return 1
}

test_requires_absolute_output_before_docker() {
    local output status
    set +e
    output=$(run_with_fake_commands 0 --gpg-recipient 'TEST-RECIPIENT' \
        --output-dir 'relative/backup')
    status=$?
    set -e
    [ "$status" -ne 0 ] || return 1
    printf '%s\n' "$output" | grep -F -- '--output-dir 必须是绝对路径' >/dev/null || return 1
    assert_fake_docker_not_called || return 1
}

test_execute_requires_ack_before_docker() {
    local output status
    set +e
    output=$(run_with_fake_commands 0 --gpg-recipient 'TEST-RECIPIENT' --execute)
    status=$?
    set -e
    [ "$status" -ne 0 ] || return 1
    printf '%s\n' "$output" | grep -F -- \
        '--execute 必须同时提供 --acknowledge-downtime' >/dev/null || return 1
    assert_fake_docker_not_called || return 1
}

test_requires_signing_key_before_docker() {
    local output status
    set +e
    output=$(run_with_fake_commands 0 --gpg-recipient 'TEST-RECIPIENT')
    status=$?
    set -e
    [ "$status" -ne 0 ] || return 1
    printf '%s\n' "$output" | grep -F -- '必须提供 --signing-key' >/dev/null || return 1
    assert_fake_docker_not_called || return 1
}

test_default_is_preflight_only() {
    local execute_default preflight_gate run_directory_creation
    execute_default=$(line_number "EXECUTE=0")
    preflight_gate=$(line_number 'if [ "$EXECUTE" -eq 0 ]; then')
    run_directory_creation=$(line_number 'mkdir -m 0700 -- "$RUN_DIRECTORY"')
    [ -n "$execute_default" ] && [ -n "$preflight_gate" ] && [ -n "$run_directory_creation" ]
    [ "$execute_default" -lt "$preflight_gate" ]
    [ "$preflight_gate" -lt "$run_directory_creation" ]
    assert_contains '当前为预检模式；未停止服务，未创建备份。'
}

test_execute_gate_precedes_commands() {
    local execute_guard command_guard
    execute_guard=$(grep -nF -- "--execute 必须同时提供 --acknowledge-downtime" "$TARGET" | cut -d: -f1)
    command_guard=$(grep -nF -- 'for command_name in docker' "$TARGET" | cut -d: -f1)
    [ -n "$execute_guard" ] && [ -n "$command_guard" ] && [ "$execute_guard" -lt "$command_guard" ]
}

test_no_production_volume_delete() {
    ! grep -E -- 'docker([[:space:]]+compose)?[^#]*(down[^#]*(--volumes|-v)|volume[[:space:]]+rm)' \
        "$TARGET" >/dev/null
}

test_cutover_order_and_guards() {
    local frontend_stop delta_drain backend_stop final_delta part_guard waitaof data_stop
    frontend_stop=$(line_number 'compose stop --timeout 45 frontend')
    delta_drain=$(line_number 'wait_download_delta_drained "$redis_container"')
    backend_stop=$(line_number 'compose stop --timeout 45 backend')
    final_delta=$(line_number 'assert_no_pending_download_delta "$redis_container"')
    part_guard=$(line_number "'find /volume -type f -name \"*.part\" -print -quit'")
    waitaof=$(line_number 'redis_cli "$redis_container" WAITAOF 1 0 5000')
    data_stop=$(line_number 'compose stop --timeout 45 mysql redis')

    [ -n "$frontend_stop" ] && [ -n "$delta_drain" ] && [ -n "$backend_stop" ]
    [ -n "$final_delta" ] && [ -n "$part_guard" ] && [ -n "$waitaof" ] && [ -n "$data_stop" ]
    [ "$frontend_stop" -lt "$delta_drain" ]
    [ "$delta_drain" -lt "$backend_stop" ]
    [ "$backend_stop" -lt "$final_delta" ]
    [ "$final_delta" -lt "$part_guard" ]
    [ "$part_guard" -lt "$waitaof" ]
    [ "$waitaof" -lt "$data_stop" ]
    assert_contains "'crp:stats:resource:download:syncing:*'"
    assert_contains 'aof_last_write_status:ok'
    assert_contains 'aof_pending_bio_fsync:0'
}

test_encrypted_artifacts_only() {
    assert_contains 'mysql.sql.gz.gpg'
    assert_contains 'mysql-inventory.tsv.gz.gpg'
    assert_contains 'redis-inventory.tsv.gz.gpg'
    assert_contains 'uploads-inventory.tsv.gz.gpg'
    assert_contains 'redis-data.tar.gz.gpg'
    assert_contains 'uploads.tar.gz.gpg'
    assert_contains 'gpg --batch --yes --trust-model always --recipient "$GPG_RECIPIENT"'
    assert_contains '--encrypt --output "$partial_path"'
    assert_not_contains '.env.gpg'
    assert_not_contains 'privkey.pem'
}

test_restore_trap() {
    local retry_reset data_start
    assert_contains 'trap on_exit EXIT'
    assert_contains 'restore_services'
    assert_not_contains 'compose up -d --no-build --pull never mysql redis backend frontend'
    assert_contains 'docker start "$mysql_container" "$redis_container"'
    assert_contains 'docker start "$backend_container"'
    assert_contains 'docker start "$frontend_container"'
    assert_contains 'wait_container_healthy "$mysql_container" mysql'
    assert_contains 'for service in mysql redis backend frontend; do'
    assert_contains 'mysql) restored_container=$mysql_container; expected_image=$mysql_image'
    assert_contains 'redis) restored_container=$redis_container; expected_image=$redis_image'
    retry_reset=$(line_number 'RESTORE_FAILED=0')
    data_start=$(line_number 'docker start "$mysql_container" "$redis_container"')
    [ -n "$retry_reset" ] && [ -n "$data_start" ] && [ "$retry_reset" -lt "$data_start" ]
}

test_output_volume_isolation_and_signature() {
    assert_contains 'paths_overlap "$OUTPUT_DIR" "$volume_mountpoint"' || return 1
    assert_contains 'assert_container_volume "$mysql_container"' || return 1
    assert_contains 'assert_container_volume "$redis_container"' || return 1
    assert_contains 'assert_container_volume "$backend_container"' || return 1
    assert_contains "ssh-keygen -Y sign -f \"\$SIGNING_KEY\"" || return 1
    assert_contains "'ssh-ed25519 '*)" || return 1
    assert_contains '[ -s "$manifest_signature_partial" ]' || return 1
    assert_contains "(cd \"\$RUN_DIRECTORY\" && sha256sum 'manifest.json')" || return 1
}

test_lock_path_rejects_link_and_special_file() {
    assert_contains "LOCK_DIRECTORY='/run/campusshare-deploy08'" || return 1
    assert_contains '[ ! -L "$LOCK_DIRECTORY" ]' || return 1
    assert_contains "install -d -o root -g root -m 0700 -- \"\$LOCK_DIRECTORY\"" || return 1
    assert_contains '[ ! -L "$LOCK_FILE" ] && [ -f "$LOCK_FILE" ]' || return 1
    assert_contains "[ \"\$(stat -c '%u:%g:%a' \"\$LOCK_DIRECTORY\")\" = '0:0:700' ]" || return 1
}

test_manifest_published_after_signature() {
    local sign_line publish_signature_line publish_manifest_line
    sign_line=$(line_number "ssh-keygen -Y sign -f \"\$SIGNING_KEY\"")
    publish_signature_line=$(line_number 'mv -- "$manifest_signature_partial" "$manifest_path.sig"')
    publish_manifest_line=$(line_number 'mv -- "$manifest_partial" "$manifest_path"')
    [ -n "$sign_line" ] && [ -n "$publish_signature_line" ] && [ -n "$publish_manifest_line" ] || return 1
    [ "$sign_line" -lt "$publish_signature_line" ] || return 1
    [ "$publish_signature_line" -lt "$publish_manifest_line" ] || return 1
}

test_inventory_commands_fail_closed() {
    assert_contains 'if ! row_count=$(docker exec "$mysql_container"' || return 1
    assert_contains 'if ! checksum_output=$(docker exec "$mysql_container"' || return 1
    assert_contains 'if ! key_type=$(redis_cli "$redis_container" TYPE "$key_name")' || return 1
    assert_contains 'if ! pttl=$(redis_cli "$redis_container" PTTL "$key_name")' || return 1
    assert_contains 'if ! dump_sha=$(redis_cli "$redis_container" --raw DUMP "$key_name"' || return 1
    assert_contains '[ "${#dump_sha}" -eq 64 ]' || return 1
}

test_partial_finalized_after_pipeline() {
    local encrypt_line finalize_line
    encrypt_line=$(line_number '| gzip -c | encrypt_stream "$mysql_artifact"')
    finalize_line=$(line_number 'finalize_artifact "$mysql_artifact"')
    [ -n "$encrypt_line" ] && [ -n "$finalize_line" ] && [ "$encrypt_line" -lt "$finalize_line" ]
}

test_manifest_excludes_secrets() {
    local manifest_block
    manifest_block=$(sed -n "/<<'PY'/,/^PY$/p" "$TARGET")
    [ -n "$manifest_block" ]
    ! printf '%s\n' "$manifest_block" | \
        grep -Ei -- 'MYSQL_PWD|REDIS_PASSWORD|JWT|PASSWORD|SECRET|TOKEN|private.?key' >/dev/null
    printf '%s\n' "$manifest_block" | grep -F -- '"gpgRecipientFingerprint": fingerprint' >/dev/null
    printf '%s\n' "$manifest_block" | grep -F -- '"artifacts": artifacts' >/dev/null
    printf '%s\n' "$manifest_block" | grep -F -- '"mysql": mysql_image' >/dev/null
    printf '%s\n' "$manifest_block" | grep -F -- '"redis": redis_image' >/dev/null
    printf '%s\n' "$manifest_block" | grep -F -- '"schemaVersion": 2' >/dev/null
    printf '%s\n' "$manifest_block" | grep -F -- '"applicationRevision": application_revision' >/dev/null
}

test_application_revision_is_bound() {
    assert_contains 'org.opencontainers.image.revision' || return 1
    assert_contains '[ "$backend_revision" = "$frontend_revision" ]' || return 1
    assert_contains 'application_revision=$backend_revision' || return 1
}

check 'Bash 语法通过' bash -n "$TARGET"
check '帮助默认声明只做预检' test_help
check '未知参数失败关闭' test_unknown_argument
check '非 root 在 Docker 前被拒绝' test_requires_root_before_docker
check '相对输出路径在 Docker 前被拒绝' test_requires_absolute_output_before_docker
check '真实执行缺少停机确认时在 Docker 前被拒绝' test_execute_requires_ack_before_docker
check '缺少签名私钥时在 Docker 前被拒绝' test_requires_signing_key_before_docker
check '默认模式在创建备份目录前退出' test_default_is_preflight_only
check '停机确认门禁早于外部命令' test_execute_gate_precedes_commands
check '不存在生产卷删除命令' test_no_production_volume_delete
check '停写顺序及 syncing、part、WAITAOF 门禁正确' test_cutover_order_and_guards
check '业务数据只生成加密产物' test_encrypted_artifacts_only
check '退出 trap 恢复生产服务' test_restore_trap
check '输出卷隔离和 detached signature 门禁齐全' test_output_volume_isolation_and_signature
check '锁目录拒绝链接和特殊文件' test_lock_path_rejects_link_and_special_file
check 'manifest 在签名成功后发布' test_manifest_published_after_signature
check '清单逐项读取失败时关闭' test_inventory_commands_fail_closed
check '管道成功后才原子完成加密产物' test_partial_finalized_after_pipeline
check 'manifest 不包含 Secret' test_manifest_excludes_secrets
check '当前应用镜像源码 revision 已绑定' test_application_revision_is_bound

printf 'DEPLOY-08 备份脚本测试完成：passed=%s failed=%s\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
