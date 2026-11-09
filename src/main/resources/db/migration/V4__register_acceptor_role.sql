-- 登记工单验收角色；已有同编码角色时保留原记录，不更改任何成员的角色。
-- 角色名称用于展示，服务端按固定 role_code = ACCEPTOR 判断验收资格。
INSERT INTO roles (role_code, name, description)
SELECT 'ACCEPTOR', '工单验收人', '可验收当前企业的待验收工单，不能验收自己维修的工单'
WHERE NOT EXISTS (
    SELECT 1 FROM roles WHERE role_code = 'ACCEPTOR'
);
