-- 入库幂等配套：旧流水保留 NULL，不猜测历史请求号，不修改已执行的历史迁移。
-- 新请求号只允许 1..64 位 ASCII 字母、数字、横线和下划线，由 Service 校验。
-- ASCII 区分大小写；禁止空白的接口规则也避免 VARCHAR 尾空格等价问题。
-- 同一企业请求号跨库存命令唯一，后续领料/退料不得复用已有入库请求号。
ALTER TABLE inventory_transactions
    ADD COLUMN request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        NULL COMMENT '客户端业务请求号，企业内唯一，旧流水允许为空',
    ADD UNIQUE KEY uk_inventory_tenant_request (tenant_id, request_id);
