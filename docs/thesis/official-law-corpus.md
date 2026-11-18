# 官方法源文本快照

截至 2026-11-18，本轮从以下**官方机构网站**取得三份公开法律文本，转为纯文本 Markdown 放在 `data/legal/raw/`，供后续法条切分。来源网址、原网页及提取文本的 SHA-256、通过日期、生效日期和逐条条数详见 [official-law-sources.json](./official-law-sources.json)。提取时去掉网站导航和重复目录，保留通过说明、章节及全部条文，不改写条文；`node --test front/scripts/official-law-corpus.test.mjs` 核验文件哈希、条号连续性和末条施行日期。

| 文本 | 网页来源 | 条数 | 本地文件 |
| --- | --- | ---: | --- |
| 个人信息保护法 | [最高人民检察院法律法规页面](https://www.spp.gov.cn/spp/fl/202108/t20210820_527244.shtml) | 74 | `data/legal/raw/中华人民共和国个人信息保护法_20210820.md` |
| 法律援助法 | [最高人民检察院法律法规页面](https://www.spp.gov.cn/spp/fl/202108/t20210820_527243.shtml) | 71 | `data/legal/raw/中华人民共和国法律援助法_20210820.md` |
| 数据安全法 | [国家保密局法律页面](https://www.gjbmj.gov.cn/n1/2025/1208/c409088-40619671.html) | 55 | `data/legal/raw/中华人民共和国数据安全法_20210610.md` |

**状态边界：**这些是官网公开页面的版本化文本快照，不是已与全国人大现行法律法规数据库逐条核对的最新有效版本；manifest 的 `currentVersionVerified` 为 `false`。网页 SHA 只记录抓取时收到的响应，未归档网页字节；文本 SHA 以 UTF-8 和 LF 换行为准，校验脚本兼容 Windows 检出时的 CRLF。三份原件已通过上传 API 登记，首次统计解析为 3 份成功、0 失败、372 个片段。最初发现默认 2560 维输出与现有 vector(1536) 表不兼容，随后接入显式维度配置；2026-11-18 15:38 实际入库成功，现为 372 条 1536 维 active 向量、三份 ACTIVE 目录版本，已完成下文所述数据库检索冒烟。未改变表结构，不将索引激活等同于现行效力确认或完整问答验证。

连接可用的后端并确认目标存储目录后，先用 `GET /api/legal/documents/stats` 比对预期文档及条数，再按实际部署方式上传：开发机使用与后端配置一致的 `APP_LEGAL_STORAGE_ROOT`，可从 `raw/` 扫描；启用目录与原件存储的部署环境应优先用 `POST /api/legal/documents/upload`（`corpusType=PUBLIC_LAW`，首次上传不传 `documentId`）登记原件，然后调用 `POST /api/legal/documents/ingest`。最后查看返回的每文件状态、成功数与 chunk 数，并在检索界面检查本轮片段的标题、条号、原文及版本。若已有同一逻辑文档，必须先按版本替代流程核对身份和旧校验和，不直接覆盖或重放原件。正式法律问答使用前仍需核对现行效力和具体适用时间。

## 本地上传与联调记录

2026-11-18 实际上传时发现 PostgreSQL JDBC 不接受直接以 setObject 绑定 Instant 登记时间，已将目录仓储改为 setTimestamp(Timestamp.from(...)) 并补回归测试，重试后三份上传均成功。仅应用现有初始化脚本中的目录表及索引增量建表，不删除或覆盖已有文档。后端以临时管理令牌、仅监听 127.0.0.1 启动，完成后已关闭；令牌未写入项目文件。

在线暂存根目录为 `data/legal/live`，与仓库法源快照目录分离，避免重复扫描同一正文；对象原件位于 `data/legal/objects`。后续启动在线导入时必须继续指向同一暂存根，并保持与本次索引一致的模型和 1536 维配置。登记编号如下，15:38 后目录版本均为 ACTIVE：

- 中华人民共和国个人信息保护法：`afb40293a4af40e6a63effbf6b76042a`。
- 中华人民共和国法律援助法：`6104db51be8e4d5ba372cd56256c7f47`。
- 中华人民共和国数据安全法：`99dcbcd5d7a44cd28abdf493f2787608`。

上传后观察到统计中 `lawName` 显示存储 UUID，而非法律标题。已修复读取逻辑：正文首个非空行的书名号标题仍优先使用；没有该标题时改用上传元数据中的原始文件名（去除扩展名），不再使用 UUID 暂存文件名。回归测试覆盖重新扫描暂存目录后的原名恢复、正文标题优先及私有材料不产生法律名称。2026-11-18 14:36（Asia/Shanghai）以最新打包产物启动仅监听本机的临时后端，调用带临时管理令牌的 `GET /api/legal/documents/stats`，按 UTF-8 原始字节解码后核对三份上传原名、法律名称及逐文件片段数，均与预期一致。名称保留原文件名中的日期后缀，不代表已完成规范法名或现行效力认定。该接口未触发入库，随后查询向量表仍为 0 行；临时后端已关闭，未保存管理令牌。

372 最初是程序解析片段数，后续已独立核对数据库也确有 372 条已激活向量；该数值不是检索命中总数或法律效果评测结果。上传标题、显式维度、实际入库与数据库检索冒烟均已有记录，仍待应用混合检索、问答引用核验全链路及独立标注评测。

## 入库前只读维度预检

为避免在批量入库时才发现维度不匹配，新增 `scripts/check-legal-embedding.mjs`。它通过 Docker 内的 psql 只读查询 `public` 下目标表的 `embedding` 列类型，再向选定的 OpenAI 兼容嵌入端点发送一条固定的非案件文本。不会上传法律原件、读取真实案件、修改表结构、截断向量或调用入库接口。探针会触发一次模型推理，外部服务可能计费。

本机 PowerShell 示例（环境变量仅设置在当前进程，使用后按需恢复）：

```powershell
$env:EMBEDDING_MODEL = 'qwen3-embedding:4b'
$env:EMBEDDING_BASE_URL = 'http://127.0.0.1:11434/v1'
node scripts/check-legal-embedding.mjs
$LASTEXITCODE
```

`EMBEDDING_MODEL` 必填，应先核对应用实际采用的 `ai_embedding_config` 活跃配置；本脚本不读取 MySQL，也不会自动发现或覆盖应用配置。`EMBEDDING_BASE_URL` 必须是以 `/v1` 等 API 前缀结尾的地址，不包含 `/embeddings`；默认使用上述本机地址。外部服务仅允许 HTTPS，禁止 URL 内嵌凭据、查询参数及重定向。若确需鉴权，仅通过 `EMBEDDING_API_KEY` 环境变量传递，不写命令示例或项目文件。

默认容器 `vector-db-agent`、数据库 `springai`、用户 `postgres`、向量表 `vector_store_openai`。部署不同可分别设置 `PGVECTOR_CONTAINER`、`PGVECTOR_DATABASE`、`PGVECTOR_USERNAME`、`APP_LEGAL_VECTOR_TABLE`；表名仅允许小写 SQL 标识符。数据库检查依赖本机 Docker 权限与容器内 postgres 用户可运行 psql 的条件，不读取或输出数据库密码。

- 退出码 `0`：仅表示单次响应向量长度与表声明维度相同，且显式请求维度（如有）与响应长度一致；不代表模型版本一致、语义空间兼容、召回质量合格或可以发布法律结论。已有向量仍需核对模型身份与版本，模型更换需另行制定重建方案。
- 退出码 `1`：已成功检查，但维度不一致，应停止后续入库并人工确定兼容方案。
- 退出码 `2`：配置、数据库访问、网络、响应结构或向量类型检查失败；不输出上游响应正文与原始异常。

2026-11-18 14:19（Asia/Shanghai）运行结果：表声明 `vector(1536)`，本机 `qwen3-embedding:4b` 返回 2560 维，`dimensionsMatch=false`，退出码为 `1`。该记录仅证明维度冲突仍存在，不是修复成功或检索效果数据。6 项专项测试覆盖匹配/不匹配、只读 SQL、无效配置、列类型异常、畸形向量和异常信息脱敏，已纳入前端目录下统一 Node 测试命令。

## 显式维度配置的兼容性验证

2026-11-18 14:45 后，针对本机 Ollama 0.24.0 发送相同固定探针：不提供 `dimensions` 时响应为 2560 维，提供 `dimensions=1536` 时响应为 1536 维。由服务端处理请求参数，客户端没有截断或填充向量；该现象只证明当前服务和模型的接口行为，不推广为所有嵌入模型均支持指定维度，也不证明缩维后召回质量合格。

后端增加可选 `AI_EMBEDDING_DIMENSIONS`（映射为 `spring.ai.embedding.dimensions`）。默认 `0` 表示不发送维度参数，保留既有行为；正整数同时用于嵌入请求选项和 PgVectorStore 配置，负数拒绝启动。此配置不修改数据库表结构、不重写已有向量；模型配置变更后须重新检查，不能仅凭维度相同混用不同模型的向量。配置回归测试包含查询文本与文档嵌入均发送维度参数的本机 HTTP 测试。

预检工具另使用 `EMBEDDING_DIMENSIONS` 指定探针请求维度，未设置则保持默认请求；若设置，必须为正整数。它不会自动继承后端的 `AI_EMBEDDING_DIMENSIONS`，操作时必须核对两者一致。示例：

```powershell
$env:EMBEDDING_MODEL = 'qwen3-embedding:4b'
$env:EMBEDDING_BASE_URL = 'http://127.0.0.1:11434/v1'
$env:EMBEDDING_DIMENSIONS = '1536'
node scripts/check-legal-embedding.mjs
# 仅在核对目标库、模型及既有向量身份后，为之后启动的后端设置：
$env:AI_EMBEDDING_DIMENSIONS = '1536'
```

14:50（Asia/Shanghai）实际预检返回 `tableDimensions=1536`、`modelDimensions=1536`、`requestedDimensions=1536`、`dimensionsMatch=true`，退出码 `0`；当时向量表仍为 0 行。新增测试还验证：服务端忽略维度请求时不能报告匹配，非法维度在数据库/模型请求前被拒绝。之后已用同一维度配置启动后端并执行下述真实入库，但尚未执行独立检索效果实验。

## 实际入库与数据库检索冒烟

原始证据记录见 [legal-index-smoke-2026-11-18.json](./legal-index-smoke-2026-11-18.json)。入库前再次对三份暂存文本按 UTF-8/LF 计算 SHA-256，均与官方来源清单一致；只使用这三份公开法律快照，不上传案件材料，不调用外部聊天模型。临时后端使用 `dev` 配置、`APP_LEGAL_STORAGE_ROOT=data/legal/live`、`AI_EMBEDDING_DIMENSIONS=1536`，仅监听 127.0.0.1，管理令牌仅存在于进程内存。

第一次使用 batch=8 和 600 秒 HTTP 超时，请求超时后关闭临时后端。随后数据库检查为 157 条 staging、0 条 active，目录仍有三份 STORED 版本，不能记为成功。第二次使用 batch=32、2300 秒 HTTP 超时；应用先清理旧 staging，再重新入库。15:38:36 返回 3 份成功、0 失败、372 个片段，接口记录耗时 1267178 毫秒。这个单次开发机耗时含模型推理和批次写入，不能当作性能基准或 p95 延迟。

独立 SQL 核对结果为：372 条 active、0 条 staging，全部向量维度 1536；目录三份版本均为 ACTIVE，片段数分别为法律援助法 141、数据安全法 74、个人信息保护法 157。随后关闭临时后端并恢复启动前的环境变量，未保存管理令牌。**后续启动仍须显式设置 `AI_EMBEDDING_DIMENSIONS=1536`，默认 0 不会自动发现现有索引维度。**

三条固定开发者冒烟问题使用本机嵌入端点产生 1536 维查询向量，再以 pgvector 余弦距离检索前 5 条；SQL 仅允许 `knowledge=law-consultant`、`ingestion_status=active`、`corpus_type=public_law`，不检索私有材料。结果首条如下，只记录工程观察，不解释法律适用：

| 固定问题主题 | 首条结果法律 | 首条条号 | 原件与目录关联 |
| --- | --- | --- | --- |
| 处理个人信息时取得同意的要求 | 个人信息保护法 | 第十三条 | ACTIVE 版本匹配 |
| 经济困难申请法律援助的材料说明 | 法律援助法 | 第四十一条 | ACTIVE 版本匹配 |
| 数据安全管理制度与教育培训 | 数据安全法 | 第二十七条 | ACTIVE 版本匹配 |

证据记录保留每条结果的 chunk ID、目录文档 ID、条号、规范化文本校验和、分片版本及原件 SHA-256；不保存向量、密钥或机器绝对路径。规范化文本校验和与原件文件 SHA-256 含义不同，不能互相替代。新增的两项 Node 测试只校验该记录的内部一致性、法源身份与数据边界，不会重新请求运行中的数据库，不能算成两项在线检索测试。

本次验证是“本机嵌入服务 + 数据库过滤与排序”的冒烟，不经过应用层混合检索、重排、回答生成和引用核验；三条题目不是独立测试集，不能据此计算论文准确率或宣称 100% 召回。尚待完成现行版本核对、完整问答链路与独立标注评测。同步入库的长请求还暴露了工程待办：应改进后台任务、进度查询及取消/恢复机制，不能只延长 HTTP 超时来解决长期运维问题。

## 17:35 本机应用层混合检索冒烟

新增 `LocalLegalRetrievalSmokeTest`，默认跳过，仅在进程环境变量 `RUN_LOCAL_LEGAL_RETRIEVAL_SMOKE=true` 时运行。该测试固定访问本机 PostgreSQL（15432、springai）与 Ollama（11434、qwen3-embedding:4b、1536 维），数据库密码仅从 `PGVECTOR_PASSWORD` 读取，不读取或发送用户的外部聊天模型密钥。通过连接参数将每个数据库会话设为只读，并用 `show transaction_read_only` 断言；不初始化 schema、不导入文档、不调用聊天模型。

在已安全注入环境变量的 PowerShell 中执行：`mvn -q -pl ddd-agent-sheep-app -am '-Dtest=LocalLegalRetrievalSmokeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。测试直接实例化生产 `LegalEvidenceRepository`、`LegalHybridRetrievalService`、本地重排器与上下文组装器，不启动 HTTP 服务。固定开发者问题询问数据安全制度与培训，显式限定法名 `中华人民共和国数据安全法_20210610` 与第二十七条，并沿用公共法库、active、knowledge 强制过滤。断言两个检索通道都可用且各有结果、最终证据符合全部过滤、版本与校验和元数据存在、上下文非空且不超预算。

2026-11-18 17:33 首次真实执行失败：向量通道可用，关键词通道不可用。独立 SQL 检查确认旧本机数据库缺少 `pg_trgm`，`similarity` 和 `word_similarity` 函数均不存在；仓库现有 `docs/dev-ops/agent-environment/pgvector/sql/init.sql` 已要求该扩展，但旧数据库未应用。随后只执行 `CREATE EXTENSION IF NOT EXISTS pg_trgm`，安装版本 1.6，未重新执行整份初始化脚本，未改文档、向量或表结构；SQL 再核对仍为 372 条 active、全部 1536 维。修复环境后同一测试实际通过：1 项、0 失败、0 错误、0 跳过，Surefire 记录 4.406 秒。该时间只是单次开发机测试记录，不能作为延迟基准。

这是限定已知法名和条号的应用层管线连通检查，不是开放式召回评测，不经过 HTTP 身份认证、回答生成、引用支持关系判断或律师签发，也不证明数据库向量与所有原件逐一一致。它不替代此前三题的数据库冒烟，更不能合并计算准确率。正式评测仍须独立冻结题库、核对现行版本并进行专业标注。

## 18:14 检索命中接入引用核验

在上述同一本机只读测试中追加生产 `LegalClaimCitationVerifier` 的三组断言：取首条真实命中正文最多 80 个字符作为合成正例摘录，要求 `SOURCE_MATCHED` 且核验记录的文档 ID 与命中一致；在同一摘录后追加明确标记的合成伪造文字，先确认它不在规范化原文中，再要求 `QUOTE_NOT_IN_SOURCE`；将证据编号设为超出当前候选列表的编号，要求 `EVIDENCE_ID_UNKNOWN`。三种输入均为开发者构造的测试控制，不是模型生成的法律结论；错误输入只在测试内存中存在，不写入数据库或法源文件。

扩展后的真实测试实际执行通过：1 项、0 失败、0 错误、0 跳过，Surefire 记录 15.27 秒；18:14 独立 SQL 复核仍为 372 条 active、全部 1536 维。默认常规测试仍跳过该本机联调。此处覆盖真实检索命中到来源元数据、摘录匹配的连接，但没有运行聊天生成、HTTP 认证、语义支持判断、现行效力确认或律师签发。正例摘录直接取自检索正文，因而只能作为管线正向控制；不得将通过与拒绝的三组断言算作独立准确率或防幻觉效果。
