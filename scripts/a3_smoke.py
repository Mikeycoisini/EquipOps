#!/usr/bin/env python3
"""A3真实Session/CSRF范围检查，仅经A1隔离预检的loopback实例；恢复原成员范围。"""
import json
import os
from a1_smoke import Client


def run(base, password):
    checks = []

    def expect(client, name, method, path, status, body=None):
        actual, response = client.call(method, path, body)
        assert actual == status, f'{name}: expected {status}, got {actual}'
        checks.append({'name': name, 'status': actual})
        return response.get('data')

    admin, member = Client(base), Client(base)
    for client, account in [(admin, 'a1_admin'), (member, 'a1_member')]:
        expect(client, account + '登录', 'POST', '/api/auth/login', 200,
               {'account': account, 'password': password})
    tenants = expect(admin, '企业列表', 'GET', '/api/tenants/mine', 200)
    tenant = next(row['id'] for row in tenants if row['tenantCode'] == 'A1_A')
    for client in [admin, member]:
        expect(client, '选择隔离企业A', 'POST', '/api/auth/active-tenant', 200, {'tenantId': tenant})
    rows = expect(admin, '管理员成员目录', 'GET', '/api/tenant-members', 200)['records']
    target = next(row for row in rows if row['account'] == 'a1_member')
    assert isinstance(target['id'], str), '成员BIGINT应以字符串返回'
    path = f"/api/tenant-members/{target['id']}/site-scopes"
    original = expect(admin, '读取原范围', 'GET', path, 200)
    default = expect(admin, '管理员默认厂区', 'GET', '/api/sites', 200)[0]
    catalog = expect(admin, '全企业厂区目录', 'GET', '/api/sites/catalog', 200)
    secondary = next((site for site in catalog if site['siteCode'] == 'A3_HTTP'), None)
    payload = {'siteCode': 'A3_HTTP', 'name': 'A3 HTTP验证厂区', 'enabled': True}
    if secondary is None:
        secondary = expect(admin, '创建厂区真实入库', 'POST', '/api/sites', 200, payload)
    else:
        secondary = expect(admin, '恢复测试厂区', 'PUT', f"/api/sites/{secondary['id']}", 200, payload)
    saved = expect(admin, '创建后持久化回查', 'GET', f"/api/sites/{secondary['id']}", 200)
    assert saved['siteCode'] == 'A3_HTTP' and isinstance(saved['id'], str)

    def replace(name, ids):
        current = expect(admin, name + '读取版本', 'GET', path, 200)
        return expect(admin, name, 'PUT', path, 200,
                      {'expectedVersion': current['version'], 'siteIds': ids})

    try:
        empty = replace('清空授权', [])
        assert expect(member, '已登录成员立即变空范围', 'GET', '/api/sites/catalog', 200) == []
        expect(member, '隐藏默认厂区详情', 'GET', f"/api/sites/{default['id']}", 404)
        expect(member, '无授权不回退默认厂区', 'GET', '/api/sites', 409)
        expect(member, '无范围不创建导出', 'POST', '/api/inventory/exports', 403, {})
        expect(member, '普通成员不读管理范围', 'GET', path, 403)
        expect(member, '普通成员不修改范围', 'PUT', path, 403,
               {'expectedVersion': empty['version'], 'siteIds': [default['id']]})
        expect(member, '普通成员不创建厂区', 'POST', '/api/sites', 403, payload)
        expect(member, '普通成员不修改厂区', 'PUT', f"/api/sites/{secondary['id']}", 403, payload)
        granted = replace('仅授予非默认厂区', [secondary['id']])
        visible = expect(member, '旧会话只见新授权厂区', 'GET', '/api/sites/catalog', 200)
        assert [row['id'] for row in visible] == [secondary['id']]
        expect(member, '非默认授权仍不冒充默认厂区', 'GET', '/api/sites', 409)
        expect(admin, '旧版本替换409', 'PUT', path, 409,
               {'expectedVersion': empty['version'], 'siteIds': []})
        duplicate = {'expectedVersion': granted['version'], 'siteIds': [secondary['id'], secondary['id']]}
        expect(admin, '重复范围ID拒绝', 'PUT', path, 400, duplicate)
        both = replace('授予两个厂区', [default['id'], secondary['id']])
        assert len(expect(member, '普通成员两个可见范围', 'GET', '/api/sites/catalog', 200)) == 2
        assert expect(member, '默认厂区保持原契约', 'GET', '/api/sites', 200)[0]['id'] == default['id']
        repeated = expect(admin, '相同集合幂等', 'PUT', path, 200,
                          {'expectedVersion': both['version'], 'siteIds': both['siteIds']})
        assert repeated['version'] == both['version']
        replace('已登录成员撤销全部', [])
        assert expect(member, '撤权下一请求立即生效', 'GET', '/api/sites/catalog', 200) == []
        events = expect(admin, '范围成功审计', 'GET',
                        f"/api/audit-events?action=MEMBER_SITE_SCOPES_CHANGED&objectId={target['id']}&outcome=SUCCESS", 200)
        assert events['records']
        detail = expect(admin, '范围审计详情', 'GET', f"/api/audit-events/{events['records'][0]['id']}", 200)
        assert detail['changes']['field'] == 'siteIds'
    finally:
        replace('恢复测试前成员范围', original['siteIds'])
        expect(admin, '停用无业务关联的测试厂区', 'PUT', f"/api/sites/{secondary['id']}", 200,
               {**payload, 'enabled': False})
    return checks


if __name__ == '__main__':
    print(json.dumps(run(os.getenv('A3_BASE_URL', 'http://127.0.0.1:18082'),
                         os.environ['A1_TEST_PASSWORD']), ensure_ascii=False, indent=2))
