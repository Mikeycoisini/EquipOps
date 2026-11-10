# EquipOps V2.0 后端

基于Java17、Spring Boot4、MyBatis/MyBatis-Plus、MySQL及Redis Session的企业设备运维系统。

## 配套项目

- 后端仓库 [Mikeycoisini/EquipOps](https://github.com/Mikeycoisini/EquipOps)
- 前端仓库 [Mikeycoisini/OPS-V2.0-](https://github.com/Mikeycoisini/OPS-V2.0-)
- [完整系统路线](docs/plan/51-V2.0完整系统总体实施方案.md)
- [工程实施计划](docs/plan/52-V2.0GitHub工程实施计划.md)
- [A0基线、差距、来源与迁移映射](docs/plan/55-A0基线差距来源与迁移映射.md)

## A0静态基线复核

需要Python3，在本仓库执行：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -v
python3 scripts/check_a0.py --frontend ../OPS-V2.0-
```

核对两仓受跟踪源码、必要构建入口及迁移版本，输出SHA-256和来源版本；不连接数据库，不替代编译或业务验收。前端检出路径不同时调整`--frontend`。

## A1工程验证

[A1复现步骤与实际验证](docs/plan/57-A1工程底座与实际验证.md)说明隔离MySQL/Redis启动、受保护变量、测试账号初始化及验证命令。后端使用完整JDK17，前端使用Node22.18；CI定义位于两仓各自的`.github/workflows/ci.yml`。

设置并核对专用`equipops_a1_`库后执行`bash scripts/a1_verify.sh`，运行构建、测试、重复初始化和真实Session/CSRF检查。初始化器仅在测试类路径中，不会在产品启动时自动创建管理员。发布候选检查可用`python3 scripts/check_public.py . ../OPS-V2.0-`。

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

先确认数据库目标，再通过Flyway初始化结构。现有正式迁移V1—V17，告警V13、续扫V14、审计V15、厂区范围V16、团队与改派V17；数据库clean关闭，自动baseline关闭。

```bash
./mvnw -DskipTests package
java -jar target/EquipOps-0.0.1-SNAPSHOT.jar
```

Windows使用mvnw.cmd。主入口为com.EquipOpsApplication，默认加载redis-session配置。Redis密码应与实际实例一致。

## 当前交付状态

已有设备、维修工单、备件库存、周期维护、统计、库存流水异步导出及导出故障告警基础。A3显式厂区范围已实现；后续协作、调拨、平台及AI增量按路线逐阶段交付。

本机独立新库初始化验证建立27张表、15个迁移成功，打包应用启动及CSRF入口通过，详细范围见[初始化记录](docs/plan/53-V2.0配置修复与新库初始化记录.md)。新库的管理员、企业和必要基础角色仍需初始化。该记录对应本机专项，完整业务和GitHub CI结果单独登记。

## 数据与验证

Session写请求保留CSRF，企业身份由后端核验。迁移、事务、并发、幂等及权限变更需要对应数据库与接口证据。源码、构建和数据库联调结果分别记录。

配置通过环境变量注入。运行日志、数据库转储、本机配置及历史数据夹具按忽略规则保留在本地。

## A2企业审计

[交付与验证](docs/plan/59-A2企业审计交付与验证.md)说明成员加入、角色、启停的事务审计、失败/拒绝记录及管理员只读页面。沿用57号隔离服务，设置`A2_MYSQL_TESTS=true`后运行`bash scripts/a1_verify.sh`可同时验证A1与A2真实HTTP流程。审计BIGINT ID按字符串传递；其他模块的动作在后续阶段逐项接入。

## A3厂区范围

[交付与验证](docs/plan/61-A3厂区范围交付与验证.md)记录显式成员厂区、全入口SQL权限、导出范围快照及下载动态撤权。普通成员历史数据不自动授予权限；上线前先核对映射。设置`A2_MYSQL_TESTS=true A3_MYSQL_TESTS=true`运行`bash scripts/a1_verify.sh`可复验隔离库事务及A1—A3真实HTTP。

## A4团队协作

[交付与验证](docs/plan/63-A4团队协作交付与验证.md)记录团队成员/工作厂区、个人与团队资格交集、受控改派、版本/幂等及责任历史。设置`A2_MYSQL_TESTS=true A3_MYSQL_TESTS=true A4_MYSQL_TESTS=true`运行`bash scripts/a1_verify.sh`复验隔离库与A1—A4真实HTTP；`scripts/a4_browser_fixture.py`只用于显式确认的隔离环境浏览器夹具，不提供生产账号初始化。
