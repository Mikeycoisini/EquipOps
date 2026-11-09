# Redis共享Session：故障恢复、动态撤权与启动配置验收

日期：2026-11-02（上海时间）。承接32号总览及本聊天中的共享Session首轮测试。本报告以实际请求、日志和只读数据库审计为依据。

## 1. 结论

用户委托的三项工作已完成：故障与恢复测试、Redis模式动态撤权回归、配置和启动说明整理。没有重写登录、企业切换或权限业务，没有更换JWT，没有执行数据库迁移。

共享Session下成员停用与管理员撤权立即作用于另一实例的原会话；Redis不可用时接口失败，不会放行旧权限。保留数据恢复Redis后，客户端能够自动重连并恢复原身份和当前企业；丢失会话数据后需要重新登录。

这不代表故障体验已完善：当前故障响应为HTTP 500，未形成统一503/JSON提示；恢复瞬间的首个请求也可能失败。首次测试的失败和后续专项复测均保留，不宣称所有测试一次全通过。

## 2. 测试环境与影响

- 真实后端：D:/Opus/EquipOps；使用E:/ChatGPT/EquipPos/artifacts/redis-session-20261102/backend独立副本运行。
- Java17、Spring Boot4.0.6；Redis依赖与安全业务沿用原项目。Java源码未修改。
- MySQL明确使用equipops_test，两个实例均关闭Flyway；没有连接默认equipops库执行测试，没有改迁移或种子。
- 后端端口18080/18081；默认启动行为探测额外使用18082。
- 临时Redis仅监听127.0.0.1:16379，Windows Redis3.0.504；这是本机功能实验，不是生产版本、安全、性能或高可用认证。
- 用户停止的6379 Redis始终保持停止；原有8081实例未停止。
- namespace为equipops:closeout:20261102或专项复测的equipops:recovery:20261102；Cookie使用JSESSIONID，接口客户端各自管理Cookie。
- 故障实验仅停止本轮创建的Redis进程；保留数据实验使用手动SAVE生成RDB后重启，丢失数据实验从空目录重启。没有对用户Redis执行FLUSHDB或改配置。
- 登录会更新测试账号last_login_at。通过正常API创建1个标记账号/成员：userId=91021、membershipId=91025，标记REDIS-CLOSE-2137802f90a643ce。清理后成员停用、角色数0，保留审计数据；未删除记录。随机密码仅在内存使用。

## 3. 实际结果

| 场景 | 结果与证据 |
| --- | --- |
| 不启用redis-session profile，6379停止 | 应用可以启动，但GET /api/auth/csrf返回500，约1.196秒。证明当前普通依赖会启用Redis Session，profile不是存储开关；此诊断实例显式设置2秒连接/命令超时，不能推断未配置默认超时也是2秒 |
| 配置文件Cookie绑定 | 实际产生JSESSIONID；跨实例CSRF及企业选择成功 |
| A停用成员，B使用原Session读取设备/统计 | 两个代表入口从200变403 |
| A恢复成员，B使用同一Session | 两个入口恢复200 |
| A授予管理员，B读取成员管理 | 200 |
| A撤销管理员，B读取成员管理与提交管理写入 | 均403；普通设备/统计读取仍200；me不再含TENANT_ADMIN |
| 撤权是否依赖重新登录 | 不依赖，Cookie在内存比较相同，未写入证据文件 |
| Redis停止，A/B读取已登录身份 | 首轮分别500、6.031秒和500、6.036秒；专项复测为6.036秒和0.886秒。均未返回业务成功 |
| 保留数据后恢复，首次立即请求 | 首轮A返回500、约4.762秒，B返回200。日志出现Lettuce超时和自动重连，不能认定恢复立即完成 |
| 保留数据恢复专项复测 | 8项断言通过；A/B不重启，原Cookie和企业91001恢复。Redis PING就绪后，顺序请求在累计1.671秒、2.226秒收到200。这不是恢复时间上限或并发测量 |
| Redis会话数据丢失后恢复 | 两实例原Cookie均401；重新登录、选择企业后跨实例200 |
| 数据审计 | equipment=17、work_orders=32、inventory_transactions=46、warehouse_stock=24，与本轮测试前一致。只证明这些表行数未变，未做全库逐字段校验 |
| 清理 | 临时Redis和3个测试后端均停止，测试成员停用且无角色，用户6379仍停止 |

首轮closeout.py共31项断言，30通过、1失败；失败项是“恢复后第一个请求即成功”。recovery.py单独验证自动重连后的最终恢复，8项通过。前者失败记录不覆盖、不改写为成功。前一次基础共享会话的15项记录属于本聊天前一轮，不计为本轮重跑。

2秒连接/命令超时限制的是单次相关等待，不是整个HTTP请求的硬上限。Session读取、保存和错误处理可能发生多次Redis访问，所以观察到约6秒。不要据此对外承诺请求最多2秒。

## 4. 配置改动与备份

只修改D:/Opus/EquipOps/src/main/resources/application-redis-session.properties：

```properties
spring.data.redis.host=${REDIS_HOST:127.0.0.1}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.password=${REDIS_PASSWORD}
spring.session.data.redis.namespace=${REDIS_SESSION_NAMESPACE:equipops:test:session}
spring.session.data.redis.repository-type=default
spring.session.timeout=${REDIS_SESSION_TIMEOUT:30m}
spring.data.redis.connect-timeout=${REDIS_CONNECT_TIMEOUT:2s}
spring.data.redis.timeout=${REDIS_COMMAND_TIMEOUT:2s}
server.servlet.session.cookie.name=JSESSIONID
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.same-site=lax
```

