# 企业级法律顾问运行方案

## 运行模式

默认使用 `offline` profile。该模式不创建 MySQL、pgvector、OpenAI 或 MCP 模型 Bean；检索与模型调用会返回明确的禁用诊断，工作流进入人工复核，不生成未经证据支持的法律结论。

切换在线模式前，按以下顺序准备：

1. 准备 MySQL、PostgreSQL/pgvector、对象存储或挂载的 `data/legal/raw`。
2. 配置 `MYSQL_*`、`PGVECTOR_*`、`AI_*` 环境变量，不把密钥写入 YAML 或代码。
3. 先以 `APP_INFRASTRUCTURE_ENABLED=true` 启用数据层，确认 `/api/governance/readiness` 中检索能力为 `READY`。
4. 配置客户端、模型、embedding 与 MCP 关联关系，再设置 `APP_MODEL_RUNTIME_ENABLED=true`。
5. 使用离线评测集验证召回率、引用可追溯性、租户隔离和拒答率，达标后才允许自动报告对外流转。

## 检索与知识库

- 向量和中文 trigram 词法检索使用同一组 `knowledgeTag`、`ingestion_status=active`、`corpus_type`、可选 `tenantId + matterId + acl_subject_id` 及白名单等值过滤条件。
- 当前词法召回是 PostgreSQL `pg_trgm`，不是 BM25。它适合作为中文连续文本的低运维基线；法规和案例规模扩大后，应以 OpenSearch/Elasticsearch 中文分析器提供真正的 BM25 召回，再继续使用 RRF 融合。
- RRF 合并会去重，并在高置信度领域路由下应用法域过滤。
- 每次检索返回来源健康度、耗时、召回数、上下文字符数、丢弃数和复核原因。
- 单路故障为 `DEGRADED`，双路故障为 `UNAVAILABLE`；任一状态均不允许自动法律推理。
- 上下文有硬字符预算、单文档证据上限和来源多样性约束，避免重复条款挤占模型上下文。
- 入库版本由源文件 SHA-256、语料类型和分片算法版本共同生成，chunk 带 `doc_id`、`parent_ref`、`section_path`、`source_checksum`、`document_version`、`chunk_schema_version`、`effective_date` 和 `ingestion_status` 元数据。未变化且分片数完整的文档跳过 embedding；变更文档分批写为 `staging`，全部校验成功后统一激活。
- 入库同时使用 JVM 运行锁和 PostgreSQL advisory lock。激活事务只清理 `ingestion_source=legal-filesystem` 所有者范围内的同文档旧版本、已变为空文档的数据和原始目录中已删除文件的孤儿向量；读取或分片失败的仍在目录内文档保留上一激活版本。原始目录可通过 `APP_LEGAL_STORAGE_ROOT` 配置。
- 预处理清除分页噪声、不可见字符和非法控制字符，并提取施行日期。`PUBLIC_LAW` 按编/章/节/条/款/项切片，`PUBLIC_CASE` 按审理经过、诉辩主张、本院查明、本院认为和裁判结果等章节切片，`FIRM_KNOWLEDGE` 与 `MATTER_PRIVATE` 按 Markdown、编号标题和合同条款切片；章节内再按句界执行有重叠的长度窗口。切片大小与重叠量通过 `APP_LEGAL_CHUNK_MAX_CHARS`、`APP_LEGAL_CHUNK_OVERLAP_CHARS` 配置，并与语料类型一起进入分片版本指纹。
- 检索采用路由约束的查询重写、向量召回、`pg_trgm` 词法召回、RRF 融合和二阶段重排序。默认重排序器是本地可解释特征实现；`ILegalEvidenceReranker` 为后续 cross-encoder 模型保留替换边界。
- 上下文压缩按 query 选择相关句及邻句，再执行来源多样性、重复句消除和字符预算控制。预算可通过 `APP_LEGAL_CONTEXT_BUDGET_CHARS` 等参数调整。

## 大规模法律文件存储

原件、目录、检索索引采用三个独立边界，不能把向量表当作文件仓库：

1. 原件层：`ILegalObjectStorage` 按原始字节 SHA-256 内容寻址且禁止原地覆盖。默认写入 `APP_LEGAL_OBJECT_STORAGE_ROOT`，生产通过 `APP_LEGAL_OBJECT_STORAGE_PROVIDER=minio` 切换 MinIO/S3。生产 bucket 还必须在基础设施侧启用 versioning、object lock/WORM、服务端加密和保留策略，应用级 `If-None-Match` 不能替代存储治理。
2. 目录层：PostgreSQL `legal_document`、`legal_document_version`、`legal_document_acl` 保存文档身份、版本、原件 URI、校验和、效力期间、替代关系、权限和入库状态。
3. 检索层：解析副本进入 `data/legal/raw`，分块后写 pgvector 和词法索引。chunk 的 `source_checksum` 校验清洗后文本，目录版本通过不可变 `object_uri` 对齐原件，二者不混用。重新清洗、换 embedding 或调整 chunk 参数只重建索引，不修改原件。

