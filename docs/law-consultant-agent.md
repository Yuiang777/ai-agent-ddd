# 律师顾问 Agent 技术说明

## 版本

- 当前版本：v1.1
- 本次更新：将法律能力拆分为 domain / app / infrastructure，并补充多智能体雏形说明。
- 适用范围：`ddd-agent-sheep` 当前主分支代码。

## 目标

本项目将律师顾问 Agent 作为一个可配置的 `ChatClient` 使用，核心能力是：法律文档入库、法条感知切片、RAG 检索回答、文档与 chunk 统计、治理审计。

调用入口：

- 普通问答：`POST /api/chat`，请求体中使用 `clientId = law-consultant`。
- 法律文档入库：`POST /api/legal/documents/ingest`。
- 法律文档统计：`GET /api/legal/documents/stats`。
- 法律多智能体雏形：`GET /api/legal/agent/workflow`。

## 架构分层

### domain

负责领域规则与领域模型，不直接依赖向量库、文件系统或 Web 层。

当前包含：

- 法律文档源信息、条、款、chunk 的领域值对象
- 法律文档入库统计模型
- 法律文档切片规则
- 律师顾问多智能体角色与工作流定义

### app

负责应用编排、对外接口、请求响应转换。

当前包含：

- `LegalDocumentIngestionService`
- `LegalDocumentController`
- `LegalAgentApplicationService`
- `LegalAgentController`

### infrastructure

负责基础设施适配：文件读取、Tika 解析、PgVector 写入、manifest 持久化。

当前包含：

- `LegalDocumentRepository`
- `ManifestJsonWriter`

## 法律文档放在哪里

用户收集的法律文档放在项目根目录下：

```text
data/legal/raw/
```

推荐目录结构：

```text
data/legal/
  raw/          原始法律文档，支持 txt、md、pdf、docx
  processed/    清洗后的纯文本或结构化中间文件
  eval/         RAG 评测集，例如 law-rag-eval.jsonl
  manifest/     文档清单和入库统计，例如 law-documents-manifest.json
```

首版建议优先放 `.txt` 或 `.md`，便于保证法条格式稳定。PDF/DOCX 可以读取，但如果源文件排版复杂，应先清洗成纯文本。

## 如何统计总文档数和 chunk 数

接口：

```http
GET /api/legal/documents/stats
```

返回内容包括：

- `totalDocuments`：`data/legal/raw/` 下支持格式的文档总数。
- `successDocuments`：可解析文档数。
- `failedDocuments`：解析失败文档数。
- `totalChunks`：按当前切片策略得到的 chunk 总数。
- `documents`：每个文档的 lawName、chunkCount、状态。
- `chunksByLaw`：每部法律切出的 chunk 数。

真正写入向量库使用：

```http
POST /api/legal/documents/ingest
```

入库后会写出清单：

```text
data/legal/manifest/law-documents-manifest.json
```

## 切片策略：第三条第二款与第三条之二不会混淆

法律文本里：

- `第三条第二款` 表示第三条下面的第二款。
- `第三条之二` 表示独立的“第三条之二”，不是第三条第二款。

因此不能只用默认 `TokenTextSplitter` 直接切，否则可能把条号和正文切散，也可能让模型误把 `第三条之二` 当成 `第三条` 的第二款。

当前入库服务采用法条感知切片：

1. 先用正则识别条文边界：

```text
第[一二三四五六七八九十百千万零〇两]+条(?:之[一二三四五六七八九十百千万零〇两]+)?
```

该规则会把 `第三条` 和 `第三条之二` 识别为两个不同的 article key。

2. 再在条内识别款：

```text
第[一二三四五六七八九十百千万零〇两]+款
```

3. 每个 chunk 都会补充上下文前缀：

```text
《法律名称》 第三条 第二款：正文...
《法律名称》 第三条之二：正文...
```

4. 每个 chunk 的 metadata 会写入：

- `knowledge = law-consultant`
- `doc_id`
- `doc_name`
- `law_name`
- `article_no`
- `clause_no`
- `chunk_index`
- `source_path`

5. 如果单条过长，再做长度切分；切分后的每段仍然带同样的法律名称、条、款前缀，避免语义丢失。

## 如何知道 RAG 有效

不要只看“回答像不像”。建议用固定评测集对比 baseline。

核心指标：

