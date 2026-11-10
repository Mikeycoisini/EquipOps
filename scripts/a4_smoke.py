#!/usr/bin/env python3
"""A4真实Session/CSRF验收；仅loopback与已预检A1隔离库，UUID业务夹具精确清理。"""
import json
import os
from pathlib import Path
import subprocess
import uuid
from a1_smoke import Client, local_base


def fixture(action, key):
    root = Path(__file__).resolve().parents[1]
    classpath = os.pathsep.join(('target/test-classes', 'target/classes',
                                (root / 'target/a1-classpath.txt').read_text().strip()))
    result = subprocess.run(['java', '-cp', classpath, 'com.support.A4Fixture', action, key],
                            cwd=root, check=True, text=True, capture_output=True)
    return json.loads(result.stdout)


def run(base, password):
    base = local_base(base)
    if not password or len(password) < 16:
        raise ValueError('必须从受保护环境提供A1_TEST_PASSWORD')
    checks = []

    def expect(client, name, method, path, status, body=None, csrf=True):
        actual, response = client.call(method, path, body, csrf)
        assert actual == status, f'{name}: expected {status}, got {actual}'
        if status == 200:
            assert response.get('code') == 200, f'{name}: business result not successful'
        checks.append({'name': name, 'status': actual})
        return response.get('data')

    key = str(uuid.uuid4())
    data = fixture('create', key)
    admin, member = Client(base), Client(base)
    original = None
    scope_path = f"/api/tenant-members/{data['memberMembershipId']}/site-scopes"

    def replace_scope(name, ids):
        current = expect(admin, name + '读取版本', 'GET', scope_path, 200)
        return expect(admin, name, 'PUT', scope_path, 200,
                      {'expectedVersion': current['version'], 'siteIds': ids})

    def reassign_payload(order, user, team, reason):
        return {'requestId': str(uuid.uuid4()), 'expectedVersion': order['version'],
                'targetAssigneeUserId': user, 'targetTeamId': team, 'reason': reason}

    try:
        expect(admin, '匿名团队读取拒绝', 'GET', '/api/teams', 401)
        expect(admin, '登录必须真实CSRF', 'POST', '/api/auth/login', 403,
               {'account': 'a1_admin', 'password': password}, False)
        for client, account in [(admin, 'a1_admin'), (member, 'a1_member')]:
            expect(client, account + '真实会话登录', 'POST', '/api/auth/login', 200,
                   {'account': account, 'password': password})
            expect(client, account + '选择企业A', 'POST', '/api/auth/active-tenant', 200,
                   {'tenantId': data['tenantAId']})
        original = expect(admin, '保存成员原个人范围', 'GET', scope_path, 200)
        granted = sorted(set(original['siteIds'] + [data['siteId']]), key=int)
        replace_scope('加入本次夹具厂区个人授权', granted)
        create = {'requestId': str(uuid.uuid4()), 'teamCode': data['teamCode'],
                  'name': 'A4 HTTP团队 ' + key[:8], 'enabled': True}
        team = expect(admin, '团队POST200与字符串ID', 'POST', '/api/teams', 200, create)
        assert isinstance(team['id'], str) and isinstance(team['tenantId'], str)
        assert team['version'] == 0 and team['membershipIds'] == [] and team['siteIds'] == []
        replay = expect(admin, '团队同号返回原完整结果', 'POST', '/api/teams', 200, create)
        assert replay == team
        expect(admin, '团队同号异内容409', 'POST', '/api/teams', 409, {**create, 'name': '异内容'})
        path = f"/api/teams/{team['id']}"
        expect(member, '未参加团队详情404', 'GET', path, 404)
        expect(member, '普通成员不能新建团队', 'POST', '/api/teams', 403,
               {**create, 'requestId': str(uuid.uuid4())})
        team = expect(admin, '团队完整替换成员', 'PUT', path + '/members', 200,
                      {'requestId': str(uuid.uuid4()), 'expectedVersion': team['version'],
                       'membershipIds': [data['memberMembershipId']]})
        team = expect(admin, '团队完整替换工作厂区', 'PUT', path + '/site-scopes', 200,
                      {'requestId': str(uuid.uuid4()), 'expectedVersion': team['version'],
                       'siteIds': [data['siteId']]})
        assert expect(member, '旧成员会话立即可见参加团队', 'GET', path, 200)['id'] == team['id']
        expect(member, '普通成员不能维护团队', 'PUT', path, 403,
               {'requestId': str(uuid.uuid4()), 'expectedVersion': team['version'], 'name': '越权', 'enabled': True})
        expect(admin, '重复成员ID拒绝', 'PUT', path + '/members', 400,
               {'requestId': str(uuid.uuid4()), 'expectedVersion': team['version'],
                'membershipIds': [data['memberMembershipId']] * 2})
        expect(admin, '小数团队版本拒绝', 'PUT', path, 400,
               {'requestId': str(uuid.uuid4()), 'expectedVersion': 0.9, 'name': '非法版本', 'enabled': True})
        expect(admin, '旧团队版本409', 'PUT', path, 409,
               {'requestId': str(uuid.uuid4()), 'expectedVersion': 0, 'name': '过期请求', 'enabled': True})
        team = expect(admin, '团队改名推进版本', 'PUT', path, 200,
                      {'requestId': str(uuid.uuid4()), 'expectedVersion': team['version'],
                       'name': 'A4 HTTP已改名 ' + key[:8], 'enabled': True})
        assert expect(admin, '团队创建旧请求保留当时快照', 'POST', '/api/teams', 200, create)['version'] == 0
        order_path = f"/api/work-orders/{data['workOrderId']}"
        order = expect(admin, '读取已分配PENDING工单', 'GET', order_path, 200)
        assert order['status'] == 'PENDING' and order['assigneeUserId'] == data['memberUserId']
        reason = 'A4 HTTP责任交接 ' + key[:8]
        command = reassign_payload(order, data['memberUserId'], team['id'], reason)
        expect(admin, '改派写入必须CSRF', 'POST', order_path + '/reassign', 403, command, False)
        reassigned = expect(admin, 'PENDING改派加入团队且状态不变', 'POST', order_path + '/reassign', 200, command)
        assert reassigned['status'] == 'PENDING' and reassigned['teamId'] == team['id'] and reassigned['version'] == order['version'] + 1
        assert expect(admin, '改派同号幂等原结果', 'POST', order_path + '/reassign', 200, command) == reassigned
        expect(admin, '改派同号异理由409', 'POST', order_path + '/reassign', 409, {**command, 'reason': '异内容'})
        expect(member, '普通成员不能改派', 'POST', order_path + '/reassign', 403,
               reassign_payload(reassigned, data['adminUserId'], None, reason))
        expect(admin, '旧工单版本409', 'POST', order_path + '/reassign', 409,
               {**reassign_payload(reassigned, data['adminUserId'], None, reason), 'expectedVersion': 0})
        for suffix, field in [('/members', 'membershipIds'), ('/site-scopes', 'siteIds')]:
            expect(admin, '未完成工单要求先交接' + suffix, 'PUT', path + suffix, 409,
                   {'requestId': str(uuid.uuid4()), 'expectedVersion': team['version'], field: []})
        expect(admin, '未完成工单团队不能停用', 'PUT', path, 409,
               {'requestId': str(uuid.uuid4()), 'expectedVersion': team['version'], 'name': team['name'], 'enabled': False})
        started = expect(member, '当前负责人开工推进version', 'POST', order_path + '/start', 200)
        assert started['status'] == 'IN_PROGRESS' and started['version'] == reassigned['version'] + 1
        personal = expect(admin, 'IN_PROGRESS改派到个人且状态不变', 'POST', order_path + '/reassign', 200,
                          reassign_payload(started, data['adminUserId'], None, reason))
        assert personal['status'] == 'IN_PROGRESS' and personal.get('teamId') is None
        expect(member, '旧负责人不能提交验收', 'POST', order_path + '/submit', 403)
        returned = expect(admin, '责任交回有个人授权的成员', 'POST', order_path + '/reassign', 200,
                          reassign_payload(personal, data['memberUserId'], None, reason))
        submitted = expect(member, '提交验收推进version', 'POST', order_path + '/submit', 200)
        assert submitted['version'] == returned['version'] + 1
        expect(admin, '待验收禁止改派', 'POST', order_path + '/reassign', 409,
               reassign_payload(submitted, data['adminUserId'], None, reason))
        rejected = expect(admin, '验收驳回推进version', 'POST', order_path + '/reject', 200, {'reason': 'A4验证退回'})
        assert rejected['status'] == 'IN_PROGRESS' and rejected['version'] == submitted['version'] + 1
        submitted = expect(member, '重新提交验收', 'POST', order_path + '/submit', 200)
        completed = expect(admin, '职责分离验收完成推进version', 'POST', order_path + '/approve', 200)
        assert completed['status'] == 'COMPLETED' and completed['version'] == submitted['version'] + 1
        expect(admin, '完成工单禁止改派', 'POST', order_path + '/reassign', 409,
               reassign_payload(completed, data['adminUserId'], None, reason))
        second_path = f"/api/work-orders/{data['otherOrderId']}"
        before = expect(admin, '读取未派单PENDING夹具', 'GET', second_path, 200)
        dispatched = expect(admin, '旧派单保留且推进version', 'PUT', second_path + '/dispatch', 200,
                            {'assigneeUserId': data['memberUserId']})
        assert dispatched['version'] == before['version'] + 1
        replace_scope('撤销负责人个人厂区授权', [site for site in granted if site != data['siteId']])
        expect(admin, '团队资格不能替代个人厂区资格', 'POST', second_path + '/reassign', 400,
               reassign_payload(dispatched, data['memberUserId'], team['id'], reason))
        replace_scope('恢复负责人本次厂区授权', granted)
        expect(admin, '切换企业B', 'POST', '/api/auth/active-tenant', 200, {'tenantId': data['tenantBId']})
        expect(admin, '跨企业团队404', 'GET', path, 404)
        expect(admin, 'B企业不继承A管理改派权', 'POST', order_path + '/reassign', 403,
               reassign_payload(completed, data['adminUserId'], None, reason))
        expect(admin, '切回企业A审计', 'POST', '/api/auth/active-tenant', 200, {'tenantId': data['tenantAId']})
        events = expect(admin, '团队创建只有一个SUCCESS事件', 'GET',
                        f"/api/audit-events?action=TEAM_CREATED&outcome=SUCCESS&objectId={team['id']}", 200)
        assert events['total'] == 1
        events = expect(admin, '改派成功审计可追溯', 'GET',
                        f"/api/audit-events?action=WORK_ORDER_REASSIGNED&outcome=SUCCESS&objectId={data['workOrderId']}", 200)
        assert events['total'] == 3
        detail = expect(admin, '改派审计不包含理由正文', 'GET', f"/api/audit-events/{events['records'][0]['id']}", 200)
        assert detail['changes']['reasonPresent'] is True and reason not in json.dumps(detail, ensure_ascii=False)
        denied = expect(admin, '团队拒绝审计固定安全字段', 'GET', '/api/audit-events?action=TEAM_CREATED&outcome=DENIED', 200)
        assert any(event['actorUserId'] == data['memberUserId'] and event.get('objectId') is None for event in denied['records'])
        logs = expect(admin, '原交接业务日志保留且ID字符串', 'GET', order_path + '/logs', 200)
        assert any(reason in (row.get('detail') or '') for row in logs)
        for row in logs:
            for field in ('id', 'workOrderId', 'actorUserId'):
                assert row.get(field) is None or isinstance(row[field], str)
    finally:
        try:
            if original is not None:
                # 若中途停在B企业，恢复授权前先切回原企业。
                expect(admin, '清理前恢复企业A', 'POST', '/api/auth/active-tenant', 200, {'tenantId': data['tenantAId']})
                replace_scope('恢复验证前个人厂区集合', original['siteIds'])
                expect(admin, '管理员退出隔离会话', 'POST', '/api/auth/logout', 200, {})
                expect(member, '成员退出隔离会话', 'POST', '/api/auth/logout', 200, {})
        finally:
            cleaned = fixture('cleanup', key)
            assert cleaned['cleaned'] and cleaned['remainingOrders'] == 0 and cleaned['remainingTeams'] == 0
            checks.append({'name': 'UUID设备工单团队精确清理', 'status': 'passed'})
    return checks


if __name__ == '__main__':
    print(json.dumps(run(os.getenv('A4_BASE_URL', 'http://127.0.0.1:18082'),
                         os.environ['A1_TEST_PASSWORD']), ensure_ascii=False, indent=2))
