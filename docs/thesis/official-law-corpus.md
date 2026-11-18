# 官方法源文本快照

截至 2026-11-18，本轮从以下**官方机构网站**取得三份公开法律文本，转为纯文本 Markdown 放在 `data/legal/raw/`，供后续法条切分。来源网址、原网页及提取文本的 SHA-256、通过日期、生效日期和逐条条数详见 [official-law-sources.json](./official-law-sources.json)。提取时去掉网站导航和重复目录，保留通过说明、章节及全部条文，不改写条文；`node --test front/scripts/official-law-corpus.test.mjs` 核验文件哈希、条号连续性和末条施行日期。

| 文本 | 网页来源 | 条数 | 本地文件 |
| --- | --- | ---: | --- |
| 个人信息保护法 | [最高人民检察院法律法规页面](https://www.spp.gov.cn/spp/fl/202108/t20210820_527244.shtml) | 74 | `data/legal/raw/中华人民共和国个人信息保护法_20210820.md` |
| 法律援助法 | [最高人民检察院法律法规页面](https://www.spp.gov.cn/spp/fl/202108/t20210820_527243.shtml) | 71 | `data/legal/raw/中华人民共和国法律援助法_20210820.md` |
| 数据安全法 | [国家保密局法律页面](https://www.gjbmj.gov.cn/n1/2025/1208/c409088-40619671.html) | 55 | `data/legal/raw/中华人民共和国数据安全法_20210610.md` |

**状态边界：**这些是官网公开页面的版本化文本快照，不是已与全国人大现行法律法规数据库逐条核对的最新有效版本；manifest 的 `currentVersionVerified` 为 `false`。网页 SHA 只记录抓取时收到的响应，未归档网页字节；文本 SHA 以 UTF-8 和 LF 换行为准，校验脚本兼容 Windows 检出时的 CRLF。本轮已通过上传 API 登记三份原件，PostgreSQL 中三个版本均为 STORED，随后仅调用统计接口验证解析（3 份成功、0 失败、372 个片段）；尚未调用向量入库接口，索引表仍为 0 行。最初检查发现本地 qwen3-embedding:4b 默认输出 2560 维，现有 vector_store_openai 表为 vector(1536)，不能直接混用；随后已验证当前本机服务支持显式请求 1536 维，详见下文。未改变表结构或既有向量，未宣称已支持新法源检索。

连接可用的后端并确认目标存储目录后，先用 `GET /api/legal/documents/stats` 比对预期文档及条数，再按实际部署方式上传：开发机使用与后端配置一致的 `APP_LEGAL_STORAGE_ROOT`，可从 `raw/` 扫描；启用目录与原件存储的部署环境应优先用 `POST /api/legal/documents/upload`（`corpusType=PUBLIC_LAW`，首次上传不传 `documentId`）登记原件，然后调用 `POST /api/legal/documents/ingest`。最后查看返回的每文件状态、成功数与 chunk 数，并在检索界面检查本轮片段的标题、条号、原文及版本。若已有同一逻辑文档，必须先按版本替代流程核对身份和旧校验和，不直接覆盖或重放原件。正式法律问答使用前仍需核对现行效力和具体适用时间。

## 本地上传与联调记录

2026-11-18 实际上传时发现 PostgreSQL JDBC 不接受直接以 setObject 绑定 Instant 登记时间，已将目录仓储改为 setTimestamp(Timestamp.from(...)) 并补回归测试，重试后三份上传均成功。仅应用现有初始化脚本中的目录表及索引增量建表，不删除或覆盖已有文档。后端以临时管理令牌、仅监听 127.0.0.1 启动，完成后已关闭；令牌未写入项目文件。

在线暂存根目录为 `data/legal/live`，与仓库法源快照目录分离，避免重复扫描同一正文；对象原件位于 `data/legal/objects`。后续启动在线导入时必须继续指向同一暂存根。登记编号如下，当前均为 STORED：

- 中华人民共和国个人信息保护法：`afb40293a4af40e6a63effbf6b76042a`。
- 中华人民共和国法律援助法：`6104db51be8e4d5ba372cd56256c7f47`。
- 中华人民共和国数据安全法：`99dcbcd5d7a44cd28abdf493f2787608`。

上传后观察到统计中 `lawName` 显示存储 UUID，而非法律标题。已修复读取逻辑：正文首个非空行的书名号标题仍优先使用；没有该标题时改用上传元数据中的原始文件名（去除扩展名），不再使用 UUID 暂存文件名。回归测试覆盖重新扫描暂存目录后的原名恢复、正文标题优先及私有材料不产生法律名称。2026-11-18 14:36（Asia/Shanghai）以最新打包产物启动仅监听本机的临时后端，调用带临时管理令牌的 `GET /api/legal/documents/stats`，按 UTF-8 原始字节解码后核对三份上传原名、法律名称及逐文件片段数，均与预期一致。名称保留原文件名中的日期后缀，不代表已完成规范法名或现行效力认定。该接口未触发入库，随后查询向量表仍为 0 行；临时后端已关闭，未保存管理令牌。

372 是程序解析片段数，不是检索命中数或法律效果评测结果。上传标题的在线显示核对和显式维度请求的本机预检已完成；下一步需在后端实际启用相同维度配置，再完成入库和真实检索核对。

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

14:50（Asia/Shanghai）实际预检返回 `tableDimensions=1536`、`modelDimensions=1536`、`requestedDimensions=1536`、`dimensionsMatch=true`，退出码 `0`；随后向量表仍为 0 行。新增测试还验证：服务端忽略维度请求时不能报告匹配，非法维度在数据库/模型请求前被拒绝。本次未启动带该配置的后端，也未执行三份文档的批量入库或检索效果实验。
