# ch05 LangGraph Agent Runtime 与工具编排

我要用 Superpowers 模式把 EquipOps 的 Agent 从"单次问答"做成"会按步骤干活"的运行时，第五步用 LangGraph 搭编排，并把上限、校验、中断全部做成硬约束。

## 功能需求

1. Runtime 结构：`Context Loader → Reasoning → Tool Executor → Observe → Update State → Loop Controller → Result Validation → Final Response / Human Approval`。
2. 图用 LangGraph：节点固定、边固定；Reasoning 只能从"已注册 ∩ 本任务 scope"里选工具。
3. 预算硬闸：单任务工具调用次数、单工具超时、任务总时长、输入/输出 token、每企业并发、每用户每日任务数；超限返回明确错误码。
4. 结果校验：工具返回按 pydantic schema 与字段白名单校验，失败丢弃并加 warning，不把脏数据送进模型。
5. checkpoint：按 tenant/user/task 隔离，存本地文件，不进业务库、不存凭据；恢复任务必须重新校验授权与业务状态。
6. 中断：需要写入时用 `interrupt` 暂停，等人工确认；恢复时不信任旧结论。
7. 一致性检查：答案里的"当前库存/状态"与 `businessFacts` 矛盾时标注 warning 或降级。

## 技术栈

- Python 3.12、FastAPI、**LangGraph**（状态图、checkpoint、interrupt）、**LangChain**（模型与工具集成）、pydantic v2。
- Java 侧沿用 Spring Boot 4.0.6，只加预算计数与配置项。
- 定死：编排用 LangGraph，不手写第二套状态机；不引 Multi-Agent、不引通用 Agent 平台。

## 前置（不满足就停）

- ch03、ch04 已通过。
- 首版预算数值已与我确认。
- LangGraph 与 LangChain 的当前稳定版本已用 Context7 核对并锁进 `pyproject.toml`。

## 本章不做

- 不做故障诊断的具体业务链路（ch06 才做）。
- 不做草稿与写入（ch07 才做）。
- 不做 Multi-Agent、不做子图编排、不做通用 Agent 平台。
- 不自由循环：不允许 Reasoning 决定"再查一次"超过上限。
- 不对生成节点或有副作用的调用重试；只允许幂等只读调用有限重试。
- 不把 checkpoint 或 taskId 当权限凭证。

## 验收标准

1. 一次完整任务能在图里跑通，节点顺序与状态迁移可追溯。
2. 六类上限各自有触发记录：工具次数、单工具超时、总时长、token、并发、每日。
3. 非法状态迁移与非法工具选择被拒。
4. 脏数据被丢弃，答案里不出现脏数据。
5. checkpoint 按企业隔离：企业 A 的任务状态不会被企业 B 读到；恢复时重新校验授权。
6. 中断—确认—恢复链路可跑通，恢复后不沿用旧权限结论。
7. 单任务成本与延迟分布（P50/P95）有实测值。
8. 全量测试通过。

## 工作要求

1. 全程走 Superpowers 流程；技能能自动触发就自动触发，没有就直说。
2. 过程留痕：`dev-notes/m7-ch05.md`，每阶段补一段，记关键原话、关键产出、被纠偏的点、翻车与返工。
3. **LangGraph 的 StateGraph、checkpoint、interrupt、recursion_limit 等 API 必须先用 Context7 MCP 查最新官方文档**，版本对不上的 API 是返工重灾区；没有 Context7 就用官网文档并记版本。
4. 技术选型定死；走不通停下来问，尤其是有人提议"换回手写状态机"或"上个 Multi-Agent"的时候。
5. 可单测部分照 TDD：迁移规则、预算计数、schema 校验、checkpoint 键隔离都要先红后绿，覆盖"第 N+1 次调用"这类边界。
6. 边界（违反即停）：只改 `ai-service/app/orchestrator/**`、`ai-service/app/tools/**`、`src/main/java/com/ai/service/**`、`AiProperties`、测试与证据目录；不加新基础设施。
7. 完结交付：运行时结构图、六类上限触发证据、checkpoint 隔离证据、测试结果、`dev-notes/m7-ch05.md` 路径。
