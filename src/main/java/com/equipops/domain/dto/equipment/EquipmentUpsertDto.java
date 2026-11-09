package com.equipops.domain.dto.equipment;

import com.equipops.domain.enums.EquipmentStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 保存设备请求。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EquipmentUpsertDto {

    /** 设备资产编号 */
    @NotBlank
    @Size(max = 64)
    private String assetCode;

    /** 设备名称 */
    @NotBlank
    @Size(max = 160)
    private String name;

    /** 设备型号 */
    @Size(max = 120)
    private String model;

    /** 所在位置或库位 */
    @Size(max = 160)
    private String location;

    /** 分类 */
    @Size(max = 100)
    private String category;

    /** 设备运行状态 */
    private EquipmentStatus status;

    /** 设备健康度（0～100） */
    @Min(0)
    @Max(100)
    private Short health;

    /** 下次维护日期 */
    private LocalDate nextMaintenanceAt;

    /** 累计运行小时数 */
    @DecimalMin("0")
    @Digits(integer = 12, fraction = 2)
    private BigDecimal runtimeHours;

    /** 设备图片地址或资源标识 */
    @Size(max = 500)
    private String imageRef;

    /** 设备投产日期 */
    private LocalDate installedAt;

    /** 设备负责人用户 ID */
    private Long managerUserId;
}
