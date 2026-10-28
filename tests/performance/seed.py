"""通过真实 API 准备可丢弃的合成资料；只有管理员授权与分类初始化使用 SQL。"""

import argparse
import hashlib
from pathlib import Path
import re
import sys
import time
import uuid

from common import api, load_context, mysql, read_json, write_json


def register_and_login(context, username, nickname):
    """先注册再登录，不从外部数据库读取或复制用户凭据。"""
    password = context["userPassword"]
    registration = api(context, "/auth/register", "POST", payload={
        "username": username, "password": password, "nickname": nickname})
    login = api(context, "/auth/login", "POST", payload={"username": username, "password": password})
    return {"userId": registration["userId"], "username": username,
            "token": login["accessToken"]}


def create_text(path, run_id, index, size):
    """内容包含批次和序号，避免仅修改文件名而误测成去重。"""
    prefix = f"campus synthetic loadtest {run_id} file {index}\n".encode("utf-8")
    content = (prefix + b"Synthetic UTF-8 teaching resource.\n" * (size // 32 + 1))[:size]
    with path.open("xb") as stream:
        stream.write(content)
    return hashlib.md5(content).hexdigest()


def mysql_target(context):
    """完成清单记录无密码的 SQL 目标，复用时拒绝偷偷切换实例或库。"""
    return {"host": context.get("mysqlHost", "127.0.0.1"),
            "port": int(context["mysqlPort"]), "database": context["mysqlDatabase"]}


def verify_category_binding(context, expected):
    """在第一个 API 写请求前，用随机分类标记绑定 HTTP 与 SQL 的同一数据目标。"""
    expected_map = {int(row["categoryId"]): row["categoryName"] for row in expected}
    rows = mysql(context, "SELECT id,category_name FROM category WHERE parent_id=0 AND status=1 ORDER BY id;")
    sql_map = {int(row.split("\t", 1)[0]): row.split("\t", 1)[1]
               for row in rows.splitlines() if row}
    actual = api(context, "/categories?parentId=0")
    api_map = {int(row["categoryId"]): row["categoryName"] for row in actual}
    if not expected_map or sql_map != expected_map or api_map != expected_map:
        raise RuntimeError("API 与 SQL 分类目标不一致，禁止发送 API 写请求")
    return actual


def assert_empty_database(context):
    """所有业务表必须为空，不接受仅 user 为空但残留资料/文件的库。"""
    tables = ("user", "category", "file_info", "user_file_authorization", "resource",
              "download_delta_sync_item", "favorite", "download_record", "audit_record")
    query = " UNION ALL ".join(f"SELECT '{table}',COUNT(*) FROM `{table}`" for table in tables) + ";"
    rows = mysql(context, query).splitlines()
    if len(rows) != len(tables) or any(int(row.split("\t")[1]) != 0 for row in rows):
        raise RuntimeError("造数要求全新专用库，至少一个业务表非空或检查不完整")


def seed(context, resource_count, user_count):
    """完整运行生成 manifest；中断批次拒绝自动续写，避免结果不确定请求重复执行。"""
    evidence = Path(context["evidenceDir"]).resolve()
    manifest_path = evidence / "manifest.json"
    if manifest_path.exists():
        previous = read_json(manifest_path)
        if (previous.get("status") == "COMPLETE" and previous.get("runId") == context["runId"]
                and len(previous["resources"]) == resource_count
                and len(previous["accounts"]) == user_count):
            if previous.get("apiBase") != context["apiBase"] or previous.get("mysqlTarget") != mysql_target(context):
                raise RuntimeError("完成清单缺少匹配的 API/SQL 目标绑定，拒绝复用")
            verify_category_binding(context, previous["categories"])
            print("已存在同批次完成清单，复用且不产生写请求", flush=True)
            return previous
        raise RuntimeError("存在未完成/不同规模清单，请保留证据并换全新专用库和批次")
    assert_empty_database(context)
    source_root = evidence / "synthetic-files"
    # 已存在的源目录可能属于另一批次；禁止覆盖，即便其中只有一个同名文件。
    source_root.mkdir(parents=True, exist_ok=False)
    manifest = {"apiBase": context["apiBase"], "runId": context["runId"],
                "mysqlTarget": mysql_target(context),
                "status": "IN_PROGRESS", "accounts": [], "resources": [],
                "categories": [], "approvedIds": [], "keywords": ["合成", "复习", "课程"],
                "startedAt": time.time()}
    write_json(manifest_path, manifest)
    # 分类没有写 API，用随机 SQL 标记先绑定数据库，再允许注册/上传等业务写请求。
    category_marker = uuid.uuid4().hex[:12]
    expected_categories = []
    for index in range(5):
        name = f"load_{category_marker}_{index + 1}"
        new_id = mysql(context, f"INSERT INTO category(parent_id,category_name,description,sort_order,status) "
                       f"VALUES(0,'{name}','仅本地压测',{index + 1},1); SELECT LAST_INSERT_ID();")
        expected_categories.append({"categoryId": int(new_id), "categoryName": name})
    manifest["categories"] = verify_category_binding(context, expected_categories)
    write_json(manifest_path, manifest)
    # 用户名只允许安全字母数字，生成 SQL 使用受控 ID，不拼接用户原始输入。
    name_prefix = "lt" + re.sub(r"[^A-Za-z0-9]", "", context["runId"])[-20:]
    admin = register_and_login(context, name_prefix + "admin", "压测管理员")
    promoted = mysql(context, f"UPDATE `user` SET role=2 WHERE id={int(admin['userId'])} AND role=1; SELECT ROW_COUNT();")
    if promoted != "1":
        raise RuntimeError("管理员提升影响行数不为 1")
    login = api(context, "/auth/login", "POST", payload={
        "username": admin["username"], "password": context["userPassword"]})
    if login["user"]["role"] != 2:
        raise RuntimeError("管理员重新登录后的角色不正确")
    admin["token"] = login["accessToken"]
    manifest["admin"] = admin
    for index in range(user_count):
        account = register_and_login(context, f"{name_prefix}u{index:03}", f"压测用户{index:03}")
        manifest["accounts"].append(account)
        if (index + 1) % 20 == 0:
            print(f"已准备账号 {index + 1}/{user_count}", flush=True)
            write_json(manifest_path, manifest)
    approved_count = resource_count * 80 // 100
    pending_end = approved_count + resource_count * 10 // 100
    rejected_end = pending_end + resource_count * 5 // 100
    for index in range(resource_count):
        account = manifest["accounts"][index % user_count]
        # 三个批准资料额外覆盖常用文件大小，其余文件保持轻量以控制准备成本。
        size = {0: 100 * 1024, 1: 1024 * 1024, 2: 10 * 1024 * 1024}.get(index, 2048)
        file_path = source_root / f"synthetic-{index:05}.txt"
        digest = create_text(file_path, context["runId"], index, size)
        uploaded = api(context, "/files", "POST", token=account["token"], file_path=file_path)
        if uploaded["fileMd5"] != digest or uploaded["fileSize"] != size:
            raise RuntimeError("上传文件摘要/字节数与合成文件不一致")
        title = f"合成课程复习资料 {index:05}"
        resource = api(context, "/resources", "POST", token=account["token"], payload={
            "fileId": uploaded["fileId"], "title": title,
            "description": f"{context['runId']} 的无个人信息合成资料",
            "categoryId": manifest["categories"][index % 5]["categoryId"],
            "courseName": f"合成课程{index % 5 + 1}", "resourceType": (index % 5) + 1,
            "tags": ["压测", f"课程{index % 5 + 1}"]})
        resource_id = resource["resourceId"]
        status = 0
        if index < approved_count or index >= rejected_end:
            api(context, f"/admin/resources/{resource_id}/audit-approvals", "POST",
                token=admin["token"], payload={"auditReason": "本地合成数据"})
            status = 1
            if index >= rejected_end:
                api(context, f"/admin/resources/{resource_id}/offline-records", "POST",
                    token=admin["token"], payload={"offlineReason": "本地合成下架"})
                status = 3
        elif index >= pending_end:
            api(context, f"/admin/resources/{resource_id}/audit-rejections", "POST",
                token=admin["token"], payload={"rejectReason": "本地合成拒绝"})
            status = 2
        manifest["resources"].append({"resourceId": resource_id, "fileId": uploaded["fileId"],
                                      "userId": account["userId"], "status": status,
                                      "md5": digest, "size": size,
                                      "path": str(file_path), "title": title})
        if status == 1:
            manifest["approvedIds"].append(resource_id)
        if (index + 1) % 25 == 0:
            print(f"已准备资料 {index + 1}/{resource_count}", flush=True)
            write_json(manifest_path, manifest)
    manifest["status"] = "COMPLETE"
    manifest["completedAt"] = time.time()
    manifest["statusCounts"] = {str(status): sum(r["status"] == status for r in manifest["resources"])
                                for status in range(4)}
    write_json(manifest_path, manifest)
    print(f"造数完成：{len(manifest['accounts'])} 用户，{len(manifest['resources'])} 资料，"
          f"{len(manifest['approvedIds'])} 已通过；清单留在仓库外", flush=True)
    return manifest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--context", required=True)
    parser.add_argument("--resources", type=int, default=1000)
    parser.add_argument("--users", type=int, default=100)
    args = parser.parse_args()
    if not 20 <= args.resources <= 10000 or not 4 <= args.users <= 1000:
        parser.error("resources 需为20～10000，users需为4～1000")
    seed(load_context(args.context), args.resources, args.users)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"造数中止：{type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
