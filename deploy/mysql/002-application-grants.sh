#!/bin/bash
set -euo pipefail

# 官方镜像只在空数据卷初始化时执行本脚本；用户名先做严格白名单校验，才允许进入 SQL 标识符与账号字面量。
application_user="${MYSQL_USER:-}"
if [[ -z "${application_user}" || "${application_user,,}" == "root" || ! "${application_user}" =~ ^[A-Za-z0-9_]{1,32}$ ]]; then
    echo "Invalid MYSQL_USER for least-privilege application account" >&2
    exit 1
fi

# 应用没有物理 DELETE 语句，仅授予现有 Mapper 需要的读、新增和更新权限。
MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql --protocol=socket -uroot <<SQL
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '${application_user}'@'%';
GRANT SELECT, INSERT, UPDATE ON \`campus_resource_platform\`.* TO '${application_user}'@'%';
FLUSH PRIVILEGES;
SQL
