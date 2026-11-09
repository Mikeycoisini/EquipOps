package com;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan({"com.equipment.mapper", "com.workorder.mapper", "com.tenant.mapper", "com.auth.mapper", "com.inventory.mapper", "com.maintenance.mapper", "com.statistics.mapper", "com.audit.mapper", "com.operations.mapper"})
public class EquipOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(EquipOpsApplication.class, args);
    }

}
