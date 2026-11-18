# 官方法源文本快照

截至 2026-11-18，本轮从以下**官方机构网站**取得三份公开法律文本，转为纯文本 Markdown 放在 `data/legal/raw/`，供后续法条切分。来源网址、原网页及提取文本的 SHA-256、通过日期、生效日期和逐条条数详见 [official-law-sources.json](./official-law-sources.json)。提取时去掉网站导航和重复目录，保留通过说明、章节及全部条文，不改写条文；`node --test front/scripts/official-law-corpus.test.mjs` 核验文件哈希、条号连续性和末条施行日期。

| 文本 | 网页来源 | 条数 | 本地文件 |
| --- | --- | ---: | --- |
| 个人信息保护法 | [最高人民检察院法律法规页面](https://www.spp.gov.cn/spp/fl/202108/t20210820_527244.shtml) | 74 | `data/legal/raw/中华人民共和国个人信息保护法_20210820.md` |
| 法律援助法 | [最高人民检察院法律法规页面](https://www.spp.gov.cn/spp/fl/202108/t20210820_527243.shtml) | 71 | `data/legal/raw/中华人民共和国法律援助法_20210820.md` |
| 数据安全法 | [国家保密局法律页面](https://www.gjbmj.gov.cn/n1/2025/1208/c409088-40619671.html) | 55 | `data/legal/raw/中华人民共和国数据安全法_20210610.md` |

**状态边界：**这些是官网公开页面的版本化文本快照，不是已与全国人大现行法律法规数据库逐条核对的最新有效版本；manifest 的 `currentVersionVerified` 为 `false`。网页 SHA 只记录抓取时收到的响应，未归档网页字节；文本 SHA 以 UTF-8 和 LF 换行为准，校验脚本兼容 Windows 检出时的 CRLF。本轮已通过上传 API 登记三份原件，PostgreSQL 中三个版本均为 STORED，随后仅调用统计接口验证解析（3 份成功、0 失败、372 个片段）；尚未调用向量入库接口，索引表仍为 0 行。原因是本地 qwen3-embedding:4b 实测输出 2560 维，现有 vector_store_openai 表为 vector(1536)，不能直接混用。本轮未改变模型或向量维度，也未宣称已支持新法源检索。

连接可用的后端并确认目标存储目录后，先用 `GET /api/legal/documents/stats` 比对预期文档及条数，再按实际部署方式上传：开发机使用与后端配置一致的 `APP_LEGAL_STORAGE_ROOT`，可从 `raw/` 扫描；启用目录与原件存储的部署环境应优先用 `POST /api/legal/documents/upload`（`corpusType=PUBLIC_LAW`，首次上传不传 `documentId`）登记原件，然后调用 `POST /api/legal/documents/ingest`。最后查看返回的每文件状态、成功数与 chunk 数，并在检索界面检查本轮片段的标题、条号、原文及版本。若已有同一逻辑文档，必须先按版本替代流程核对身份和旧校验和，不直接覆盖或重放原件。正式法律问答使用前仍需核对现行效力和具体适用时间。

## 本地上传与联调记录

2026-11-18 实际上传时发现 PostgreSQL JDBC 不接受直接以 setObject 绑定 Instant 登记时间，已将目录仓储改为 setTimestamp(Timestamp.from(...)) 并补回归测试，重试后三份上传均成功。仅应用现有初始化脚本中的目录表及索引增量建表，不删除或覆盖已有文档。后端以临时管理令牌、仅监听 127.0.0.1 启动，完成后已关闭；令牌未写入项目文件。

在线暂存根目录为 `data/legal/live`，与仓库法源快照目录分离，避免重复扫描同一正文；对象原件位于 `data/legal/objects`。后续启动在线导入时必须继续指向同一暂存根。登记编号如下，当前均为 STORED：

- 中华人民共和国个人信息保护法：`afb40293a4af40e6a63effbf6b76042a`。
- 中华人民共和国法律援助法：`6104db51be8e4d5ba372cd56256c7f47`。
- 中华人民共和国数据安全法：`99dcbcd5d7a44cd28abdf493f2787608`。

统计中 `lawName` 暂显示上传文档 UUID 而非法律标题，属于已观察到的元数据问题，尚待修复；372 是程序解析片段数，不是检索命中数或法律效果评测结果。下一步需先修复上传标题元数据，再安全确定与现有表兼容的嵌入方案，完成入库和真实检索核对。
