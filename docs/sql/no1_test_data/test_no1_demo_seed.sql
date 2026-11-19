-- 为前端“测试企业 No.1”（tenant_code = TEST_NO_1）补齐各功能演示数据。
-- 仅追加演示数据，不修改账号、厂区、仓库等既有配置；重复执行会中止并提示。
-- 在 equipops_v2 库执行：mysql -uroot equipops_v2 < test_no1_demo_seed.sql
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS seed_equipops_test_no1_demo;
DELIMITER $$
CREATE PROCEDURE seed_equipops_test_no1_demo()
SQL SECURITY INVOKER
BEGIN
    DECLARE v_tenant BIGINT UNSIGNED;
    DECLARE v_admin BIGINT UNSIGNED;
    DECLARE v_reporter BIGINT UNSIGNED;
    DECLARE v_technician BIGINT UNSIGNED;
    DECLARE v_acceptor BIGINT UNSIGNED;
    DECLARE v_site BIGINT UNSIGNED;
    DECLARE v_wh1 BIGINT UNSIGNED;
    DECLARE v_wh2 BIGINT UNSIGNED;
    DECLARE v_team BIGINT UNSIGNED;
    DECLARE v_n INT;
    DECLARE v_now DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3);
    DECLARE v_ids JSON DEFAULT (JSON_OBJECT());
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        DROP TEMPORARY TABLE IF EXISTS t1_n;
        RESIGNAL;
    END;

    SELECT id INTO v_tenant FROM tenants WHERE tenant_code = 'TEST_NO_1';
    IF v_tenant IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'tenant TEST_NO_1 not found';
    END IF;
    IF EXISTS (SELECT 1 FROM equipment WHERE tenant_id = v_tenant AND asset_code LIKE 'T1-EQ-%') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'TEST_NO_1 demo data already exists; nothing changed';
    END IF;
    SELECT id INTO v_admin FROM users WHERE account = 'admin';
    SELECT id INTO v_reporter FROM users WHERE account = 'reporter';
    SELECT id INTO v_technician FROM users WHERE account = 'technician';
    SELECT id INTO v_acceptor FROM users WHERE account = 'acceptor';
    IF v_admin IS NULL OR v_technician IS NULL OR v_acceptor IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'required demo users are missing';
    END IF;
    SELECT id INTO v_site FROM sites WHERE tenant_id = v_tenant AND is_default = 1 LIMIT 1;
    SELECT id INTO v_wh1 FROM warehouses WHERE tenant_id = v_tenant AND is_default = 1 LIMIT 1;
    IF v_site IS NULL OR v_wh1 IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'default site or warehouse is missing';
    END IF;

    START TRANSACTION;
    -- 第二个仓库与班组，用于调拨示例和工单归属。
    INSERT INTO warehouses(tenant_id, site_id, warehouse_code, name, location, enabled, is_default)
    SELECT v_tenant, v_site, 'T1-W-02', '二号备件仓库', '二号库房', 1, 0
    WHERE NOT EXISTS (SELECT 1 FROM warehouses WHERE tenant_id = v_tenant AND warehouse_code = 'T1-W-02');
    SELECT id INTO v_wh2 FROM warehouses WHERE tenant_id = v_tenant AND warehouse_code = 'T1-W-02';

    INSERT INTO teams(tenant_id, team_code, name, enabled, version, created_by)
    SELECT v_tenant, 'T1-T-01', '演示维修班组', 1, 1, v_admin
    WHERE NOT EXISTS (SELECT 1 FROM teams WHERE tenant_id = v_tenant AND team_code = 'T1-T-01');
    SELECT id INTO v_team FROM teams WHERE tenant_id = v_tenant AND team_code = 'T1-T-01';
    INSERT INTO team_members(tenant_id, team_id, membership_id)
    SELECT v_tenant, v_team, m.id FROM tenant_memberships m
    WHERE m.tenant_id = v_tenant AND m.user_id IN (v_reporter, v_technician, v_acceptor)
      AND NOT EXISTS (SELECT 1 FROM team_members tm WHERE tm.team_id = v_team AND tm.membership_id = m.id);
    INSERT INTO team_site_scopes(tenant_id, team_id, site_id)
    SELECT v_tenant, v_team, v_site
    WHERE NOT EXISTS (SELECT 1 FROM team_site_scopes WHERE team_id = v_team AND site_id = v_site);

    CREATE TEMPORARY TABLE t1_n (
        n INT PRIMARY KEY,
        suffix CHAR(3) NOT NULL,
        runtime_hours DECIMAL(14,2) NOT NULL
    ) ENGINE = InnoDB;
    INSERT INTO t1_n
    WITH RECURSIVE series AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM series WHERE n < 24)
    SELECT n, LPAD(n, 3, '0'), 120 + n * 7 FROM series;

    -- 设备档案：运行/待关注/停机三种状态。
    SET v_n = 1;
    WHILE v_n <= 8 DO
        INSERT INTO equipment(tenant_id, site_id, asset_code, name, model, location, category, status, health,
                              next_maintenance_at, runtime_hours, installed_at, manager_user_id, created_at, updated_at)
        SELECT v_tenant, v_site, CONCAT('T1-EQ-', suffix), CONCAT('演示设备 ', suffix), CONCAT('EQ-', 1 + MOD(n, 4)),
               CONCAT('一号车间 ', suffix), '联调设备',
               CASE WHEN n <= 4 THEN 'RUNNING' WHEN n <= 6 THEN 'NEEDS_ATTENTION' ELSE 'STOPPED' END,
               ELT(n, 92, 88, 85, 90, 55, 45, 20, 30),
               CURRENT_DATE + INTERVAL MOD(n, 10) - 3 DAY, runtime_hours, CURRENT_DATE - INTERVAL 300 DAY,
               v_technician, v_now - INTERVAL 30 DAY, v_now - INTERVAL 2 DAY
        FROM t1_n WHERE n = v_n;
        SET v_ids = JSON_SET(v_ids, CONCAT('$."', 100 + v_n, '"'), LAST_INSERT_ID());
        SET v_n = v_n + 1;
    END WHILE;

    -- 备件：库存由下面的入库、领料、退料流水汇总，保证流水与库存一致。
    SET v_n = 1;
    WHILE v_n <= 6 DO
        INSERT INTO spare_parts(tenant_id, part_code, name, spec, category, location, stock_quantity, min_stock_quantity, unit, enabled)
        SELECT v_tenant, CONCAT('T1-P-', suffix), CONCAT('演示备件 ', suffix), CONCAT('型号 ', suffix),
               '机修耗材', '一号库位', 0, 5, '件', 1
        FROM t1_n WHERE n = v_n;
        SET v_ids = JSON_SET(v_ids, CONCAT('$."', 200 + v_n, '"'), LAST_INSERT_ID());
        SET v_n = v_n + 1;
    END WHILE;
    INSERT INTO equipment_spare_parts(tenant_id, equipment_id, spare_part_id, created_by)
    SELECT v_tenant,
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 100 + n, '"'))) AS UNSIGNED),
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 200 + MOD(n, 6) + 1, '"'))) AS UNSIGNED),
           v_admin
    FROM t1_n WHERE n <= 8;

    -- 工单：待派单 6、维修中 6、待验收 6、已完成 6，覆盖统计与看板。
    SET v_n = 1;
    WHILE v_n <= 24 DO
        INSERT INTO work_orders(tenant_id, site_id, order_no, title, equipment_id, description, priority, status,
                                assignee_user_id, due_at, completed_at, created_by, created_at, updated_at, team_id, version)
        SELECT v_tenant, v_site, CONCAT('T1-WO-', suffix), CONCAT('演示维修工单 ', suffix),
               CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 100 + (MOD(n - 1, 8) + 1), '"'))) AS UNSIGNED),
               '检查设备运行状态、紧固件与备件消耗。',
               ELT(1 + MOD(n, 3), 'NORMAL', 'HIGH', 'URGENT'),
               CASE WHEN n <= 6 THEN 'PENDING' WHEN n <= 12 THEN 'IN_PROGRESS'
                    WHEN n <= 18 THEN 'AWAITING_ACCEPTANCE' ELSE 'COMPLETED' END,
               IF(n <= 3, NULL, v_technician),
               v_now - INTERVAL (25 - n) DAY + INTERVAL 3 DAY,
               IF(n > 18, v_now - INTERVAL (25 - n) DAY + INTERVAL 2 DAY, NULL),
               v_admin, v_now - INTERVAL (25 - n) DAY, v_now - INTERVAL (25 - n) DAY + INTERVAL 2 HOUR,
               v_team, IF(n > 18, 2, 1)
        FROM t1_n WHERE n = v_n;
        SET v_ids = JSON_SET(v_ids, CONCAT('$."', 300 + v_n, '"'), LAST_INSERT_ID());
        SET v_n = v_n + 1;
    END WHILE;

    -- 工单时间线：创建、派单、开工、提交验收、验收完成。
    INSERT INTO work_order_logs(work_order_id, event_type, from_status, to_status, title, detail, actor_user_id, created_at)
    SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300 + n, '"'))) AS UNSIGNED),
           'CREATED', NULL, 'PENDING', '提交报修', '演示测试记录', v_reporter, v_now - INTERVAL (25 - n) DAY
    FROM t1_n;
    INSERT INTO work_order_logs(work_order_id, event_type, from_status, to_status, title, detail, actor_user_id, created_at)
    SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300 + n, '"'))) AS UNSIGNED),
           'ASSIGNED', 'PENDING', 'PENDING', '指派维修人', '按班组技能派单', v_admin, v_now - INTERVAL (25 - n) DAY + INTERVAL 1 HOUR
    FROM t1_n WHERE n BETWEEN 4 AND 6;
    INSERT INTO work_order_logs(work_order_id, event_type, from_status, to_status, title, actor_user_id, created_at)
    SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300 + n, '"'))) AS UNSIGNED),
           'STATUS_CHANGED', 'PENDING', 'IN_PROGRESS', '开始维修', v_technician, v_now - INTERVAL (25 - n) DAY + INTERVAL 3 HOUR
    FROM t1_n WHERE n > 6;
    INSERT INTO work_order_logs(work_order_id, event_type, from_status, to_status, title, actor_user_id, created_at)
    SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300 + n, '"'))) AS UNSIGNED),
           'STATUS_CHANGED', 'IN_PROGRESS', 'AWAITING_ACCEPTANCE', '提交验收', v_technician, v_now - INTERVAL (25 - n) DAY + INTERVAL 1 DAY
    FROM t1_n WHERE n > 12;
    INSERT INTO work_order_logs(work_order_id, event_type, from_status, to_status, title, actor_user_id, created_at)
    SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300 + n, '"'))) AS UNSIGNED),
           'STATUS_CHANGED', 'AWAITING_ACCEPTANCE', 'COMPLETED', '验收通过', v_acceptor, v_now - INTERVAL (25 - n) DAY + INTERVAL 2 DAY
    FROM t1_n WHERE n > 18;

    -- 工单备件用量与领退料流水。
    INSERT INTO work_order_parts(work_order_id, spare_part_id, planned_quantity, issued_quantity, returned_quantity, created_at, updated_at)
    SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300 + n, '"'))) AS UNSIGNED),
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 200 + MOD(n - 1, 6) + 1, '"'))) AS UNSIGNED),
           2, 2, IF(MOD(n, 4) = 0, 1, 0),
           v_now - INTERVAL (25 - n) DAY, v_now - INTERVAL (25 - n) DAY + INTERVAL 4 HOUR
    FROM t1_n WHERE n > 6;

    INSERT INTO inventory_transactions(tenant_id, warehouse_id, spare_part_id, transaction_no, transaction_type, quantity,
                                       reference_type, created_by, request_id, created_at, note)
    SELECT v_tenant, v_wh1, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 200 + n, '"'))) AS UNSIGNED),
           CONCAT('T1-IN-', suffix), 'INBOUND', 50, 'MANUAL', v_admin, CONCAT('t1-stock-', suffix),
           v_now - INTERVAL 30 DAY, '演示期初入库'
    FROM t1_n WHERE n <= 6;
    SET v_n = 1;
    WHILE v_n <= 24 DO
        INSERT INTO inventory_transactions(tenant_id, warehouse_id, spare_part_id, transaction_no, transaction_type, quantity,
                                           reference_type, work_order_id, created_by, request_id, created_at, note)
        SELECT v_tenant, v_wh1, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 200 + MOD(n - 1, 6) + 1, '"'))) AS UNSIGNED),
               CONCAT('T1-OUT-', suffix), 'OUTBOUND', 2, 'WORK_ORDER',
               CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300 + n, '"'))) AS UNSIGNED),
               v_technician, CONCAT('t1-issue-', suffix), v_now - INTERVAL (25 - n) DAY + INTERVAL 4 HOUR, '工单领料'
        FROM t1_n WHERE n = v_n AND n > 6;
        SET v_ids = JSON_SET(v_ids, CONCAT('$."', 400 + v_n, '"'), LAST_INSERT_ID());
        SET v_n = v_n + 1;
    END WHILE;
    INSERT INTO inventory_transactions(tenant_id, warehouse_id, spare_part_id, transaction_no, transaction_type, quantity,
                                       reference_type, work_order_id, created_by, request_id, issue_transaction_id, created_at, note)
    SELECT v_tenant, v_wh1, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 200 + MOD(n - 1, 6) + 1, '"'))) AS UNSIGNED),
           CONCAT('T1-RET-', suffix), 'INBOUND', 1, 'WORK_ORDER',
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300 + n, '"'))) AS UNSIGNED),
           v_technician, CONCAT('t1-return-', suffix),
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 400 + n, '"'))) AS UNSIGNED),
           v_now - INTERVAL (25 - n) DAY + INTERVAL 5 HOUR, '工单退料'
    FROM t1_n WHERE MOD(n, 4) = 0 AND n > 6;

    -- 调拨：待发/在途/已收货三种状态，已收货的同步产生出库与入库流水。
    INSERT INTO warehouse_transfers(tenant_id, transfer_no, source_warehouse_id, target_warehouse_id, spare_part_id,
                                    quantity, status, version, note, created_by, created_at)
    SELECT v_tenant, CONCAT('T1-TR-', suffix), v_wh1, v_wh2,
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 200 + n, '"'))) AS UNSIGNED),
           5, ELT(n, 'REQUESTED', 'IN_TRANSIT', 'RECEIVED'), ELT(n, 0, 1, 2), '演示调拨', v_admin,
           v_now - INTERVAL (4 - n) DAY
    FROM t1_n WHERE n <= 3;
    SET v_n = 1;
    WHILE v_n <= 3 DO
        SET v_ids = JSON_SET(v_ids, CONCAT('$."', 500 + v_n, '"'),
            (SELECT id FROM warehouse_transfers WHERE tenant_id = v_tenant AND transfer_no = CONCAT('T1-TR-00', v_n)));
        SET v_n = v_n + 1;
    END WHILE;
    INSERT INTO warehouse_transfer_operations(tenant_id, transfer_id, request_id, action, payload_hash, result_status,
                                              result_version, actor_user_id, reason, created_at)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 500 + n, '"'))) AS UNSIGNED),
           CONCAT('t1-tr-create-', suffix), 'CREATE', SHA2(CONCAT('t1-tr-create-', n), 256),
           'REQUESTED', 0, v_admin, NULL, v_now - INTERVAL (4 - n) DAY
    FROM t1_n WHERE n <= 3;
    INSERT INTO warehouse_transfer_operations(tenant_id, transfer_id, request_id, action, payload_hash, result_status,
                                              result_version, actor_user_id, reason, created_at)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 500 + n, '"'))) AS UNSIGNED),
           CONCAT('t1-tr-step-', suffix), IF(n = 3, 'RECEIVE', 'DISPATCH'), SHA2(CONCAT('t1-tr-step-', n), 256),
           IF(n = 3, 'RECEIVED', 'IN_TRANSIT'), IF(n = 3, 2, 1), v_admin, '演示流转', v_now - INTERVAL (4 - n) DAY + INTERVAL 6 HOUR
    FROM t1_n WHERE n IN (2, 3);
    INSERT INTO inventory_transactions(tenant_id, warehouse_id, spare_part_id, transaction_no, transaction_type, quantity,
                                       reference_type, transfer_id, created_by, request_id, created_at, note)
    SELECT v_tenant, v_wh1, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 200 + n, '"'))) AS UNSIGNED),
           CONCAT('T1-TR-OUT-', suffix), 'OUTBOUND', 5, 'TRANSFER',
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 500 + n, '"'))) AS UNSIGNED),
           v_admin, CONCAT('t1-tr-out-', suffix), v_now - INTERVAL (4 - n) DAY + INTERVAL 6 HOUR, '调拨出库'
    FROM t1_n WHERE n = 3;
    INSERT INTO inventory_transactions(tenant_id, warehouse_id, spare_part_id, transaction_no, transaction_type, quantity,
                                       reference_type, transfer_id, created_by, request_id, created_at, note)
    SELECT v_tenant, v_wh2, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 200 + n, '"'))) AS UNSIGNED),
           CONCAT('T1-TR-IN-', suffix), 'INBOUND', 5, 'TRANSFER',
           CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 500 + n, '"'))) AS UNSIGNED),
           v_admin, CONCAT('t1-tr-in-', suffix), v_now - INTERVAL (4 - n) DAY + INTERVAL 8 HOUR, '调拨入库'
    FROM t1_n WHERE n = 3;

    -- 仓库库存按流水汇总，备件总库存同步更新。
    DELETE FROM warehouse_stock WHERE tenant_id = v_tenant AND warehouse_id IN (v_wh1, v_wh2);
    INSERT INTO warehouse_stock(tenant_id, warehouse_id, spare_part_id, quantity, min_stock_quantity)
    SELECT tenant_id, warehouse_id, spare_part_id, SUM(IF(transaction_type = 'OUTBOUND', -quantity, quantity)), 5
    FROM inventory_transactions WHERE tenant_id = v_tenant GROUP BY tenant_id, warehouse_id, spare_part_id;
    UPDATE spare_parts p JOIN (
        SELECT spare_part_id, SUM(quantity) AS total FROM warehouse_stock
        WHERE tenant_id = v_tenant GROUP BY spare_part_id
    ) s ON s.spare_part_id = p.id SET p.stock_quantity = s.total WHERE p.tenant_id = v_tenant;

    -- 改派记录：让工单详情能看到责任交接。
    INSERT INTO work_order_reassignments(tenant_id, work_order_id, request_id, payload_hash, before_assignee_user_id,
                                         after_assignee_user_id, before_team_id, after_team_id, actor_user_id, reason,
                                         result_json, created_at)
    SELECT v_tenant, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300 + n, '"'))) AS UNSIGNED),
           UUID(), SHA2(CONCAT('t1-reassign-', n), 256), v_admin, v_technician, NULL, v_team, v_admin,
           '按技能调整维修负责人',
           JSON_OBJECT('id', CAST(CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 300 + n, '"'))) AS UNSIGNED) AS CHAR), 'version', 2),
           v_now - INTERVAL (25 - n) DAY + INTERVAL 1 HOUR
    FROM t1_n WHERE n IN (7, 9);

    -- 维修计划与已执行记录。
    INSERT INTO maintenance_plans(tenant_id, site_id, equipment_id, name, plan_type, content, cycle_days,
                                  next_due_date, assignee_user_id, enabled, created_by)
    SELECT v_tenant, v_site, CAST(JSON_UNQUOTE(JSON_EXTRACT(v_ids, CONCAT('$."', 100 + n, '"'))) AS UNSIGNED),
           CONCAT('演示维护计划 ', suffix), IF(MOD(n, 2) = 0, 'INSPECTION', 'MAINTENANCE'),
           '检查振动、紧固件与备件状态。', 14, CURRENT_DATE + INTERVAL n - 1 DAY, v_technician, 1, v_admin
    FROM t1_n WHERE n <= 4;
    INSERT INTO maintenance_executions(tenant_id, plan_id, site_id, equipment_id, request_id, plan_name, plan_type,
                                       plan_content, cycle_days, scheduled_date, executed_at, executed_by, result,
                                       next_due_date, equipment_asset_code, equipment_name, executed_by_name)
    SELECT v_tenant, p.id, p.site_id, p.equipment_id, CONCAT('t1-maint-', LPAD(p.id, 3, '0')), p.name, p.plan_type,
           p.content, p.cycle_days, p.next_due_date - INTERVAL 14 DAY,
           TIMESTAMP(p.next_due_date - INTERVAL 14 DAY, '10:00:00'), p.assignee_user_id,
           '已完成现场检查，运行正常。', p.next_due_date, e.asset_code, e.name, u.display_name
    FROM maintenance_plans p
    JOIN equipment e ON e.id = p.equipment_id
    JOIN users u ON u.id = p.assignee_user_id
    WHERE p.tenant_id = v_tenant AND p.name LIKE '演示维护计划 %';

    COMMIT;
    SELECT v_tenant AS tenant_id, 'TEST_NO_1' AS tenant_code;
    SELECT 'equipment' AS record_type, COUNT(*) AS row_count FROM equipment WHERE tenant_id = v_tenant
    UNION ALL SELECT 'spare_parts', COUNT(*) FROM spare_parts WHERE tenant_id = v_tenant
    UNION ALL SELECT 'work_orders', COUNT(*) FROM work_orders WHERE tenant_id = v_tenant
    UNION ALL SELECT 'work_order_logs', COUNT(*) FROM work_order_logs WHERE work_order_id IN (SELECT id FROM work_orders WHERE tenant_id = v_tenant)
    UNION ALL SELECT 'work_order_parts', COUNT(*) FROM work_order_parts WHERE work_order_id IN (SELECT id FROM work_orders WHERE tenant_id = v_tenant)
    UNION ALL SELECT 'work_order_reassignments', COUNT(*) FROM work_order_reassignments WHERE tenant_id = v_tenant
    UNION ALL SELECT 'maintenance_plans', COUNT(*) FROM maintenance_plans WHERE tenant_id = v_tenant
    UNION ALL SELECT 'maintenance_executions', COUNT(*) FROM maintenance_executions WHERE tenant_id = v_tenant
    UNION ALL SELECT 'inventory_transactions', COUNT(*) FROM inventory_transactions WHERE tenant_id = v_tenant
    UNION ALL SELECT 'warehouse_stock', COUNT(*) FROM warehouse_stock WHERE tenant_id = v_tenant
    UNION ALL SELECT 'warehouse_transfers', COUNT(*) FROM warehouse_transfers WHERE tenant_id = v_tenant
    UNION ALL SELECT 'warehouse_transfer_operations', COUNT(*) FROM warehouse_transfer_operations WHERE tenant_id = v_tenant;
    DROP TEMPORARY TABLE t1_n;
END$$
DELIMITER ;
CALL seed_equipops_test_no1_demo();
DROP PROCEDURE seed_equipops_test_no1_demo;
