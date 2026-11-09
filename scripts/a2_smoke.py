#!/usr/bin/env python3
"""A2真实HTTP检查：只连接经A1预检的loopback实例，测试后恢复成员启用状态。"""
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
    expect(admin, '匿名审计拒绝', 'GET', '/api/audit-events', 401)
    expect(admin, '管理员登录', 'POST', '/api/auth/login', 200, {'account': 'a1_admin', 'password': password})
    tenants = expect(admin, '企业列表', 'GET', '/api/tenants/mine', 200)
    ids = {row['tenantCode']: row['id'] for row in tenants}
    expect(admin, '选择A', 'POST', '/api/auth/active-tenant', 200, {'tenantId': ids['A1_A']})
    rows = expect(admin, '读取成员', 'GET', '/api/tenant-members', 200)['records']
    target = next(row for row in rows if row['account'] == 'a1_member')
    assert target['enabled'] is True
    member_id = target['id']
    path = f'/api/tenant-members/{member_id}/status'
    query = f'/api/audit-events?action=MEMBER_STATUS_CHANGED&outcome=SUCCESS&objectId={member_id}&size=100'
    before = expect(admin, '记录变更前计数', 'GET', query, 200)['total']
    try:
        expect(admin, '停用成员', 'PATCH', path, 200, {'enabled': False})
        expect(admin, '相同状态重试', 'PATCH', path, 200, {'enabled': False})
        after = expect(admin, '重试只记一次成功', 'GET', query, 200)
        assert after['total'] == before + 1
        event = after['records'][0]
        detail = expect(admin, '只读审计详情', 'GET', f"/api/audit-events/{event['id']}", 200)
        assert detail['changes'] == {'field': 'enabled', 'before': True, 'after': False}
        assert 'changesJson' not in detail and 'changesJson' not in event
    finally:
        expect(admin, '恢复成员', 'PATCH', path, 200, {'enabled': True})
    expect(admin, '重复添加被拒绝', 'POST', '/api/tenant-members', 409, {'userId': target['userId'], 'roleIds': []})
    failures = expect(admin, '失败审计查询', 'GET', '/api/audit-events?action=MEMBER_ADDED&outcome=FAILURE', 200)
    assert failures['records']
    failure = expect(admin, '失败错误码安全', 'GET', f"/api/audit-events/{failures['records'][0]['id']}", 200)
    assert failure['changes'] == {'errorCode': 'CONFLICT'} and failure['objectId'] is None
    expect(member, '普通成员登录', 'POST', '/api/auth/login', 200, {'account': 'a1_member', 'password': password})
    expect(member, '普通成员选择A', 'POST', '/api/auth/active-tenant', 200, {'tenantId': ids['A1_A']})
    expect(member, '普通成员查询拒绝', 'GET', '/api/audit-events', 403)
    expect(member, '普通成员修改拒绝', 'PATCH', path, 403, {'enabled': False})
    denied = expect(admin, '拒绝审计查询', 'GET', '/api/audit-events?outcome=DENIED', 200)
    assert any(str(row['actorUserId']) == str(target['userId']) for row in denied['records'])
    expect(admin, '零ID400', 'GET', '/api/audit-events/0', 400)
    expect(admin, '溢出页码400', 'GET', '/api/audit-events?current=9223372036854775807&size=100', 400)
    expect(admin, '无效日期400', 'GET', '/api/audit-events?occurredFrom=invalid', 400)
    expect(admin, '日期倒序400', 'GET', '/api/audit-events?occurredFrom=2026-11-09T10:00:00&occurredTo=2026-11-08T10:00:00', 400)
    expect(admin, '切换B', 'POST', '/api/auth/active-tenant', 200, {'tenantId': ids['A1_B']})
    if os.getenv('A2_CROSS_TENANT_ADMIN') == 'true':
        page = expect(admin, 'B管理员不读A记录', 'GET', '/api/audit-events', 200)
        assert not any(row['id'] == event['id'] for row in page['records'])
        expect(admin, '跨企业详情404', 'GET', f"/api/audit-events/{event['id']}", 404)
    else:
        expect(admin, 'B不继承A管理员权', 'GET', '/api/audit-events', 403)
    expect(admin, '管理员退出', 'POST', '/api/auth/logout', 200, {})
    expect(member, '成员退出', 'POST', '/api/auth/logout', 200, {})
    return checks


if __name__ == '__main__':
    print(json.dumps(run(os.getenv('A2_BASE_URL', 'http://127.0.0.1:18082'), os.environ['A1_TEST_PASSWORD']), ensure_ascii=False, indent=2))
