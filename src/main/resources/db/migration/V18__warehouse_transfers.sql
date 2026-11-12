-- 调拨单、幂等结果与库存流水关联。
CREATE TABLE warehouse_transfers (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    transfer_no VARCHAR(40) NOT NULL,
    source_warehouse_id BIGINT UNSIGNED NOT NULL,
    target_warehouse_id BIGINT UNSIGNED NOT NULL,
    spare_part_id BIGINT UNSIGNED NOT NULL,
    quantity DECIMAL(14,3) NOT NULL,
    status ENUM('REQUESTED','IN_TRANSIT','RECEIVED','CANCELLED','EXCEPTION') NOT NULL,
    version INT NOT NULL DEFAULT 0,
    note VARCHAR(500),
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_transfer_number (tenant_id, transfer_no),
    UNIQUE KEY uk_transfer_owned (tenant_id, id),
    KEY idx_transfer_status (tenant_id, status, id),
    KEY idx_transfer_source (tenant_id, source_warehouse_id, status),
    KEY idx_transfer_target (tenant_id, target_warehouse_id, status),
    CHECK (quantity > 0 AND source_warehouse_id <> target_warehouse_id),
    FOREIGN KEY (tenant_id, source_warehouse_id) REFERENCES warehouses (tenant_id, id),
    FOREIGN KEY (tenant_id, target_warehouse_id) REFERENCES warehouses (tenant_id, id),
    FOREIGN KEY (tenant_id, spare_part_id) REFERENCES spare_parts (tenant_id, id),
    FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE warehouse_transfer_operations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    transfer_id BIGINT UNSIGNED NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(16) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    result_status VARCHAR(16) NOT NULL,
    result_version INT NOT NULL,
    actor_user_id BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(500),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_transfer_operation_request (tenant_id, request_id),
    KEY idx_transfer_operation_history (tenant_id, transfer_id, id),
    FOREIGN KEY (tenant_id, transfer_id) REFERENCES warehouse_transfers (tenant_id, id),
    FOREIGN KEY (actor_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE inventory_transactions
    DROP CHECK chk_inventory_return_source,
    MODIFY reference_type ENUM('WORK_ORDER','PURCHASE_ORDER','MANUAL','OTHER','TRANSFER') NOT NULL DEFAULT 'MANUAL',
    ADD CONSTRAINT chk_inventory_return_source CHECK (
        issue_transaction_id IS NULL OR (
            tenant_id IS NOT NULL AND warehouse_id IS NOT NULL
            AND transaction_type = 'INBOUND' AND reference_type = 'WORK_ORDER'
        )
    ),
    ADD transfer_id BIGINT UNSIGNED NULL,
    ADD UNIQUE KEY uk_inventory_transfer_direction (tenant_id, transfer_id, transaction_type),
    ADD CONSTRAINT fk_inventory_transfer FOREIGN KEY (tenant_id, transfer_id)
        REFERENCES warehouse_transfers (tenant_id, id),
    ADD CONSTRAINT chk_inventory_transfer CHECK (
        (reference_type <> 'TRANSFER' AND transfer_id IS NULL) OR
        (reference_type = 'TRANSFER' AND tenant_id IS NOT NULL AND warehouse_id IS NOT NULL
            AND transfer_id IS NOT NULL AND transaction_type IN ('INBOUND','OUTBOUND'))
    );
