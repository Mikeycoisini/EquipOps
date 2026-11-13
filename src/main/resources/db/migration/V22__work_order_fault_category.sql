ALTER TABLE work_orders
    ADD COLUMN fault_category VARCHAR(16) COLLATE utf8mb4_0900_bin NULL,
    ADD CONSTRAINT chk_work_order_fault_category CHECK (
        fault_category IS NULL OR (
            source = 'REPAIR_REPORT'
            AND fault_category IN ('MECHANICAL', 'ELECTRICAL', 'CONTROL', 'HYDRAULIC', 'OTHER')
        )
    );