上传使用 `POST /api/legal/documents/upload`，参数包括 `file`、`corpusType`，以及按需提供的 `documentId`、`matterId`、`supersedesVersion`、`authorityLevel`、`effectiveFrom`、`effectiveTo`。首次上传不传 `documentId`；同一逻辑文档的新版本复用原 `documentId` 并提供被替代版本的完整 SHA-256。

上传成功只表示原件和目录版本处于 `STORED`。批量上传结束后调用 `POST /api/legal/documents/ingest`，待分块完整性校验和两阶段向量发布成功后，目录版本才切换为 `ACTIVE`，同文档旧版本标为 `SUPERSEDED`。已有 PostgreSQL 数据卷不会重新执行 Docker 初始化脚本，升级环境必须先人工应用 [init.sql](./dev-ops/agent-environment/pgvector/sql/init.sql) 中新增的目录表和索引，再启动新版应用。

语料隔离规则固定如下：

| corpusType | 用途 | 强制作用域 |
|---|---|---|
| `PUBLIC_LAW` | 法律、行政法规、司法解释 | 不携带租户和案件 |
| `PUBLIC_CASE` | 公开裁判文书、指导案例 | 不携带租户和案件 |
| `FIRM_KNOWLEDGE` | 律所模板、研究成果 | `tenantId` |
| `MATTER_PRIVATE` | 客户材料、案件证据、工作底稿 | `tenantId + matterId + actorId` |

`MATTER_PRIVATE` 检索缺少任一作用域会直接失败。租户和主体来自认证网关注入的 `TenantContext`，不接受客户端自行声明。当前最小权限实现把私有文档绑定到上传者；团队共享必须通过显式 ACL 授权和重新发布可见主体，不能移除 `acl_subject_id` 过滤。`legal-hierarchy-v5` 把作用域与语料感知章节路径写入每个 chunk；升级后应重新执行入库，使旧 schema 的 chunk 不再参与检索。私有暂存目录缺失 sidecar 或目录与元数据冲突时，整个扫描失败关闭，不会降级成公共语料。

工作流在普通咨询时查询公共法规，诉讼流程同时查询公开案例；存在服务端受管的 `matterReference` 且捕获到认证租户和主体时，再查询该主体可访问的案件私有语料，最后统一重排和压缩。SSE 异步执行前会复制认证标识，避免线程切换丢失 `ThreadLocal` 上下文。任一已请求作用域不可用或证据不足时，联邦检索标为 `DEGRADED/INSUFFICIENT` 并进入人工复核。

容量规划不要先猜总文件数，应先抽样测量：`原件容量 = 文件数 × 平均文件大小 × 版本系数 × 副本系数`；`向量容量 ≈ chunk 数 × embedding 维度 × 4 字节 × 1.3~2.0 索引系数`。先用一万份代表性文档测每份 chunk 数、OCR 后文本量、召回质量和 p95 延迟，再决定分片、冷热分层与 OpenSearch 节点规模。

法庭场景不能直接依赖在线知识库。开庭前需要生成按 `tenantId + matterId` 冻结、加密并可离线校验的案件包，至少包含原件、引用快照、版本清单、SHA-256、离线词法/向量索引和导出审计；运行时断网、缺索引、校验失败或引用版本不一致必须停止生成。当前代码已具备不可变原件、版本目录和案件隔离基础，但离线冻结包尚未实现，因此不能标记为庭审生产就绪。

## 多 Agent 编排

默认 DAG 为：委托受理与冲突审查 → 意图识别 → 材料解析 → 合同/案卷路由 → 事实时间线 → 混合检索 → 争点与举证责任 → 证据三性与缺口 → 管辖与程序期限 → 法律推理 → 对方最强主张 → 双边诉讼策略 → 风险 → 引用 → 确定性复核 → 律师签发 → 内部工作底稿。

