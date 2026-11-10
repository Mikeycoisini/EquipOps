#!/usr/bin/env python3
"""为真实A4浏览器验收分配/清理UUID夹具；密码仅从环境读取，不写入manifest。"""
import argparse
import json
import os
from pathlib import Path
import uuid
from a1_smoke import Client, local_base
from a4_smoke import fixture


def call(client, method, path, body=None):
    status, response = client.call(method, path, body)
    assert status == 200 and response.get('code') == 200, f'浏览器夹具API预期200，实际{status}'
    return response.get('data')


def session(base, password, tenant):
    client = Client(base)
    call(client, 'POST', '/api/auth/login', {'account': 'a1_admin', 'password': password})
    call(client, 'POST', '/api/auth/active-tenant', {'tenantId': tenant})
    return client


def create(base, password):
    local_base(base)
    data = fixture('create', str(uuid.uuid4()))
    client = session(base, password, data['tenantAId'])
    path = f"/api/tenant-members/{data['memberMembershipId']}/site-scopes"
    try:
        original = call(client, 'GET', path)
        scopes = sorted(set(original['siteIds'] + [data['siteId']]), key=int)
        call(client, 'PUT', path, {'expectedVersion': original['version'], 'siteIds': scopes})
        data.update(originalSiteIds=original['siteIds'], adminAccount='a1_admin', memberAccount='a1_member',
                    tenantAName='A1验证企业A', tenantBName='A1验证企业B')
        return data
    finally:
        call(client, 'POST', '/api/auth/logout', {})


def cleanup(base, password, data):
    local_base(base)
    client = session(base, password, data['tenantAId'])
    path = f"/api/tenant-members/{data['memberMembershipId']}/site-scopes"
    try:
        current = call(client, 'GET', path)
        call(client, 'PUT', path, {'expectedVersion': current['version'], 'siteIds': data['originalSiteIds']})
        return fixture('cleanup', data['fixtureKey'])
    finally:
        call(client, 'POST', '/api/auth/logout', {})


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['create', 'cleanup'])
    parser.add_argument('--base-url', default='http://127.0.0.1:18082')
    parser.add_argument('--manifest', required=True)
    arguments = parser.parse_args()
    password = os.environ['A1_TEST_PASSWORD']
    target = Path(arguments.manifest)
    if arguments.action == 'create':
        if target.exists():
            raise SystemExit('manifest已存在，禁止覆盖未清理的夹具')
        result = create(arguments.base_url, password)
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('x', encoding='utf-8') as output:
            os.chmod(target, 0o600)
            json.dump(result, output, ensure_ascii=False, indent=2)
        print(json.dumps({'manifest': str(target), 'fixtureKey': result['fixtureKey'], 'created': True}))
    else:
        result = cleanup(arguments.base_url, password, json.loads(target.read_text()))
        print(json.dumps(result, ensure_ascii=False))
