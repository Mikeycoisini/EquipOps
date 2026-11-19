-- 为前端“测试企业 No.1”（tenant_code = TEST_NO_1）生成 120 样本规模的完整演示数据。
-- 覆盖：成员/厂区/仓库/班组、设备、备件、工单及时间线、改派、维护计划与执行、
--       库存流水与调拨、库存导出任务、导出故障告警与处置、审计事件。
-- 只追加演示数据（编码统一 TS1- / ts1_），不修改既有账号与配置；重复执行会中止并提示。
-- 执行：mysql -uroot -p equipops_v2 < test_no1_demo_seed_120.sql
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS seed_equipops_test_no1_demo_120;
DELIMITER $$
CREATE PROCEDURE seed_equipops_test_no1_demo_120()
SQL SECURITY INVOKER
BEGIN
    DECLARE v_tenant BIGINT UNSIGNED;
    DECLARE v_admin BIGINT UNSIGNED;
    DECLARE v_reporter BIGINT UNSIGNED;
    DECLARE v_technician BIGINT UNSIGNED;
    DECLARE v_acceptor BIGINT UNSIGNED;
    DECLARE v_site BIGINT UNSIGNED;
    DECLARE v_admin_role BIGINT UNSIGNED;
    DECLARE v_acceptor_role BIGINT UNSIGNED;
    DECLARE v_rule BIGINT UNSIGNED;
    DECLARE v_n INT;
    DECLARE v_now DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3);
    DECLARE v_old DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) - INTERVAL 10 DAY;
    DECLARE v_hash VARCHAR(255) DEFAULT '$2a$10$96VXa8t4/.WzyKjSKRiHmelccnhbF9hvCjfjz7TWqikFwqKOa.yL2';
    DECLARE v_ids JSON DEFAULT (JSON_OBJECT());
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        DROP TEMPORARY TABLE IF EXISTS t1_n;
        DROP TEMPORARY TABLE IF EXISTS t1_counts;
        RESIGNAL;
    END;

    SELECT id INTO v_tenant FROM tenants WHERE tenant_code = 'TEST_NO_1';
    IF v_tenant IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'tenant TEST_NO_1 not found';
    END IF;
    IF EXISTS (SELECT 1 FROM equipment WHERE tenant_id = v_tenant AND asset_code LIKE 'TS1-EQ-%') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'TS1 120-row fixture already exists; nothing changed';
    END IF;
    IF EXISTS (SELECT 1 FROM users WHERE account LIKE 'ts1_user_%') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'TS1 fixture accounts already exist; nothing changed';
    END IF;
    SELECT id INTO v_admin FROM users WHERE account = 'admin';
    SELECT id INTO v_reporter FROM users WHERE account = 'reporter';
    SELECT id INTO v_technician FROM users WHERE account = 'technician';
    SELECT id INTO v_acceptor FROM users WHERE account = 'acceptor';
    SELECT id INTO v_site FROM sites WHERE tenant_id = v_tenant AND is_default = 1 LIMIT 1;
    SELECT id INTO v_admin_role FROM roles WHERE role_code = 'TENANT_ADMIN';
    SELECT id INTO v_acceptor_role FROM roles WHERE role_code = 'ACCEPTOR';
    IF v_admin IS NULL OR v_technician IS NULL OR v_site IS NULL
       OR v_admin_role IS NULL OR v_acceptor_role IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'TEST_NO_1 base accounts, site or roles are missing';
    END IF;

    START TRANSACTION;
    CREATE TEMPORARY TABLE t1_n (
        n INT PRIMARY KEY,
        suffix CHAR(3) NOT NULL,
        runtime_hours DECIMAL(14,2) NOT NULL,
        fault_at DATETIME(3) NOT NULL
    ) ENGINE = InnoDB;
    INSERT INTO t1_n
    WITH RECURSIVE series AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM series WHERE n < 120)
    SELECT n, LPAD(n, 3, '0'), 100 + n, TIMESTAMPADD(HOUR, n, v_old) FROM series;

    -- 120 个成员、厂区、仓库、班组，并把每人授权到自己厂区。
    SET v_n = 1;
    WHILE v_n <= 120 DO
        INSERT INTO users(account, password_hash, display_name, enabled)
        SELECT CONCAT('ts1_user_', suffix), v_hash, CONCAT('TS1 演示人员 ', suffix), IF(n = 120, 0, 1)
        FROM t1_n WHERE n = v_n;
        SET v_ids = JSON_SET(v_ids, CONCAT('$."', 100000 + v_n, '"'), LAST_INSERT_ID());
        SET v_n = v_n + 1;
    END WHILE;
    SET v_n = 1;
    WHILE v_n <= 120 DO
        INSERT INTO tenant_memberships(tenant_id, user_id, enabled, site_scope_version)
        SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 100000 + n, '"'))) AS UNSIGNED),
               IF(n = 120, 0, 1), 1
        FROM t1_n WHERE n = v_n;
        SET v_ids = JSON_SET(v_ids, CONCAT('$."', 200000 + v_n, '"'), LAST_INSERT_ID());
        SET v_n = v_n + 1;
    END WHILE;
    INSERT INTO tenant_member_roles(tenant_id, membership_id, role_id)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 200000 + n, '"'))) AS UNSIGNED),
           IF(n = 1, v_admin_role, v_acceptor_role)
    FROM t1_n WHERE n NOT IN (2, 4);

    SET v_n = 1;
    WHILE v_n <= 120 DO
        INSERT INTO sites(tenant_id, site_code, name, enabled, is_default)
        SELECT v_tenant, CONCAT('TS1-S-', suffix), CONCAT('TS1 厂区 ', suffix), 1, 0
        FROM t1_n WHERE n = v_n;
        SET v_ids = JSON_SET(v_ids, CONCAT('$."', 300000 + v_n, '"'), LAST_INSERT_ID());
        SET v_n = v_n + 1;
    END WHILE;
    SET v_n = 1;
    WHILE v_n <= 120 DO
        INSERT INTO warehouses(tenant_id, site_id, warehouse_code, name, location, enabled, is_default)
        SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300000 + n, '"'))) AS UNSIGNED),
               CONCAT('TS1-W-', suffix), CONCAT('TS1 仓库 ', suffix), CONCAT('TS1 库房 ', suffix), 1, 0
        FROM t1_n WHERE n = v_n;
        SET v_ids = JSON_SET(v_ids, CONCAT('$."', 400000 + v_n, '"'), LAST_INSERT_ID());
        SET v_n = v_n + 1;
    END WHILE;
    INSERT INTO tenant_member_site_scopes(tenant_id, membership_id, site_id, granted_by)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 200000 + n, '"'))) AS UNSIGNED),
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300000 + n, '"'))) AS UNSIGNED), v_admin
    FROM t1_n;

    SET v_n = 1;
    WHILE v_n <= 120 DO
        INSERT INTO teams(tenant_id, team_code, name, enabled, version, created_by)
        SELECT v_tenant, CONCAT('TS1-T-', suffix), CONCAT('TS1 维修班组 ', suffix), IF(n = 120, 0, 1), 1, v_admin
        FROM t1_n WHERE n = v_n;
        SET v_ids = JSON_SET(v_ids, CONCAT('$."', 500000 + v_n, '"'), LAST_INSERT_ID());
        SET v_n = v_n + 1;
    END WHILE;
    INSERT INTO team_members(tenant_id, team_id, membership_id)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 500000 + n, '"'))) AS UNSIGNED),
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 200000 + n, '"'))) AS UNSIGNED)
    FROM t1_n;
    INSERT INTO team_site_scopes(tenant_id, team_id, site_id)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 500000 + n, '"'))) AS UNSIGNED),
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300000 + n, '"'))) AS UNSIGNED)
    FROM t1_n;
    INSERT INTO team_operations(tenant_id, team_id, request_id, payload_hash, action, result_json, created_at)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 500000 + n, '"'))) AS UNSIGNED),
           UUID(), SHA2(CONCAT('ts1-team-', n), 256), 'CREATE',
           JSON_OBJECT('id', CAST(CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 500000 + n, '"'))) AS UNSIGNED) AS CHAR), 'version', 1),
           v_old
    FROM t1_n;

    -- 120 台设备与 120 个备件，按厂区一一对应。
    SET v_n = 1;
    WHILE v_n <= 120 DO
        INSERT INTO equipment(tenant_id, site_id, asset_code, name, model, location, category, status, health,
                              next_maintenance_at, runtime_hours, installed_at, manager_user_id, created_at, updated_at)
        SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300000 + n, '"'))) AS UNSIGNED),
               CONCAT('TS1-EQ-', suffix), CONCAT('TS1 设备 ', suffix), CONCAT('EQ-', 1 + MOD(n, 6)),
               CONCAT('TS1 车间 ', suffix), '联调设备',
               ELT(1 + MOD(n, 3), 'RUNNING', 'NEEDS_ATTENTION', 'STOPPED'),
               60 + MOD(n, 40), CURRENT_DATE + INTERVAL MOD(n, 14) - 7 DAY, runtime_hours,
               CURRENT_DATE - INTERVAL 365 DAY,
               CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 100000 + n, '"'))) AS UNSIGNED),
               v_old, v_old + INTERVAL 1 DAY
        FROM t1_n WHERE n = v_n;
        SET v_ids = JSON_SET(v_ids, CONCAT('$."', 600000 + v_n, '"'), LAST_INSERT_ID());
        SET v_n = v_n + 1;
    END WHILE;

    SET v_n = 1;
    WHILE v_n <= 120 DO
        INSERT INTO spare_parts(tenant_id, part_code, name, spec, category, location, stock_quantity, min_stock_quantity, unit, enabled)
        SELECT v_tenant, CONCAT('TS1-P-', suffix), CONCAT('TS1 备件 ', suffix), CONCAT('型号 ', suffix),
               '机修耗材', CONCAT('TS1 库位 ', suffix), 0, IF(MOD(n, 10) = 0, 30, 5), '件', IF(n = 120, 0, 1)
        FROM t1_n WHERE n = v_n;
        SET v_ids = JSON_SET(v_ids, CONCAT('$."', 700000 + v_n, '"'), LAST_INSERT_ID());
        SET v_n = v_n + 1;
    END WHILE;
    INSERT INTO equipment_spare_parts(tenant_id, equipment_id, spare_part_id, created_by)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 600000 + n, '"'))) AS UNSIGNED),
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 700000 + n, '"'))) AS UNSIGNED), v_admin
    FROM t1_n;

    -- 120 张工单：前 30 待派单、31-60 维修中、61-90 待验收、91-120 已完成。
    -- 工单、设备、厂区、负责人、班组使用同一序号，保证数据自洽。
    SET v_n = 1;
    WHILE v_n <= 120 DO
        INSERT INTO work_orders(tenant_id, site_id, order_no, title, equipment_id, description, priority, status,
                                assignee_user_id, due_at, completed_at, created_by, created_at, updated_at, team_id, version)
        SELECT v_tenant,
               CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300000 + MOD(n - 1, 40) + 1, '"'))) AS UNSIGNED),
               CONCAT('TS1-WO-', suffix), CONCAT('TS1 检修工单 ', suffix),
               CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 600000 + MOD(n - 1, 40) + 1, '"'))) AS UNSIGNED),
               '检查设备运转、备件消耗与连接件状态。',
               ELT(1 + MOD(n, 3), 'NORMAL', 'HIGH', 'URGENT'),
               CASE WHEN n <= 30 THEN 'PENDING' WHEN n <= 60 THEN 'IN_PROGRESS'
                    WHEN n <= 90 THEN 'AWAITING_ACCEPTANCE' ELSE 'COMPLETED' END,
               IF(n <= 10, NULL,
                  CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 100000 + MOD(n - 1, 40) + 1, '"'))) AS UNSIGNED)),
               v_now - INTERVAL (2 + MOD(n, 28)) DAY + INTERVAL 3 DAY,
               IF(n > 90, v_now - INTERVAL (2 + MOD(n, 28)) DAY + INTERVAL 2 DAY, NULL),
               v_admin, v_now - INTERVAL (2 + MOD(n, 28)) DAY,
               v_now - INTERVAL (2 + MOD(n, 28)) DAY + INTERVAL 2 HOUR,
               CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 500000 + MOD(n - 1, 40) + 1, '"'))) AS UNSIGNED),
               IF(n > 90, 2, 1)
        FROM t1_n WHERE n = v_n;
        SET v_ids = JSON_SET(v_ids, CONCAT('$."', 800000 + v_n, '"'), LAST_INSERT_ID());
        SET v_n = v_n + 1;
    END WHILE;

    -- 时间线、备件用量、改派（全部数量均超过 100）。
    INSERT INTO work_order_logs(work_order_id, event_type, from_status, to_status, title, detail, actor_user_id, created_at)
    SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 800000 + n, '"'))) AS UNSIGNED),
           'CREATED', NULL, 'PENDING', '提交报修', 'TS1 演示记录', v_reporter,
           v_now - INTERVAL (2 + MOD(n, 28)) DAY
    FROM t1_n;
    INSERT INTO work_order_logs(work_order_id, event_type, from_status, to_status, title, detail, actor_user_id, created_at)
    SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 800000 + n, '"'))) AS UNSIGNED),
           'ASSIGNED', 'PENDING', 'PENDING', '指派维修人', '按班组技能派单', v_admin,
           v_now - INTERVAL (2 + MOD(n, 28)) DAY + INTERVAL 1 HOUR
    FROM t1_n WHERE n BETWEEN 11 AND 30;
    INSERT INTO work_order_logs(work_order_id, event_type, from_status, to_status, title, actor_user_id, created_at)
    SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 800000 + n, '"'))) AS UNSIGNED),
           'STATUS_CHANGED', 'PENDING', 'IN_PROGRESS', '开始维修', v_technician,
           v_now - INTERVAL (2 + MOD(n, 28)) DAY + INTERVAL 3 HOUR
    FROM t1_n WHERE n > 30;
    INSERT INTO work_order_logs(work_order_id, event_type, from_status, to_status, title, actor_user_id, created_at)
    SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 800000 + n, '"'))) AS UNSIGNED),
           'STATUS_CHANGED', 'IN_PROGRESS', 'AWAITING_ACCEPTANCE', '提交验收', v_technician,
           v_now - INTERVAL (2 + MOD(n, 28)) DAY + INTERVAL 1 DAY
    FROM t1_n WHERE n > 60;
    INSERT INTO work_order_logs(work_order_id, event_type, from_status, to_status, title, actor_user_id, created_at)
    SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 800000 + n, '"'))) AS UNSIGNED),
           'STATUS_CHANGED', 'AWAITING_ACCEPTANCE', 'COMPLETED', '验收通过', v_acceptor,
           v_now - INTERVAL (2 + MOD(n, 28)) DAY + INTERVAL 2 DAY
    FROM t1_n WHERE n > 90;

    INSERT INTO work_order_parts(work_order_id, spare_part_id, planned_quantity, issued_quantity, returned_quantity, created_at, updated_at)
    SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 800000 + n, '"'))) AS UNSIGNED),
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 700000 + n, '"'))) AS UNSIGNED),
           2, 2, IF(MOD(n, 4) = 0, 1, 0),
           v_now - INTERVAL (2 + MOD(n, 28)) DAY,
           v_now - INTERVAL (2 + MOD(n, 28)) DAY + INTERVAL 4 HOUR
    FROM t1_n WHERE n > 30;
    INSERT INTO work_order_reassignments(tenant_id, work_order_id, request_id, payload_hash, before_assignee_user_id,
                                         after_assignee_user_id, before_team_id, after_team_id, actor_user_id, reason,
                                         result_json, created_at)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 800000 + n, '"'))) AS UNSIGNED),
           UUID(), SHA2(CONCAT('ts1-reassign-', n), 256), v_admin,
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 100000 + MOD(n - 1, 40) + 1, '"'))) AS UNSIGNED),
           NULL, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 500000 + MOD(n - 1, 40) + 1, '"'))) AS UNSIGNED),
           v_admin, 'TS1 按技能调整负责人',
           JSON_OBJECT('id', CAST(CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 800000 + n, '"'))) AS UNSIGNED) AS CHAR), 'version', 1),
           v_now - INTERVAL (2 + MOD(n, 28)) DAY + INTERVAL 1 HOUR
    FROM t1_n;

    -- 库存：期初入库 500、工单领料 2、退料 1，调拨按状态同步出入库。
    INSERT INTO inventory_transactions(tenant_id, warehouse_id, spare_part_id, transaction_no, transaction_type, quantity,
                                       reference_type, created_by, request_id, created_at, note)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 400000 + n, '"'))) AS UNSIGNED),
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 700000 + n, '"'))) AS UNSIGNED),
           CONCAT('TS1-IN-', suffix), 'INBOUND', 500, 'MANUAL', v_admin, CONCAT('ts1-stock-', suffix), v_old, 'TS1 期初入库'
    FROM t1_n;
    SET v_n = 1;
    WHILE v_n <= 120 DO
        INSERT INTO inventory_transactions(tenant_id, warehouse_id, spare_part_id, transaction_no, transaction_type, quantity,
                                           reference_type, work_order_id, created_by, request_id, created_at, note)
        SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 400000 + n, '"'))) AS UNSIGNED),
               CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 700000 + n, '"'))) AS UNSIGNED),
               CONCAT('TS1-OUT-', suffix), 'OUTBOUND', 2, 'WORK_ORDER',
               CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 800000 + n, '"'))) AS UNSIGNED),
               v_technician, CONCAT('ts1-issue-', suffix), v_now - INTERVAL (2 + MOD(n, 28)) DAY + INTERVAL 4 HOUR, '工单领料'
        FROM t1_n WHERE n = v_n AND n > 30;
        IF ROW_COUNT() = 1 THEN
            SET v_ids = JSON_SET(v_ids, CONCAT('$."', 1300000 + v_n, '"'), LAST_INSERT_ID());
        END IF;
        SET v_n = v_n + 1;
    END WHILE;
    INSERT INTO inventory_transactions(tenant_id, warehouse_id, spare_part_id, transaction_no, transaction_type, quantity,
                                       reference_type, work_order_id, created_by, request_id, issue_transaction_id, created_at, note)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 400000 + n, '"'))) AS UNSIGNED),
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 700000 + n, '"'))) AS UNSIGNED),
           CONCAT('TS1-RET-', suffix), 'INBOUND', 1, 'WORK_ORDER',
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 800000 + n, '"'))) AS UNSIGNED),
           v_technician, CONCAT('ts1-return-', suffix),
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 1300000 + n, '"'))) AS UNSIGNED),
           v_now - INTERVAL (2 + MOD(n, 28)) DAY + INTERVAL 5 HOUR, '工单退料'
    FROM t1_n WHERE n > 30 AND MOD(n, 4) = 0;

    SET v_n = 1;
    WHILE v_n <= 120 DO
        INSERT INTO warehouse_transfers(tenant_id, transfer_no, source_warehouse_id, target_warehouse_id, spare_part_id,
                                        quantity, status, version, note, created_by, created_at)
        SELECT v_tenant, CONCAT('TS1-TR-', suffix),
               CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 400000 + n, '"'))) AS UNSIGNED),
               CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 400000 + MOD(n, 120) + 1, '"'))) AS UNSIGNED),
               CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 700000 + n, '"'))) AS UNSIGNED),
               5, ELT(1 + MOD(n - 1, 5), 'REQUESTED', 'IN_TRANSIT', 'RECEIVED', 'CANCELLED', 'EXCEPTION'),
               ELT(1 + MOD(n - 1, 5), 0, 1, 2, 1, 2), 'TS1 调拨样例', v_admin, v_old
        FROM t1_n WHERE n = v_n;
        SET v_ids = JSON_SET(v_ids, CONCAT('$."', 900000 + v_n, '"'), LAST_INSERT_ID());
        SET v_n = v_n + 1;
    END WHILE;
    INSERT INTO warehouse_transfer_operations(tenant_id, transfer_id, request_id, action, payload_hash, result_status,
                                              result_version, actor_user_id, reason, created_at)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 900000 + n, '"'))) AS UNSIGNED),
           CONCAT('ts1-tr-create-', suffix), 'CREATE', SHA2(CONCAT('ts1-tr-create-', n), 256),
           'REQUESTED', 0, v_admin, NULL, v_old
    FROM t1_n;
    INSERT INTO warehouse_transfer_operations(tenant_id, transfer_id, request_id, action, payload_hash, result_status,
                                              result_version, actor_user_id, reason, created_at)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 900000 + n, '"'))) AS UNSIGNED),
           CONCAT('ts1-tr-step-', suffix), IF(MOD(n - 1, 5) = 3, 'CANCEL', 'DISPATCH'),
           SHA2(CONCAT('ts1-tr-step-', n), 256), IF(MOD(n - 1, 5) = 3, 'CANCELLED', 'IN_TRANSIT'),
           1, v_admin, 'TS1 流转', v_old + INTERVAL 1 DAY
    FROM t1_n WHERE MOD(n - 1, 5) <> 0;
    INSERT INTO warehouse_transfer_operations(tenant_id, transfer_id, request_id, action, payload_hash, result_status,
                                              result_version, actor_user_id, reason, created_at)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 900000 + n, '"'))) AS UNSIGNED),
           CONCAT('ts1-tr-final-', suffix), IF(MOD(n - 1, 5) = 2, 'RECEIVE', 'EXCEPTION'),
           SHA2(CONCAT('ts1-tr-final-', n), 256), IF(MOD(n - 1, 5) = 2, 'RECEIVED', 'EXCEPTION'),
           2, v_admin, 'TS1 历史动作', v_old + INTERVAL 2 DAY
    FROM t1_n WHERE MOD(n - 1, 5) IN (2, 4);
    INSERT INTO inventory_transactions(tenant_id, warehouse_id, spare_part_id, transaction_no, transaction_type, quantity,
                                       reference_type, transfer_id, created_by, request_id, created_at, note)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 400000 + n, '"'))) AS UNSIGNED),
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 700000 + n, '"'))) AS UNSIGNED),
           CONCAT('TS1-TR-OUT-', suffix), 'OUTBOUND', 5, 'TRANSFER',
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 900000 + n, '"'))) AS UNSIGNED),
           v_admin, CONCAT('ts1-tr-out-', suffix), v_old + INTERVAL 1 DAY, '调拨出库'
    FROM t1_n WHERE MOD(n - 1, 5) = 2;
    INSERT INTO inventory_transactions(tenant_id, warehouse_id, spare_part_id, transaction_no, transaction_type, quantity,
                                       reference_type, transfer_id, created_by, request_id, created_at, note)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 400000 + MOD(n, 120) + 1, '"'))) AS UNSIGNED),
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 700000 + n, '"'))) AS UNSIGNED),
           CONCAT('TS1-TR-IN-', suffix), 'INBOUND', 5, 'TRANSFER',
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 900000 + n, '"'))) AS UNSIGNED),
           v_admin, CONCAT('ts1-tr-in-', suffix), v_old + INTERVAL 2 DAY, '调拨入库'
    FROM t1_n WHERE MOD(n - 1, 5) = 2;

    DELETE ws FROM warehouse_stock ws JOIN warehouses w ON w.id = ws.warehouse_id
    WHERE ws.tenant_id = v_tenant AND w.warehouse_code LIKE 'TS1-W-%';
    INSERT INTO warehouse_stock(tenant_id, warehouse_id, spare_part_id, quantity, min_stock_quantity)
    SELECT it.tenant_id, it.warehouse_id, it.spare_part_id,
           SUM(IF(it.transaction_type = 'OUTBOUND', -it.quantity, it.quantity)), 5
    FROM inventory_transactions it JOIN warehouses w ON w.id = it.warehouse_id
    WHERE it.tenant_id = v_tenant AND w.warehouse_code LIKE 'TS1-W-%'
    GROUP BY it.tenant_id, it.warehouse_id, it.spare_part_id;
    UPDATE spare_parts p JOIN (
        SELECT spare_part_id, SUM(quantity) AS total FROM warehouse_stock
        WHERE tenant_id = v_tenant GROUP BY spare_part_id
    ) s ON s.spare_part_id = p.id SET p.stock_quantity = s.total WHERE p.tenant_id = v_tenant;

    -- 120 条维护计划及对应执行记录。
    SET v_n = 1;
    WHILE v_n <= 120 DO
        INSERT INTO maintenance_plans(tenant_id, site_id, equipment_id, name, plan_type, content, cycle_days,
                                      next_due_date, assignee_user_id, enabled, created_by)
        SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300000 + n, '"'))) AS UNSIGNED),
               CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 600000 + n, '"'))) AS UNSIGNED),
               CONCAT('TS1 维护计划 ', suffix), IF(MOD(n, 2) = 0, 'INSPECTION', 'MAINTENANCE'),
               '检查振动、紧固件与备件状态。', 14, CURRENT_DATE + INTERVAL MOD(n, 14) - 7 DAY,
               CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 100000 + n, '"'))) AS UNSIGNED),
               IF(n = 120, 0, 1), v_admin
        FROM t1_n WHERE n = v_n;
        SET v_ids = JSON_SET(v_ids, CONCAT('$."', 1400000 + v_n, '"'), LAST_INSERT_ID());
        SET v_n = v_n + 1;
    END WHILE;
    INSERT INTO maintenance_executions(tenant_id, plan_id, site_id, equipment_id, request_id, plan_name, plan_type,
                                       plan_content, cycle_days, scheduled_date, executed_at, executed_by, result,
                                       next_due_date, equipment_asset_code, equipment_name, executed_by_name)
    SELECT v_tenant, p.id, p.site_id, p.equipment_id, CONCAT('ts1-maint-', LPAD(p.id, 6, '0')), p.name, p.plan_type,
           p.content, p.cycle_days, p.next_due_date - INTERVAL 7 DAY,
           TIMESTAMP(p.next_due_date - INTERVAL 7 DAY, '10:00:00'), p.assignee_user_id,
           '已完成现场检查，结果正常。', p.next_due_date, e.asset_code, e.name, u.display_name
    FROM maintenance_plans p
    JOIN equipment e ON e.id = p.equipment_id
    JOIN users u ON u.id = p.assignee_user_id
    WHERE p.tenant_id = v_tenant AND p.name LIKE 'TS1 维护计划 %';

    -- 库存导出任务：120 条失败历史 + 120 条取消/过期历史，并绑定厂区。
    SET v_n = 1;
    WHILE v_n <= 120 DO
        INSERT INTO inventory_export_tasks(tenant_id, created_by, warehouse_id, status, processed_rows, error_code,
                                           error_message, owner_instance_id, run_token, lease_until, created_at,
                                           started_at, finished_at, deadline_at)
        SELECT v_tenant, v_admin, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 400000 + n, '"'))) AS UNSIGNED),
               'FAILED', 0, IF(n = 119, 'QUERY_FAILED', 'IO_FAILED'), 'TS1 历史写文件失败样例',
               'ts1-fixture', UUID(), fault_at, fault_at - INTERVAL 1 MINUTE, fault_at - INTERVAL 30 SECOND,
               fault_at, fault_at + INTERVAL 2 MINUTE
        FROM t1_n WHERE n = v_n;
        SET v_ids = JSON_SET(v_ids, CONCAT('$."', 1000000 + v_n, '"'), LAST_INSERT_ID());
        SET v_n = v_n + 1;
    END WHILE;
    SET v_n = 1;
    WHILE v_n <= 120 DO
        INSERT INTO inventory_export_tasks(tenant_id, created_by, warehouse_id, status, processed_rows,
                                           owner_instance_id, run_token, lease_until, created_at, finished_at, deadline_at)
        SELECT v_tenant, v_admin, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 400000 + n, '"'))) AS UNSIGNED),
               IF(MOD(n, 2) = 0, 'CANCELLED', 'EXPIRED'), 0, 'ts1-fixture', UUID(), fault_at,
               fault_at - INTERVAL 1 MINUTE, fault_at, fault_at + INTERVAL 2 MINUTE
        FROM t1_n WHERE n = v_n;
        SET v_ids = JSON_SET(v_ids, CONCAT('$."', 1100000 + v_n, '"'), LAST_INSERT_ID());
        SET v_n = v_n + 1;
    END WHILE;
    INSERT INTO inventory_export_task_sites(task_id, tenant_id, site_id)
    SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 1000000 + n, '"'))) AS UNSIGNED), v_tenant,
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300000 + n, '"'))) AS UNSIGNED)
    FROM t1_n;
    INSERT INTO inventory_export_task_sites(task_id, tenant_id, site_id)
    SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 1100000 + n, '"'))) AS UNSIGNED), v_tenant,
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300000 + n, '"'))) AS UNSIGNED)
    FROM t1_n;

    -- 导出故障告警：1 条规则 + 120 个事件 + 来源/处置/通知。
    INSERT INTO alert_rules(tenant_id, rule_code, enabled, threshold_count, window_seconds, cooldown_seconds, owner_user_id)
    VALUES (v_tenant, 'EXPORT_TASK_SYSTEM_FAILURE', 1, 1, 300, 60, v_admin);
    SET v_rule = LAST_INSERT_ID();
    INSERT INTO alert_scan_progress(tenant_id, rule_id, rule_version, window_start, window_end, completed)
    VALUES (v_tenant, v_rule, 0, v_old, v_now, 1);
    SET v_n = 1;
    WHILE v_n <= 120 DO
        INSERT INTO alert_events(tenant_id, rule_id, fingerprint, error_code, status, recovery_status, open_slot,
                                 occurrence_count, first_task_id, latest_task_id, owner_user_id, first_seen_at, last_seen_at,
                                 acknowledged_at, recovered_at, resolved_at, version, created_at, updated_at)
        SELECT v_tenant, v_rule, CONCAT('TS1-ALERT-', suffix), IF(n = 119, 'QUERY_FAILED', 'IO_FAILED'),
               CASE n WHEN 119 THEN 'ACKNOWLEDGED' WHEN 120 THEN 'OPEN' ELSE 'RESOLVED' END,
               IF(n = 120, 'UNVERIFIED', 'RECOVERED'), IF(n >= 119, 1, NULL), 1,
               CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 1000000 + n, '"'))) AS UNSIGNED),
               CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 1000000 + n, '"'))) AS UNSIGNED),
               v_admin, fault_at, fault_at,
               IF(n = 120, NULL, fault_at + INTERVAL 5 MINUTE),
               IF(n = 120, NULL, fault_at + INTERVAL 10 MINUTE),
               IF(n >= 119, NULL, fault_at + INTERVAL 15 MINUTE),
               IF(n >= 119, 1, 2), fault_at, fault_at + INTERVAL 15 MINUTE
        FROM t1_n WHERE n = v_n;
        SET v_ids = JSON_SET(v_ids, CONCAT('$."', 1200000 + v_n, '"'), LAST_INSERT_ID());
        SET v_n = v_n + 1;
    END WHILE;
    INSERT INTO alert_event_sources(tenant_id, rule_id, event_id, task_id, error_code, task_finished_at)
    SELECT v_tenant, v_rule, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 1200000 + n, '"'))) AS UNSIGNED),
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 1000000 + n, '"'))) AS UNSIGNED),
           IF(n = 119, 'QUERY_FAILED', 'IO_FAILED'), fault_at
    FROM t1_n;
    INSERT INTO alert_actions(tenant_id, event_id, actor_user_id, action_type, note, request_id, created_at)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 1200000 + n, '"'))) AS UNSIGNED),
           v_admin, 'ACKNOWLEDGE', 'TS1 历史接手', UUID(), fault_at + INTERVAL 5 MINUTE
    FROM t1_n WHERE n < 120;
    INSERT INTO alert_actions(tenant_id, event_id, actor_user_id, action_type, note, request_id, created_at)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 1200000 + n, '"'))) AS UNSIGNED),
           v_admin, 'RESOLVE', 'TS1 历史恢复结案', UUID(), fault_at + INTERVAL 15 MINUTE
    FROM t1_n WHERE n < 119;
    INSERT INTO alert_notifications(tenant_id, event_id, recipient_user_id, delivery_key, status, title, message,
                                    attempts, delivered_at, read_at)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 1200000 + n, '"'))) AS UNSIGNED),
           v_admin, CONCAT('TS1-DELIVERY-', suffix), 'DELIVERED', '库存导出故障', 'TS1 历史测试事件',
           1, fault_at, IF(n < 119, fault_at + INTERVAL 5 MINUTE, NULL)
    FROM t1_n;

    -- 审计：120 条工单改派 + 120 条厂区创建。
    INSERT INTO audit_events(tenant_id, actor_user_id, actor_type, action, object_type, object_id, outcome,
                             request_id, changes_json, occurred_at)
    SELECT v_tenant, v_admin, 'USER', 'WORK_ORDER_REASSIGNED', 'WORK_ORDER',
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 800000 + n, '"'))) AS UNSIGNED), 'SUCCESS', UUID(),
           JSON_OBJECT('version', 1), v_now - INTERVAL (2 + MOD(n, 28)) DAY + INTERVAL 1 HOUR
    FROM t1_n;
    INSERT INTO audit_events(tenant_id, actor_user_id, actor_type, action, object_type, object_id, outcome,
                             request_id, changes_json, occurred_at)
    SELECT v_tenant, v_admin, 'USER', 'SITE_CREATED', 'SITE',
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300000 + n, '"'))) AS UNSIGNED), 'SUCCESS', UUID(),
           JSON_OBJECT('enabled', TRUE), v_old
    FROM t1_n;

    -- 数量校验：核心业务表必须达到 120 条，配置类至少 1 条。
    CREATE TEMPORARY TABLE t1_counts (
        record_type VARCHAR(64) PRIMARY KEY,
        row_count INT NOT NULL,
        minimum_rows INT NOT NULL
    ) ENGINE = InnoDB;
    INSERT INTO t1_counts
    SELECT 'users', COUNT(*), 120 FROM users WHERE account LIKE 'ts1_user_%'
    UNION ALL SELECT 'tenant_memberships', COUNT(*), 120 FROM tenant_memberships WHERE tenant_id = v_tenant AND user_id IN (SELECT id FROM users WHERE account LIKE 'ts1_user_%')
    UNION ALL SELECT 'tenant_member_roles', COUNT(*), 100 FROM tenant_member_roles WHERE tenant_id = v_tenant AND membership_id IN (SELECT id FROM tenant_memberships WHERE tenant_id = v_tenant AND user_id IN (SELECT id FROM users WHERE account LIKE 'ts1_user_%'))
    UNION ALL SELECT 'sites', COUNT(*), 120 FROM sites WHERE tenant_id = v_tenant AND site_code LIKE 'TS1-S-%'
    UNION ALL SELECT 'warehouses', COUNT(*), 120 FROM warehouses WHERE tenant_id = v_tenant AND warehouse_code LIKE 'TS1-W-%'
    UNION ALL SELECT 'teams', COUNT(*), 120 FROM teams WHERE tenant_id = v_tenant AND team_code LIKE 'TS1-T-%'
    UNION ALL SELECT 'team_members', COUNT(*), 120 FROM team_members WHERE tenant_id = v_tenant
    UNION ALL SELECT 'team_site_scopes', COUNT(*), 120 FROM team_site_scopes WHERE tenant_id = v_tenant
    UNION ALL SELECT 'team_operations', COUNT(*), 120 FROM team_operations WHERE tenant_id = v_tenant
    UNION ALL SELECT 'equipment', COUNT(*), 120 FROM equipment WHERE tenant_id = v_tenant AND asset_code LIKE 'TS1-EQ-%'
    UNION ALL SELECT 'spare_parts', COUNT(*), 120 FROM spare_parts WHERE tenant_id = v_tenant AND part_code LIKE 'TS1-P-%'
    UNION ALL SELECT 'equipment_spare_parts', COUNT(*), 120 FROM equipment_spare_parts WHERE tenant_id = v_tenant
    UNION ALL SELECT 'work_orders', COUNT(*), 120 FROM work_orders WHERE tenant_id = v_tenant AND order_no LIKE 'TS1-WO-%'
    UNION ALL SELECT 'work_order_logs', COUNT(*), 100 FROM work_order_logs WHERE work_order_id IN (SELECT id FROM work_orders WHERE tenant_id = v_tenant AND order_no LIKE 'TS1-WO-%')
    UNION ALL SELECT 'work_order_parts', COUNT(*), 80 FROM work_order_parts WHERE work_order_id IN (SELECT id FROM work_orders WHERE tenant_id = v_tenant AND order_no LIKE 'TS1-WO-%')
    UNION ALL SELECT 'work_order_reassignments', COUNT(*), 100 FROM work_order_reassignments WHERE tenant_id = v_tenant
    UNION ALL SELECT 'maintenance_plans', COUNT(*), 120 FROM maintenance_plans WHERE tenant_id = v_tenant AND name LIKE 'TS1 维护计划 %'
    UNION ALL SELECT 'maintenance_executions', COUNT(*), 100 FROM maintenance_executions WHERE tenant_id = v_tenant
    UNION ALL SELECT 'warehouse_transfers', COUNT(*), 120 FROM warehouse_transfers WHERE tenant_id = v_tenant AND transfer_no LIKE 'TS1-TR-%'
    UNION ALL SELECT 'warehouse_transfer_operations', COUNT(*), 200 FROM warehouse_transfer_operations WHERE tenant_id = v_tenant
    UNION ALL SELECT 'inventory_transactions', COUNT(*), 250 FROM inventory_transactions WHERE tenant_id = v_tenant
    UNION ALL SELECT 'warehouse_stock', COUNT(*), 100 FROM warehouse_stock WHERE tenant_id = v_tenant
    UNION ALL SELECT 'inventory_export_tasks', COUNT(*), 200 FROM inventory_export_tasks WHERE tenant_id = v_tenant AND owner_instance_id = 'ts1-fixture'
    UNION ALL SELECT 'inventory_export_task_sites', COUNT(*), 200 FROM inventory_export_task_sites WHERE tenant_id = v_tenant
    UNION ALL SELECT 'alert_rules', COUNT(*), 1 FROM alert_rules WHERE tenant_id = v_tenant AND rule_code = 'EXPORT_TASK_SYSTEM_FAILURE'
    UNION ALL SELECT 'alert_scan_progress', COUNT(*), 1 FROM alert_scan_progress WHERE tenant_id = v_tenant
    UNION ALL SELECT 'alert_events', COUNT(*), 120 FROM alert_events WHERE tenant_id = v_tenant AND fingerprint LIKE 'TS1-ALERT-%'
    UNION ALL SELECT 'alert_event_sources', COUNT(*), 100 FROM alert_event_sources WHERE tenant_id = v_tenant
    UNION ALL SELECT 'alert_actions', COUNT(*), 200 FROM alert_actions WHERE tenant_id = v_tenant
    UNION ALL SELECT 'alert_notifications', COUNT(*), 100 FROM alert_notifications WHERE tenant_id = v_tenant
    UNION ALL SELECT 'audit_events', COUNT(*), 200 FROM audit_events WHERE tenant_id = v_tenant;
    IF EXISTS (SELECT 1 FROM t1_counts WHERE row_count < minimum_rows) THEN
        SELECT * FROM t1_counts WHERE row_count < minimum_rows;
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'TS1 fixture count below minimum';
    END IF;

    COMMIT;
    SELECT v_tenant AS tenant_id, 'TEST_NO_1' AS tenant_code, 'ts1_user_001' AS sample_member;
    SELECT record_type, row_count, minimum_rows FROM t1_counts ORDER BY record_type;
    DROP TEMPORARY TABLE t1_counts;
    DROP TEMPORARY TABLE t1_n;
END$$
DELIMITER ;
CALL seed_equipops_test_no1_demo_120();
DROP PROCEDURE seed_equipops_test_no1_demo_120;