- `INTAKE` 是最前置的确定性门禁。诉讼案件缺少实际委托方、诉讼地位、对方、冲突审查、委托授权、程序类型、阶段或法院/机构时，状态为 `BLOCKED`；后续节点发出 `NODE_SKIPPED`，不读取业务上传材料、不调用检索或模型。
- 禁止行为检测采用失败关闭原则。伪造、隐匿或毁灭证据，虚假陈述，操纵或威胁证人，贿赂或不当接触司法人员，以及规避执行等请求均停止执行。该规则同时适用于原告/申请人和被告/被申请人。
- `FACTS` 只区分客户陈述、已证实事实、待核事实和来源。`ISSUES` 必须位于检索之后，基于可核验依据形成请求权/抗辩要件及举证责任矩阵。
- `EVIDENCE` 对真实性、关联性、合法性、证明目的、保全与补强动作逐项登记；只提供合法取证方案。
- `OPPONENT` 独立提出对方最强论证和可能证据，`STRATEGY` 再整合本方主位/备选方案、反请求或抗辩、保全、和解及执行路径，避免单方确认偏差。
- `REVIEW` 和 `LAWYER_REVIEW` 都是确定性节点。任何诉讼输出固定 `lawyerSignoffRequired=true`、`reportIssuable=false`，只能作为内部工作底稿，不能承诺胜诉或直接对外签发。

## 诉讼案件数据契约

JSON、multipart、工作流变量和 SSE 统一使用以下字段：

- 受理：`matterReference`、`representedPartyName`、`partyRole`、`opponentNames`、`conflictCheckCompleted`、`conflictCheckReference`、`engagementAuthorized`、`engagementReference`。
- 程序：`proceedingType`、`proceedingStage`、`courtOrTribunal`、`caseNumber`、`nextDeadline`、`deadlineSource`。日期固定使用 `yyyy-MM-dd`；期限来源必须可回溯到法定规则、送达文书或机构通知。
- 分析产物：`factsTimeline`、`issuesMatrix`、`evidenceMatrix`、`proceduralAnalysis`、`opponentAnalysis`、`litigationStrategy`、`lawyerReviewStatus`。

当前前端事项数据保存在浏览器本地，仅用于无数据库条件下验证流程。历史诉讼事项迁移时不会默认冲突审查或授权已经完成，必须在“更新受理”中人工确认。

浏览器不能设置 `tenantId`、`knowledgeTag`、`topK`、`workflowBlocked` 或 `reviewRequired`。当前服务端固定使用受管知识空间和检索上限，并丢弃外部控制字段。接入统一身份后，`tenantId` 必须从已认证的安全上下文注入，且在向量、全文、对象存储、缓存、审计和 SSE 查询中使用同一隔离条件；在此之前不得把多租户私有案卷写入共享知识空间。

## 材料上传授权

工作流上传采用两阶段协议：先调用 `POST /api/workflows/uploads/authorize` 执行确定性受理预检，再携带五分钟有效、一次性使用的 `X-Workflow-Upload-Authorization` 调用 multipart 上传。令牌绑定服务端受管模型路由、请求文本和规范化后的案件字段；字段不一致、重复使用或过期均拒绝。控制器在异步处理前把文件写入受控临时目录，复制失败、工作流结束或队列拒绝时删除，且不提供临时文件下载端点。

当前令牌仅保存在单进程内存中，适用于无数据库阶段的流程验证，不是多实例生产实现。正式环境必须由身份主体申请、与 `tenantId + matterId + uploadManifest` 绑定，持久化或签名保存授权记录，并支持撤销、幂等、跨实例校验和审计。对象存储还应启用恶意文件扫描、静态加密、保留策略、法律保全和证据 SHA-256 清单。

`clientId` 在当前工程中是模型运行时路由标识，不是客户身份或租户身份。法律工作流 API 不接受客户端提供的 `clientId`，统一使用服务端 `WORKFLOW_MODEL_CLIENT_ID`；生产环境应进一步由模型路由策略按操作类型选择批准的模型版本。认证后的用户、组织、岗位、Matter ACL 和承办团队权限共同决定案件访问范围，不能复用模型路由标识充当身份。

## 正式案件系统落地

启用中间件后，至少拆分以下持久化边界，不应继续以浏览器本地状态作为事实来源：

1. `matter/party/engagement/conflict_check`：当事人别名、关联方和历史客户用于冲突图谱，冲突结果需记录规则版本、执行人、时间与覆盖范围。
2. `case_file/evidence_item/evidence_version`：原始文件进入对象存储，元数据保存 SHA-256、来源、取得方式、保管人、版本和访问审计，禁止覆盖原件。
3. `issue/burden/evidence_link`：争点、法律要件、举证责任和证据之间使用显式关联，不只保存一段模型文本。
4. `deadline/procedural_event`：期限必须保存依据、起算日、法定/法院指定类型、时区、复核人和多级提醒；模型只能建议，不能自动确认起算日。
5. `workflow_run/agent_run/prompt_version/model_version/citation_snapshot`：保留输入摘要、模型与提示词版本、检索诊断、引用版本和人工修改轨迹，敏感正文按最小权限单独存储。
6. `review/signoff`：承办律师、复核律师、审批意见、退回原因和签发版本不可与 AI 原始产物相互覆盖。

