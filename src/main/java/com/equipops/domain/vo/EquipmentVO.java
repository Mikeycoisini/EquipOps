package com.equipops.domain.vo;

import com.equipops.domain.enums.EquipmentStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 设备。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EquipmentVO {

    /** 设备 ID */
    private Long id;

    /** 所属企业 ID。 */
    private Long tenantId;

    /** 所属厂区 ID。 */
    private Long siteId;


    /** 设备资产编号 */
    private String assetCode;

    /** 设备名称 */
    private String name;

    /** 设备型号 */
    private String model;

    /** 所在位置或库位 */
    private String location;

    /** 分类 */
    private String category;

    /** 设备运行状态 */
    private EquipmentStatus status;

    /** 设备健康度（0～100） */
    private Short health;

    /** 下次维护日期 */
    private LocalDate nextMaintenanceAt;

    /** 累计运行小时数 */
    private BigDecimal runtimeHours;

    /** 设备图片地址或资源标识 */
    private String imageRef;

    /** 设备投产日期 */
    private LocalDate installedAt;

    /** 设备负责人用户 ID */
    private Long managerUserId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
