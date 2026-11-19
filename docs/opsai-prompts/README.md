# EquipOps M7 分阶段提示词（Industrial Maintenance Agent）

一章一份，整篇复制给编码 Agent。统一五段：功能需求 / 技术栈 / 本章不做 / 验收标准 / 工作要求。

定位：**业务型 Agent + 混合架构**——Java 管业务与授权，Python（LangGraph）管推理、检索与编排。AI 只产出草稿，写入一律回 Java 校验并经用户确认。

## 文件索引

| 文件 | 章 | 内容 | 阶段 | 前置 |
|---|---|---|---|---|
| `01-ch01-资料入库与最小闭环.md` | ch01 | 上传→切分→词面检索→引用→任务可查/可取消 | AI1.0 前 | 无 |
| `02-ch02-向量检索引用与评测.md` | ch02 | 向量检索、引用校验、拒答、评测集 | AI1.0 后 | ch01 |
| `03-ch03-只读工具底座.md` | ch03 | 工具注册表、任务凭证、设备、履历 | AI1.1 前 | **M5**、ch02 |
| `04-ch04-只读工具扩展.md` | ch04 | SOP 检索、适配件、库存、告警 | AI1.1 后 | ch03 |
| `05-ch05-Agent-Runtime.md` | ch05 | LangGraph 编排、预算、结果校验、checkpoint | AI1.2 | ch04 |
| `06-ch06-故障诊断与案例检索.md` | ch06 | S1 诊断链路、S2 历史案例、备件推荐 | AI1.3 | ch05 |
| `07-ch07-工单与报告草稿.md` | ch07 | 草稿、人工确认、Java 三重校验写回 | AI1.4 | ch06；写入范围已确认 |
| `08-ch08-评估可观测与安全.md` | ch08 | 评测、成本、日志脱敏、开关、熔断、停机演练 | AI1.5 | ch07 |
| `99-code-review.md` | — | 每章合并前的 code review | — | 任一章完成 |

## 技术栈（全局定死）

- 前端：Vue 3（独立仓库）
- 业务：Java 17、Spring Boot 4.0.6、MyBatis-Plus 3.5.15、Flyway、MySQL 8、Redis Session
- Agent：Python 3.12、FastAPI、**LangGraph**、**LangChain**
- 模型：OpenAI 兼容协议直连上游，`CHAT_*` 全在 `.env`
- 工具：Java 内部 HTTP 网关，契约按 MCP 工具形态（name/description/inputSchema）；独立 MCP Server 后置
- 定死：以上不可自行替换；发现矛盾或走不通，停下来问

## 通用约定

**流程**：brainstorm → spec → plan → 逐任务执行 → code review → finish。技能能自动触发就自动触发；环境里没有就直说，不要假装走过。

**留痕**：每章在 `dev-notes/m7-ch0X.md` 追记，每个阶段结束补一段，记四样——关键原话、关键产出（spec/plan 路径、评审结论）、被拒绝或纠偏的点、翻车与返工。不许收尾一次性补记。

**查文档**：涉及具体库/框架/API 用法，先用 Context7 MCP 查最新官方文档和接口定义；没有 Context7 就用官网文档，并在 dev-notes 记版本与链接。

**边界红线**（所有章节通用）：

- 身份只从 `CurrentAccessService` 取，绝不从请求体接受 `tenantId`/`userId`。
- 不改已执行的 Flyway 迁移（V1—V15）；新迁移版本号先确认。
- Python 不连业务 MySQL；模型不生成并执行 SQL。
- 工具名与参数由 Java 注册表决定，模型说了不算。
- ch01—ch06 不写业务表；ch07 也只经用户确认后写。
- ch03 起，M5 未落地就不得接业务工具。
- 不提交凭据、`.env`、资料文件、运行数据。
- 不用编译通过冒充验收，不用假数据冒充联调。

**UI 例外**：前端在独立仓库。页面用 Vibe Coding 方式（我描述效果你改），不套 brainstorm、TDD、code review。

## 与仓库文档的关系

- 定位与场景：`docs/plan/56-M7-Agent定位与场景范围.md`
- 流程规范：`docs/plan/55-M7-Agent开发流程规范.md`
- 范围与门槛：`docs/plan/38`（M7-R1/R2）、`39`、`40`、`41`、`42`、`43`

提示词是执行指令，上述文档是依据。冲突时以仓库文档为准，并停下来报告。