## 模型与评测策略

- 事实抽取、分类和格式修复优先使用低温度、低成本模型；争点、对方论证和策略使用高推理模型。各任务使用独立操作名，便于后续做模型路由和成本预算。
- 所有诉讼 Agent 输出固定 JSON 顶层字段；无法解析时最多进行一次受治理的格式修复，仍失败则保存 `MODEL_OUTPUT_INVALID` 并进入人工复核。
- 不展示未经校准的“胜诉率”。结果表达为事实假设下的场景、主要不确定性和可能裁判路径；只有建立带时间切分、案由分层和校准曲线的离线评测体系后，才允许展示概率。
- 上线评测按原告/被告、案由、程序阶段和法域分桶，至少度量事实遗漏率、争点覆盖率、举证责任准确率、引用支持率、过期法命中率、对方论证覆盖率、禁止建议率和律师修改率。
- 生产环境对模型输出做提示注入隔离：案卷正文仅作为不可信数据，不允许其中的指令改变系统策略、工具权限、租户过滤或签发规则。

## 模型运行时治理

- 每个客户端有并发信号量，超限立即返回 `MODEL_CONCURRENCY_LIMIT`。
- 连续失败触发客户端级熔断，窗口内快速失败并返回 `MODEL_CIRCUIT_OPEN`。
- 仅无工具的幂等读取调用允许最多 2 次有界重试；工具/MCP 调用固定单次，避免副作用重复执行。
- 日志只记录操作名、耗时、客户端和错误类型，不记录 API key、完整提示词或知识库正文。

结构化输出还必须通过任务级字段契约：对象校验必需顶层字段，数组校验每个元素字段；修复后的输出标记 `valid/repaired`，兜底输出标记 `fallbackUsed` 并强制进入 `REVIEW_REQUIRED`。这比单纯判断 JSON 是否可解析更严格，也避免数组被错误截取为内部对象。

`/api/governance/readiness` 中的 `CONFIGURED_UNVERIFIED`、`PLANNED` 和 `ARMED_UNVERIFIED` 不代表生产就绪。生产 profile 默认强制认证主体上下文（`APP_ENTERPRISE_SECURITY_CONTEXT_ENABLED=true`），可信代理请求头仍默认关闭，仅在边界代理明确剥离外部同名头后才通过 `APP_ENTERPRISE_TRUSTED_CONTEXT_HEADER_ENABLED=true` 开启。只有数据库/向量库健康检查、认证租户上下文、检索评测和明确的 `APP_LEGAL_AUTOMATION_RELEASE_ENABLED=true` 发布门禁同时满足时，才允许受控自动推理；诉讼报告仍必须由执业律师签发。

法律文档换版采用两阶段发布：确定性 chunk id 作为幂等键，全部 embedding 批次先写为 `staging`，按 `doc_id + document_version + ingestion_run_id` 校验分片总数和索引唯一性，再在数据库事务中切换为 `active` 并清理旧版本。失败批次会清理暂存数据，旧激活版本继续提供检索。生产运维仍需为连续失败增加告警、重试队列和批次审计看板。

上传文件在扩展名和声明 MIME 之外执行文件头校验：PDF、Office、图片、视频和文本均拒绝明显伪装内容；DOCX 还确认 OOXML 目录结构。该校验不是恶意文件扫描的替代品，生产对象存储仍需接入病毒/木马扫描和证据保全链。

知识库原始目录同样执行文档类型和可配置单文件上限，默认 200 MB，由 `APP_LEGAL_MAX_SOURCE_BYTES` 控制；被跳过的文件应在正式 manifest 中记录原因，不能静默当作“知识库为空”。

## 上线前必须完成

- 证据召回评测：按法域、法规版本、租户和文档类型分桶。
- 引用核验：每个结论可追溯到 `doc_id + document_version + article_no + clause_no + chunk_index`。
- 负面测试：空知识库、单路故障、错误法域、过期法规、模型超时、MCP 重试和跨租户查询。
- 运行观察：`/api/governance/readiness`、`/api/governance/health`、工作流 SSE 的检索诊断与人工复核事件。
- 访问控制：移除开发 token，接入 OIDC/SSO、组织与 Matter 级 RBAC/ABAC，确保上传、SSE、复核、签发和知识检索均记录主体与授权依据。
- 分布式上传：以持久化或签名授权替换当前内存令牌，完成对象存储隔离、恶意文件扫描、证据哈希和保管链审计。
