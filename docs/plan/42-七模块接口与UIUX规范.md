# 七模块接口与UI/UX规范

日期：2026-11-03。文档阶段三。所有新增路径为候选契约，须逐项确认后实施；已有GET /api/sites、GET /api/warehouses的默认对象语义保留，不能直接改成全量列表。

## 1. 统一协议

沿用Session、CSRF和Result{code,message,data,timestamp}；HTTP状态与业务结果一致。分页沿用IPage：records,total,current,size，current>=1，size建议1—100；实际DTO确认后冻结。稳定排序createdAt DESC,id DESC；BIGINT ID/编号按字符串，数量按三位小数字符串，时间ISO8601带时区。实体不能直接作为响应，禁止storageKey/runToken/凭据/SQL泄露。

tenantId/userId不得从普通业务请求体取授权；平台接口里的tenantId只作目标对象，仍单独鉴权。401未登录、403无动作权、404无权范围或对象不存在、409状态/版本/幂等冲突、400非法参数、429容量、503依赖不可用；业务原有状态约定以当前Controller核对，不能批量重写。

敏感变更body含requestId（业务幂等，与X-Request-ID追踪头不同）及expectedVersion；同号同内容返回原结果，同号异内容409。CSRF只用于浏览器写入口，不能为AI工具全局关闭。

## 2. 模块接口与页面清单

| 模块 | 候选接口 | 页面及动作权限 |
|---|---|---|
| M1 | GET /api/audit-events；GET /api/audit-events/{id} | 企业管理员审计列表/详情，按动作/操作者/对象/时间筛选，仅当前企业，无编辑删除 |
| M2 | GET /api/operations/alerts；GET /api/operations/alerts/{id}；POST /{id}/acknowledge；POST /{id}/resolve；GET /api/operations/alert-rules | 受权运维看平台事件，企业管理员仅企业事件；确认/处置填说明，指标恢复不由普通用户伪造 |
| M3 | GET/POST /api/teams；GET/PUT /api/teams/{id}；PUT /{id}/members；PUT /{id}/site-scopes；POST /api/work-orders/{id}/reassign | 团队列表/详情/成员范围；工单详情改派弹窗选择有效目标并填原因 |
| M4 | GET /api/warehouses/catalog（新增完整列表）；GET/PUT /api/warehouses/{id}；复用已有POST创建；GET/POST /api/transfers；GET /{id}；POST /{id}/dispatch、receive、cancel；POST /{id}/exceptions；POST /{id}/resume | 仓库页+调拨列表/详情；创建管理员选择两仓和备件数量；状态控制按钮但后端必须复核 |
| M5 | GET /api/sites/catalog；复用现有厂区详情/创建/更新；GET/PUT /api/tenant-members/{id}/site-scopes | 厂区页+成员范围选择器；不可见厂区不显示，保存前提示哪些业务受影响 |
| M6 | POST /api/platform/tenants；GET /api/platform/tenants；GET /{id}；POST /{id}/suspend、resume；GET /{id}/actions | 独立平台入口，企业管理员访问403；暂停/恢复显示企业目标、原因和影响确认 |
| M7 | GET/POST /api/ai/documents；GET /{id}；DELETE /{id}；POST /{id}/versions；POST /api/ai/questions；GET /api/ai/tasks/{id}；POST /{id}/cancel | 资料页与问答页；管理员管理资料，有效授权成员提问；来源抽屉、状态和失败重试 |

表中短路径/{id}均相对该模块最近完整资源前缀；实施时Controller内必须写完整核对清单。规则维护接口/告警外部通知暂不设计成通用脚本编辑器；Python内部工具接口不开放给浏览器。

## 3. 核心请求/响应示例

以下ID及值仅为说明，不是数据库种子：

```json
{"requestId":"client-generated-uuid","sourceWarehouseId":"101","targetWarehouseId":"102","sparePartId":"201","quantity":"3.000","note":"设备维护备件调拨"}
```

POST /api/transfers 成功201，Result.data包含id、transferNo、REQUESTED、version、两端仓库、数量和createdAt。出库/收货body `{requestId,expectedVersion}`；取消/异常/恢复另含reason。动作成功200，状态竞争409，库存不足409且不产生半条流水；不以202伪装库存已完成。

POST /api/work-orders/{id}/reassign：`{requestId,expectedVersion,targetAssigneeUserId,targetTeamId,reason}`，200返回更新后工单，不新造ASSIGNED状态。PUT成员范围：`{expectedVersion,siteIds:["11","12"]}`，完整替换集合，空数组=无普通厂区授权，管理员例外按PRD确认；不能解释为不修改。

POST /api/ai/questions：`{requestId,question,documentIds:[]}`，202返回taskId/state；GET任务成功结果分answer、citations、businessFacts、warnings。引用包括documentId/versionId/chunkId/section，只有真实页码才给page。无依据返回明确不足状态，不伪造成功结论。Python错误映射安全错误码，不暴露供应商密钥。

## 4. 状态驱动交互

| 场景 | 页面规范 |
|---|---|
| 加载/空结果/失败 | 分别显示，不用空数组掩盖失败；保留筛选，重试不重复写入 |
| 调拨REQUESTED | 展示出库/取消；IN_TRANSIT显示收货/异常；EXCEPTION仅核验恢复；终态只读 |
| 版本冲突 | 提示数据已更新，重新读取，不自动覆盖或重复扣库存 |
| 企业切换 | 清空旧详情和候选项，停止轮询，丢弃旧企业晚到响应；后台任务不改变归属 |
| 厂区撤权 | 重新加载允许范围；不可访问详情404，不显示缓存原文 |
| 企业暂停 | 清除当前业务视图，显示企业不可用并允许重新选择其他有效企业；不自动循环重试 |
| AI执行 | 等待/检索/生成/成功/失败/取消/过期清晰展示；生成不代表维修建议正确 |
| AI来源 | 来源和业务事实分区，模型推断明确标注；资料文本不能作为页面HTML执行 |

## 5. UI信息架构与可用性

企业设置：厂区/仓库/团队/成员范围/审计；库存：调拨；工单详情：团队与改派时间线；运维：告警与处置；平台：企业管理独立导航；知识助手：资料/问答。没有权限的菜单隐藏但不替代后端权限。

表单每字段中文标签、单位、必填/最大长度、就地错误；按钮提交期间禁重复点击，业务幂等仍必须实现。危险动作显示目标、影响和确认，键盘可操作，焦点回到触发元素；颜色配文字，状态不只靠颜色区分。小屏列表允许横向滚动，详情重要状态/数量优先；无障碍名称、错误提示关联输入。首版提供文字页面规格，视觉稿在进入对应页面时再逐项确认，不以本文宣称UI已实现。

## 6. 契约冻结与兼容

每接口开工前输出字段/约束/角色/状态/示例/错误清单，更新OpenAPI或等价契约记录。先实现当前接口，不批量生成候选。现有默认厂区/仓库GET保持原语义；若选择改路径命名需同步39/41/43文档及前端，不留两套相互矛盾契约。
