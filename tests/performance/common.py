"""本地压测公共工具；只使用 Python 标准库，运行凭据始终留在仓库外。"""

import json
import os
from pathlib import Path
import re
import stat
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid


def read_json(path):
    """兼容 PowerShell 写入的 UTF-8 BOM。"""
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write_json(path, value):
    """使用同目录原子替换保留中断时可读的执行清单。"""
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(target)


def load_context(path):
    """限制压测为明确的本机地址与独立库，防止误写日常库或公网。"""
    context = read_json(path)
    parsed = urllib.parse.urlsplit(context["apiBase"])
    if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost", "::1"):
        raise ValueError("apiBase 必须为本机 HTTP 地址")
    if parsed.path.rstrip("/") != "/api/v1":
        raise ValueError("apiBase 必须包含 /api/v1")
    if not re.fullmatch(r"campus_loadtest_[A-Za-z0-9_]+", context["mysqlDatabase"]):
        raise ValueError("只允许 campus_loadtest_ 前缀的专用库")
    if context.get("mysqlHost", "127.0.0.1") not in ("127.0.0.1", "localhost", "::1"):
        raise ValueError("MySQL 必须为本机实例")
    # 先检查未解引用的路径链，再 resolve，避免符号链接将证据写回仓库或其他活动目录。
    evidence = Path(os.path.abspath(os.path.expanduser(context["evidenceDir"])))
    for ancestor in (evidence, *evidence.parents):
        try:
            metadata = ancestor.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(metadata.st_mode) or getattr(metadata, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT:
            raise ValueError("evidenceDir 路径链包含符号链接或 ReparsePoint")
    repository = Path(__file__).resolve().parents[2]
    if evidence.resolve().is_relative_to(repository):
        raise ValueError("evidenceDir 必须位于仓库外")
    context["evidenceDir"] = str(evidence)
    return context


def mysql(context, sql):
    """SQL 由受控输入构建，凭据经子进程环境传递而不进入命令行。"""
    environment = os.environ.copy()
    environment["MYSQL_PWD"] = context.get("mysqlPassword", "")
    command = [context["mysqlExecutable"], "--protocol=tcp",
               "--host=" + context.get("mysqlHost", "127.0.0.1"),
               "--port=" + str(context["mysqlPort"]),
               "--user=" + context["mysqlUser"], "--default-character-set=utf8mb4",
               "--batch", "--skip-column-names", context["mysqlDatabase"]]
    result = subprocess.run(command, input=sql, text=True, encoding="utf-8",
                            capture_output=True, env=environment, timeout=30)
    if result.returncode:
        # 不输出客户端命令、SQL 或完整 stderr，以免意外带出凭据和用户数据。
        raise RuntimeError("MySQL 查询失败，退出码 " + str(result.returncode))
    return result.stdout.strip()


def request(api_base, path, method="GET", token=None, payload=None,
            file_path=None, headers=None, timeout=60, expected_status=200):
    """发送单次请求；写请求不重试，超时必须由调用者独立对账。"""
    request_headers = {"Accept": "application/json", **(headers or {})}
    if token:
        request_headers["Authorization"] = "Bearer " + token
    body = None
    if file_path:
        source = Path(file_path)
        boundary = "crp-load-" + uuid.uuid4().hex
        prefix = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
                  f"filename=\"{source.name}\"\r\nContent-Type: text/plain\r\n\r\n").encode()
        body = prefix + source.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
        request_headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
    elif payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request_headers["Content-Type"] = "application/json"
    url = api_base.rstrip("/") + path
    http_request = urllib.request.Request(url, data=body, headers=request_headers, method=method)
    try:
        with urllib.request.urlopen(http_request, timeout=timeout) as response:
            status, response_headers, content = response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        status, response_headers, content = error.code, dict(error.headers), error.read()
    if expected_status is not None and status != expected_status:
        raise RuntimeError(f"HTTP {status}，预期 {expected_status}；{method} {path.split('?')[0]}")
    if "json" in response_headers.get("Content-Type", ""):
        result = json.loads(content)
        if expected_status == 200 and result.get("code") != 0:
            raise RuntimeError("业务 code 非零：" + str(result.get("code")))
        return status, response_headers, result
    return status, response_headers, content


def api(context, path, method="GET", token=None, payload=None, file_path=None):
    """正常 JSON 接口同时验证 HTTP 与业务 code。"""
    return request(context["apiBase"], path, method, token, payload, file_path)[2]["data"]
