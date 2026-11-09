#!/usr/bin/env python3
"""对真实Session/CSRF执行A1检查，仅允许本机隔离应用。"""
import argparse
import http.cookiejar
import json
import os
import urllib.error
import urllib.parse
import urllib.request


def local_base(value):
    parsed = urllib.parse.urlsplit(value)
    if (parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost"}
            or parsed.username or parsed.password or parsed.path not in {"", "/"}
            or parsed.query or parsed.fragment):
        raise ValueError("只允许无凭据的loopback HTTP源地址")
    return value.rstrip("/")


class Client:
    def __init__(self, base):
        self.base = local_base(base)
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(self.cookies))

    def call(self, method, path, body=None, csrf=True):
        headers = {"Content-Type": "application/json"}
        if method != "GET" and csrf:
            status, token = self.call("GET", "/api/auth/csrf")
            assert status == 200 and token["data"]["token"], "CSRF获取失败"
            headers[token["data"]["headerName"]] = token["data"]["token"]
        request = urllib.request.Request(self.base + path, method=method, headers=headers,
                                        data=json.dumps(body).encode() if body is not None else None)
        try:
            response = self.opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, json.loads(response.read())


def run(base, password):
    if not password or len(password) < 16:
        raise ValueError("必须从环境提供A1_TEST_PASSWORD")
    checks = []

    def expect(client, name, method, path, status, body=None, csrf=True):
        actual, response = client.call(method, path, body, csrf)
        assert actual == status, f"{name}预期HTTP{status}，实际{actual}"
        if status == 200:
            assert response.get("code") == 200, f"{name}业务结果未成功"
        checks.append({"name": name, "status": actual})
        return response.get("data")

    admin = Client(base)
    expect(admin, "匿名拒绝", "GET", "/api/auth/me", 401)
    expect(admin, "登录必须CSRF", "POST", "/api/auth/login", 403,
           {"account": "a1_admin", "password": password}, False)
    expect(admin, "错误密码拒绝", "POST", "/api/auth/login", 401,
           {"account": "a1_admin", "password": "invalid-password"})
    expect(admin, "真实密码登录", "POST", "/api/auth/login", 200,
           {"account": "a1_admin", "password": password})
    assert any(cookie.name == "EQUIPOPS_V2_SESSION" for cookie in admin.cookies), "缺少Session Cookie"
    tenants = expect(admin, "读取所属企业", "GET", "/api/tenants/mine", 200)
    ids = {tenant["tenantCode"]: tenant["id"] for tenant in tenants}
    assert set(ids) == {"A1_A", "A1_B"}, "测试企业不完整"
    active = expect(admin, "选择A企业", "POST", "/api/auth/active-tenant", 200, {"tenantId": ids["A1_A"]})
    assert "TENANT_ADMIN" in active["roleCodes"]
    expect(admin, "A企业管理员权限", "GET", "/api/tenant-members", 200)
    sites = expect(admin, "A企业默认厂区", "GET", "/api/sites", 200)
    site_a = sites[0]["id"]
    active = expect(admin, "切换B企业", "POST", "/api/auth/active-tenant", 200, {"tenantId": ids["A1_B"]})
    assert "TENANT_ADMIN" not in active["roleCodes"], "切换后继承了旧企业角色"
    expect(admin, "B企业不继承管理权", "GET", "/api/tenant-members", 403)
    expect(admin, "跨企业对象隐藏", "GET", f"/api/sites/{site_a}", 404)
    active = expect(admin, "切回A企业", "POST", "/api/auth/active-tenant", 200, {"tenantId": ids["A1_A"]})
    assert "TENANT_ADMIN" in active["roleCodes"]
    member = Client(base)
    expect(member, "普通成员登录", "POST", "/api/auth/login", 200,
           {"account": "a1_member", "password": password})
    expect(member, "普通成员选择A", "POST", "/api/auth/active-tenant", 200, {"tenantId": ids["A1_A"]})
    expect(member, "普通成员管理拒绝", "GET", "/api/tenant-members", 403)
    expect(member, "无成员关系切换拒绝", "POST", "/api/auth/active-tenant", 403, {"tenantId": ids["A1_B"]})
    identity = expect(member, "失败切换保留原企业", "GET", "/api/auth/me", 200)
    assert str(identity["activeTenant"]["id"]) == str(ids["A1_A"])
    expect(admin, "退出", "POST", "/api/auth/logout", 200, {})
    expect(admin, "退出后原会话拒绝", "GET", "/api/auth/me", 401)
    return checks


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8082")
    arguments = parser.parse_args()
    print(json.dumps(run(arguments.base_url, os.getenv("A1_TEST_PASSWORD")), ensure_ascii=False, indent=2))