- 检索命中率：问题对应的法律、条、款是否进入 topK。
- 引用准确率：答案引用的法律名称、条、款是否正确。
- 答案可追溯率：答案中的关键结论是否能在检索 chunk 中找到依据。
- 拒答准确率：知识库没有依据时，是否明确拒答而不是编造。
- 相似条款区分率：例如能否区分 `第三条第二款` 与 `第三条之二`。

## 评测集多少条

首版建议 80-120 条，不要一开始追求很多，先保证覆盖面。

建议分布：

- 30 条：精确法条问题，答案来自单一条款。
- 20 条：同一法律内跨条综合问题。
- 20 条：相似条款干扰问题，例如 `第三条第二款` vs `第三条之二`。
- 15 条：知识库无依据问题，测试拒答。
- 15 条：版本/时效问题，测试是否能说明依据版本不足。

## 评测集怎么构建

放在：

```text
data/legal/eval/law-rag-eval.jsonl
```

推荐 JSONL 格式，每行一条：

```json
{"question":"第三条第二款规定了什么？","expected_law":"示例法","expected_article":"第三条","expected_clause":"第二款","expected_answer_points":["要点1","要点2"],"negative_articles":["第三条之二"]}
```

字段说明：

- `question`：用户问题。
- `expected_law`：期望命中的法律名称。
- `expected_article`：期望命中的条。
- `expected_clause`：期望命中的款，没有则为空。
- `expected_answer_points`：答案必须覆盖的要点。
- `negative_articles`：容易混淆但不应引用的条款。

## baseline 是什么

baseline 是对比组，用来证明 RAG 是否真的提升质量。

建议至少做两个 baseline：

1. 无 RAG 的同模型回答：只给问题，不接法律知识库。
2. 关键词检索回答：不用向量检索，只用关键词/标题匹配出的文本回答。

律师顾问 Agent 的 RAG 回答应该在引用准确率、拒答准确率、相似条款区分率上明显优于 baseline。

## 多智能体雏形

律师顾问 Agent 可以拆成四个角色：

- 检索智能体：定位候选法条和上下文。
- 推理智能体：基于候选法条组织结论。
- 审查智能体：检查引用准确性、拒答边界和免责声明。
- 用户交互智能体：整理最终回复并提示用户补充关键信息。

当前版本只实现了工作流雏形，便于后续接入真正的编排执行器。

## Agent 使用的工具

首版工具配置：

- RAG：`RAG_ANSWER` advisor，filter 为 `knowledge == 'law-consultant'`。
- Chat Memory：`CHAT_MEMORY` advisor，保留短期上下文。
- Governance Audit：已有治理接口记录请求、耗时、失败和限流。

首版不默认绑定 MCP 工具。原因是本地 MCP SSE 服务未启动时会导致 Agent 装配失败。后续如果需要外部检索、法院案例库、企业内部系统，再按需接入 MCP。

## 律师顾问 Agent 数据库配置

示例 SQL：

```text
docs/dev-ops/agent-environment/mysql/sql/law-consultant-agent-seed.sql
```

注意：其中 `ai_client_config` 的 model `target_id` 需要改成你本地 `ai_client_model.model_id` 中真实存在的模型 ID。

## 后续你需要准备什么

1. 法律原文文件：优先 `.txt` / `.md`，也可 `.pdf` / `.docx`。
2. 文档来源和版本：发布日期、施行日期、修订日期。
3. 法律名称规范：文件名和正文标题尽量一致。
4. 评测问题集：首版 80-120 条，覆盖精确法条、跨条综合、相似条款、无依据拒答。
5. 期望引用格式：例如固定输出“《法律名称》第X条第Y款”。
6. 是否允许联网：如果不允许，Agent 只能使用本地 RAG 文档。
7. 是否接入 MCP：如果要接法院案例、法规网站或内部系统，需要提供对应 MCP 服务配置。
8. 已泄露过的 API Key 需要撤销并重新生成，不要继续使用旧 key。

## 当前限制

- 法律咨询必须包含免责声明，不能作为正式法律意见。
- RAG 质量取决于文档版本、清洗质量和评测集质量。
- PDF/DOCX 复杂排版可能影响条文识别，重要法律建议先清洗为纯文本。
- 目前按中文条款格式识别，特殊格式法律文本需要补充规则。





## THINK

#### 层级索引（Parent-Child Retrieval）：感觉可以用这个处理法律合同

#### Hybrid Search 混合检索：BM25 负责精确匹配，通过 RRF（Reciprocal Rank Fusion 倒数排序融合）算法，把两边的结果合并成一个排序。处理法律条文

