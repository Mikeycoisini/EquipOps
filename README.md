# EquipOps V2.0 后端

基于Java17、Spring Boot4、MyBatis/MyBatis-Plus、MySQL及Redis Session的企业设备运维系统。

## 配套项目

- 后端仓库 [Mikeycoisini/EquipOps](https://github.com/Mikeycoisini/EquipOps)
- 前端仓库 [Mikeycoisini/OPS-V2.0-](https://github.com/Mikeycoisini/OPS-V2.0-)
- [完整系统路线](docs/plan/51-V2.0完整系统总体实施方案.md)
- [工程实施计划](docs/plan/52-V2.0GitHub工程实施计划.md)

## 本地启动

准备Java17、MySQL8与Redis。通过IDE运行配置或进程环境设置DB_PASSWORD，按实际服务配置设置REDIS_PASSWORD。其余参数可按下表覆盖。

| 参数 | 默认值 |
|---|---|
| DB_URL | 本机MySQL的equipops_v2库 |
| DB_USER | root |
| APP_PORT | 8082 |
| REDIS_HOST / REDIS_PORT | 127.0.0.1 / 6379 |
| REDIS_SESSION_NAMESPACE | equipops:v2:session |
| INVENTORY_EXPORT_ROOT | 用户主目录下.equipops-v2/inventory-exports |

先确认数据库目标，再通过Flyway初始化结构。现有正式迁移V1—V15，告警V13、续扫V14、审计V15；数据库clean关闭，自动baseline关闭。

```bash
./mvnw -DskipTests package
java -jar target/EquipOps-0.0.1-SNAPSHOT.jar
```

Windows使用mvnw.cmd。主入口为com.EquipOpsApplication，默认加载redis-session配置。Redis密码应与实际实例一致。

## 当前交付状态

已有设备、维修工单、备件库存、周期维护、统计、库存流水异步导出及导出故障告警基础。后续组织范围、协作、调拨、平台及AI增量按路线逐阶段交付。

本机独立新库初始化验证建立27张表、15个迁移成功，打包应用启动及CSRF入口通过，详细范围见[初始化记录](docs/plan/53-V2.0配置修复与新库初始化记录.md)。新库的管理员、企业和必要基础角色仍需初始化。该记录对应本机专项，完整业务和GitHub CI结果单独登记。

## 数据与验证

Session写请求保留CSRF，企业身份由后端核验。迁移、事务、并发、幂等及权限变更需要对应数据库与接口证据。源码、构建和数据库联调结果分别记录。

配置通过环境变量注入。运行日志、数据库转储、本机配置及历史数据夹具按忽略规则保留在本地。