保留原连接字段及默认namespace/30分钟空闲时间，增加环境参数和连接等待限制，统一Cookie名称；本地HTTP测试没有设置Secure=true，HTTPS部署应另行核对。原文件备份：E:/ChatGPT/EquipPos/artifacts/redis-session-20261102/before-closeout/20261102-193719/application-redis-session.properties。

原有认证代码保持不动：saveContext保存认证、HttpSession保存企业ID、HttpSessionCsrfTokenRepository保存CSRF、CurrentAccessService每次查询最新权限。Redis保存会话，不代替数据库授权校验。

默认仍依赖Redis：没有将starter改为可选Maven profile，也没有引入“故障时自动回退内存Session”。未开启redis-session时，本文件的Cookie/超时/namespace配置不会加载，但依赖仍可能自动配置Session。这是当前已实测行为，不是双模式切换能力。

## 5. 双实例启动说明

IDEA运行原后端时复制两个启动配置，分别设置端口，并明确以下配置；不要直接沿用默认开发库。

| 配置项 | A与B要求 |
| --- | --- |
| Active profiles | redis-session |
| server.port | 两个不同且空闲端口，例如18080/18081 |
| DB_URL | jdbc:mysql://localhost:3306/equipops_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai |
| DB_PASSWORD | 使用已有本机凭据，不写入文档或版本库 |
| spring.flyway.enabled | 本次独立验证为false；不是建议正式部署永久关闭迁移 |
| REDIS_HOST / REDIS_PORT | 相同的Redis地址和端口 |
| REDIS_PASSWORD | 与Redis一致；无密码本机测试可通过启动参数--spring.data.redis.password=明确留空 |
| REDIS_SESSION_NAMESPACE | 两实例相同；不同环境隔离 |
| REDIS_SESSION_TIMEOUT | 两实例相同，常规30m |

两实例必须使用兼容代码版本和相同Redis数据库编号。Cookie不按端口隔离，不要在同一浏览器Cookie环境里混用其他本机Session测试；建议独立浏览器配置或接口工具独立Cookie罐。

本轮另提供E:/ChatGPT/EquipPos/artifacts/redis-session-20261102/start-redis-session-test.ps1，可在两个终端分别运行：

```powershell
& 'E:/ChatGPT/EquipPos/artifacts/redis-session-20261102/start-redis-session-test.ps1' -Port 18080 -RedisPort 6379
& 'E:/ChatGPT/EquipPos/artifacts/redis-session-20261102/start-redis-session-test.ps1' -Port 18081 -RedisPort 6379
```

该脚本不启动Redis，使用已编译的测试副本，固定连接equipops_test并关闭Flyway；密码来自已有IDE本机配置。运行前需要用户启动对应Redis；本次结束时6379仍关闭。脚本已做PowerShell语法检查，未以这两个手动命令另开实例验收；实际HTTP测试由closeout.py/recovery.py编排。源码变化后需重新构建副本和依赖classpath，不要将旧副本用于验证新代码。

编译副本的本机已验证命令：在E:/ChatGPT/EquipPos设置JAVA_HOME=E:/JDK17，使用现有Maven3.9.16，以-f artifacts/redis-session-20261102/backend/pom.xml执行compile dependency:build-classpath，-Dmdep.outputFile=../classpath.txt；本轮使用C:/Users/薇薇依娉婷/.m2/repository依赖缓存。不将这些本机路径写入pom.xml。

## 6. 原始证据与限制

所有相对路径以E:/ChatGPT/EquipPos/artifacts/redis-session-20261102为根：

- closeout/results.json：首轮31项原始结果。
- closeout/backend-18080.log、backend-18081.log、backend-18082.log：运行与故障日志。
- closeout/fixture.json、business-before.json：标记成员与业务行数基线。
- recovery/results.json及同目录日志：自动重连专项复测。
- final-audit.json：最终只读数据、端口、源码对照和清理核验。
- verify.py / closeout.py / recovery.py：对应实验脚本；closeout.py保留首次“立即恢复”断言，因此可能观察到预期的瞬时失败；恢复窗口由recovery.py专门验证。

未验证：浏览器故障提示、负载均衡/HTTPS/跨域、Redis生产版本、主从/哨兵切换、网络黑洞、长时间故障、在途写请求与退出竞态、全API全角色矩阵、性能/吞吐及实际生产备份恢复。RDB实验只用于区分Session保留和丢失，不能当作数据库备份恢复验收。


共享Session核心验证和本次补测已完成。若继续改善当前故障体验，可单独约定最小503/统一JSON与前端提示；本轮只记录现状，不因测试委托扩大为改写安全过滤链。

下一开发单元按27号路线是“一个异步导出功能”，先确定导出工单还是库存流水、字段、权限、规模与格式，再逐步实现，不提前生成完整业务。

本单元复述三题：

1. 为什么把Session迁到Redis，登录代码可以保持不变？因为Spring Session替换HttpSession的存储实现，业务仍面向HttpSession操作。
2. 为什么共享Session后还能立即撤权？会话保存身份与企业选择，CurrentAccessService每次从数据库校验成员和角色，不把旧Session当作永久授权。
3. 为什么Redis重启后可能继续登录，也可能必须重新登录？取决于会话数据是否保留、是否过期、序列化是否兼容，以及客户端是否完成重连；仅端口可连接不等于所有请求已恢复。