#### Reranking 精排

#### Corrective RAG（CRAG）：避免胡说八道

#### Adaptive RAG：加一个路由器（分类器）分别处理不同类别的等级的问题，也可以用 LLM 的 few-shot 少样本提示来实现，关键是让简单问题和复杂问题分别处理。

#### GraphRAG：感觉这个处理法律问题的人物对象之间的关系，擅长多跳推理



#### Text-to-SQL RAG：查询表格数据，账单财务之类的

**Multi-Agent RAG**：拆分为多个专职 Agent，各自负责一些任务。

总结：**Agentic RAG**智能体驱动化，



#### Speculative RAG：（假设性检索增强生成）借鉴了推测性解码（Speculative Decoding）的思想，核心目标是降低延迟，把检索到的文档分成多个子集，用多个专家小模型从每个子集 **并行** 生成候选草稿，最后由一个更强的大模型做一次验证，选出最佳答案。



## 最终的RAG模型**Speculative RAG + 多模态 RAG**





### 先把“证据化”打牢（你现在项目就差这一步）
- 目标：每个答案必须能落到 chunk 的 law_name/article_no/clause_no
- 你现在已经做了“法条感知切片”，这是优势（避免“第三条之二”混淆）
### 1）Hybrid Search（BM25 + 向量）+ RRF（强烈推荐第一阶段就上）
- 解决：法律条文的“精确条款/关键词”命中率（BM25）+ 语义召回（向量）
- RRF 优点：不用训练、实现简单、融合稳定，直接提升 topK 质量
- 工程落点：
  - 先把法条/合同 chunk 写入一个可 BM25 搜的索引（ES/OpenSearch 或 PostgreSQL full-text）
  - 向量库继续用 pgvector
  - 结果按 RRF 合并，输出统一的候选证据集
### 2）Reranking 精排（第二阶段）
- 解决：引用准确率、相似条款区分（尤其是“近义条款/例外条款/除外责任”）
- 做法：
  - rerank 模型输入：query + chunk 文本（带条款前缀）
  - 输出：topN 最终证据
- 可选：
  - 轻量：用 cross-encoder reranker（有现成模型）
  - 或用 LLM 做 pairwise/pointwise rerank（成本更高）
### 3）Parent-Child Retrieval（合同强烈适配）
- 解决：合同条款上下文缺失（只取子条款，丢了定义、适用范围、例外）
- 做法：
  - child：条款级 chunk（用于召回）
  - parent：章节/合同定义部分（用于补上下文）
  - 检索命中 child 后，把 parent 一并带回（上下文拼装）
### 4）Adaptive RAG（路由器/分类器）
- 解决：简单问题不用复杂 pipeline，复杂问题才上 rerank/GraphRAG，降成本降延迟
- 类别建议（法律很好分）：
  - A：精确法条/条款定位（BM25 强）
  - B：类比/语义问题（向量强 + rerank）
  - C：多跳关系（GraphRAG）
  - D：结构化数据查询（Text-to-SQL）
  - E：无依据/超出知识库 → 拒答
- 路由实现：
  - 规则 + LLM few-shot（最实用）
  - 后续再换成小模型分类器
### 5）CRAG（Corrective RAG）
- 解决：胡说八道/引用不匹配/证据不足时乱答
- 机制：
  - 生成后做“引用校验”：答案中的关键断言是否在证据 chunk 中可定位
  - 不满足：触发二次检索/扩大召回/切换策略，仍不满足则拒答 + 提示缺少事实
### 6）GraphRAG（人物/主体/责任链）
- 解决：多主体、多跳推理，比如“甲乙丙之间权利义务如何传递”
- 数据构建：
  - 从合同/案例抽取实体与关系（主体、义务、期限、违约责任、例外）
  - 图检索得到子图，再让 LLM 在子图上推理
### 7）Text-to-SQL RAG（财务/账单/台账）
- 解决：真实业务系统“数据问答”
- 关键：
  - SQL 生成要有权限控制、白名单表、限行数、审计
  - 用 RAG 提供表结构/字段释义/示例查询
### 8）Speculative RAG + 多模态（最后阶段）
- Speculative RAG 适合“低延迟+高并发”，但工程复杂度最高（并行草稿 + 验证）
- 多模态 RAG（合同扫描件、图片、PDF）更依赖 OCR/版面解析质量，建议在检索/纠错成熟后再上



