# 2026-10-21 前后端综合检查

## 结论与范围

编译和已执行自动化检查通过，不等于所有业务和生产安全均通过。本轮检查了前端全量测试、生产构建、npm 依赖审计、后端完整 Maven package、核心授权/数据契约代码，以及构建产物的部分浏览器场景。未部署目标生产环境、未连接真实模型/数据库/向量库/MCP，未逐页逐操作穷举。保留现有未提交改动，不提交推送。

## 风险，按优先级

后续进展：多模态任务/资产所有权与控制器、任务记录的异常原文路径已在本轮后续修复，见 multimodal-ownership.md。下文保留原发现作为审计历史；其他问题仍待处理。

### P1：多模态任务和资产没有租户/主体所有权边界

MultimodalService.java:43 使用全局 taskMap，recentTasks（110）返回所有任务，getTask（118）按裸 taskId 读取，任务记录（393）含 prompt、result、errorMessage，却没有 tenantId/actorId。MultimodalController.java:61、66 和资产接口直接调用服务，没有主体授权。AdminApiInterceptor 只核验共享管理令牌。若多个租户可以经过同一管理令牌/代理访问这些接口，可读取其他任务内容；此为代码级发现，未使用真实客户数据验证。
验收：两个租户分别创建模拟任务，列表、详情和资产必须互不可见；缺身份拒绝；运维全局访问需要独立权限与审计。

### P1：治理审计为全局数据，未进行角色/租户授权

GovernanceController.java:34 直接返回 recentEvents；GovernanceAuditService.java:113 返回整个全局事件队列，AuditEvent 没有租户字段。拥有有效共享管理令牌者可经接口读取全局事件摘要/指标，不受前端菜单隐藏影响。当前事件不是聊天正文，风险描述不应夸大成已泄露完整正文。
验收：客户禁止读取运维审计；租户工作人员只能读自身范围；只有独立运维身份可读全局汇总。readiness 被客户聊天页调用，后续拆分最小公开运行状态，不能简单封禁整个治理目录而破坏客户界面。

### P1：聊天缺少企业身份时仍回退离线共享身份

ChatController.java:48 的执行入口未检查企业身份是否必须存在；scopedConversationId（101）在 principal 缺失时使用 offline-tenant/offline-user。TenantContextFilter 在生产可信头默认关闭且未有上游属性时可能留下空上下文，而 AdminApiInterceptor 仍仅检查管理令牌。若请求经过共享令牌代理但缺身份，模型执行及记忆分区可能落入共享离线空间。此处为条件性代码路径风险，不代表当前已发生跨用户泄露。
验收：安全模式开启且缺主体时，在限流/模型调用前拒绝；离线模式继续正常使用固定身份；需覆盖空身份、伪造头和跨用户同 conversationId。

### P2：就绪判定只依赖开关，却可声明受控发布就绪

EnterpriseReadinessService.java:101 根据基础设施开关、SQL 配置、模型 enabled、安全开关和发布开关返回 ready=true；115 清空后续行动，118 标注 CONTROLLED_RELEASE_READY。能力本身仍为 CONFIGURED_UNVERIFIED，无真实模型连通、数据库健康和评测证据门禁；清空 actions 还会丢弃未解决的计费提示。
验收：全部开关开启但缺可信验证证据时仍不能声明就绪；配置状态与验证状态分开，保留未完成行动。

### P2：多模态错误存在原始异常外泄路径

MultimodalController.java:87 拼接异常及 cause 原文返回；MultimodalService.java:100 把 e.getMessage 写进 task.failed 返回记录，并在103记录完整异常。不能假定异常一定含凭据，但上游消息可能含地址、响应体或敏感配置。
验收：模拟包含敏感标记的异常，响应、任务列表和日志均不出现原文，仅保留稳定错误码及可关联的事件标识。

### P3：客户身份提示显示 undefined

客户上下文刻意不返回 actorId/tenantId，NavBar.jsx:22 仍把这两个字段插入 Tooltip。应按角色显示通用隐私文案，不重新增加敏感字段。

## 本轮实际修复

完整后端构建首先失败于 MatterUploadHttpIntegrationTest：仍按旧行为拒绝 CLIENT 读列表，且匿名上下文错误码预期已过时。更新测试以验证客户可读自己的精简数据，其他客户列表为空，客户写材料/上传能力仍拒绝，内部 evidence/deadlines/reviewTasks/tenantId/attributes 不出现在客户 DTO。将匿名请求预期改为现有 ACTOR_ROLE_REQUIRED；生产授权没有放宽。

## 验证证据

- 后端完整构建：183 tests，0 failures，0 errors，3 skipped，44 suites；跳过项为显式人工集成测试（模型/pgvector/MCP/装配）。证据 data/acceptance/backend-build-20261021T061509471Z.json；JAR SHA-256：499ef69abd8b0117127e1ecb42f63fbab2a63b84c1bfdbc9b6aa4d2e36b6279c。
- 初次失败日志：data/2026-10-21-full-audit-backend.log；中间错误码不匹配日志：data/2026-10-21-audit-http-regression.log；最终通过：data/2026-10-21-full-audit-backend-final.log。
- 前端测试：26 项通过；Vite build 通过；日志 data/2026-10-21-full-audit-front.log。
- npm audit：当前锁文件审计 0 已知漏洞；data/2026-10-21-front-dependency-audit.json。不代表依赖绝对安全，也未做后端依赖漏洞扫描。
- 浏览器：使用本次 dist，Playwright 路由拦截静态资源与模拟 API，不是真实后端联调；身份失败显示明确提示，CLIENT 访问设置跳转客户首页，无观察到的页面运行错误。390px 初次进入和从桌面切换后布局稳定时 scrollWidth=390，无持续横向溢出；瞬态 resize 观察未判为缺陷。证据 data/2026-10-21-audit-browser.json、data/2026-10-21-audit-client-mobile.png。
- 发现已有 5173 监听服务属于其他应用，没有修改或终止；后续使用浏览器虚拟域名路由隔离，未启动替代服务器。
- git diff --check 通过；未跟踪新文件不在普通 git diff 检查覆盖内，已由构建/测试编译覆盖 Java 变动。

## 纠正先前判断

此前记录22将身份失败描述成 dashboard 自循环并不准确：navigation.js 的客户白名单包含 dashboard，缺失角色也可以访问该路径，原代码不是必然循环。已加入的身份失败提示仍有价值，可避免身份读取失败后按客户态继续呈现，但不应把未复现循环当事实。

## 未完成验收

真实模型质量与凭据、生产认证网关剥离伪造头、MySQL schema/事务恢复、pgvector 检索隔离、对象存储所有权、所有页面全流程和安全渗透均未完成。优先修复前三项隔离/身份风险，再接入真实环境验收。
