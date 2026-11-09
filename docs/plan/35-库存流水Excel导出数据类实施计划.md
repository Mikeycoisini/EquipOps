# 库存流水Excel导出数据类实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 仅为已确认的Excel异步导出创建5个数据类型，不实现接口或数据库业务。

**Architecture:** 任务Entity准备MyBatis映射，两个DTO分别接收创建筛选和任务分页，VO只输出公开资料，枚举列出8种状态。当前直接在用户指定的D:/Opus/EquipOps新增文件，不切换分支/工作树，不覆盖现有文件。

**Tech Stack:** Java17、Lombok、MyBatis-Plus、Jakarta Validation；本步不添加POI等依赖。

**Spec:** D:/Opus/EquipOps/docs/plan/34-库存流水Excel异步导出首版设计.md（用户已确认）。

## Global Constraints

- 仅创建Entity、DTO、VO、枚举，不新增Controller、Mapper、Service、迁移SQL或业务实现，不启动应用，不提交/推送。
- 保留用户已有文件与TODO，使用@Data、@NoArgsConstructor、@AllArgsConstructor及中文字段注释，不添加@Builder。
- 请求不接收tenantId、createdBy、状态或服务器文件路径；VO不暴露storageKey、ownerInstanceId、runToken、租约。
- Ruling：参照MaintenancePlanEntity，本步Entity仅使用MyBatis注解，暂不加JPA @Entity，避免未建表时影响既有Hibernate启动校验。数据库设计步骤再决定是否需要JPA映射。
- Ruling：本步只有声明式数据模型，无业务行为；按上级约定不编写镜像字段的测试，以Java17编译、Lombok生成检查和字段边界核对验证，不扩展为接口测试。

## Review Focus

- DTO不能通过继承分页查询类意外接收分页或身份字段。
- VO不能复制Entity后泄漏内部路径、租约或执行令牌。
- 8种状态必须与已确认方案完全一致，不能缺失取消中/拒绝/过期。
- 未建表的Entity不能触发新JPA表校验。
- Lombok构造器/getter/setter需真实编译，不只依赖文本检查。

## Task 1：创建并验证五个数据类型

**Files（均为D:/Opus/EquipOps/src/main/java/com/equipops/domain下新文件）：**

- entity/InventoryExportTaskEntity.java：inventory_export_tasks映射，身份、筛选、状态、文件、错误、执行租约与生命周期时间。
- dto/inventory/InventoryExportCreateDto.java：3个正数关联ID、2种既有枚举、起止时间，无页码和身份字段。
- dto/inventory/InventoryExportPageQueryDto.java：current=1、size=10、可选任务状态；分页范围后续由业务层限制。
- vo/InventoryExportTaskVO.java：任务ID、公开筛选、状态、已处理条数、文件展示信息、公开错误与时间。
- enums/InventoryExportStatus.java：QUEUED、RUNNING、CANCEL_REQUESTED、SUCCEEDED、FAILED、CANCELLED、REJECTED、EXPIRED。

**Interfaces:** 本步仅产生上述Java类型及Lombok访问方法，不产生服务方法/HTTP路径。

- [x] 检查5个目标均不存在，保存现有Java文件哈希基线。
- [x] 创建5个新文件，保护原有代码；Entity使用Long/LocalDateTime及既有枚举，错误码为String，内部文件字段仅Entity持有。
- [x] 在E盘独立输出目录使用Java17和已有依赖/Lombok编译5个类型，检查javap输出。
- [x] 核对DTO/VO边界、状态完整性、新文件范围和旧Java文件哈希未变；记录结果。
- [x] 将计划及验证记录交付；仅讲解下一数据库设计步骤，不自动建表。

完成标准只涵盖数据类型可编译和边界检查，不代表数据库、任务或Excel导出功能已实现。

## 本步实际结果

5个新类型经Java17和Lombok编译，编译日志无诊断输出；请求/返回字段边界与8种状态核对通过，独立只读审查无重要问题。196个原Java文件哈希未变。未启动Spring、未执行SQL、未创建数据库表。详细证据：E:/ChatGPT/EquipPos/artifacts/inventory-export-data-20261102/verification.json、deployment.json、javap.log。首次受限编译曾在关闭依赖文件时出现访问异常，保留compile-initial-restricted.log；使用本机依赖缓存权限复验后compile.log为空。
