-- 律师顾问 Agent 示例配置
-- 使用前请确认 ai_client_api / ai_client_model 中的 api_id、model_id 与本地模型配置一致。
-- 初期仅启用 RAG，不绑定 MCP，避免本地 MCP 服务未启动导致装配失败。

INSERT INTO ai_client (client_id, client_name, description, status)
VALUES ('law-consultant', '律师顾问Agent', '基于法律文档知识库的律师顾问 Agent', 1)
ON DUPLICATE KEY UPDATE client_name = VALUES(client_name), description = VALUES(description), status = VALUES(status);

INSERT INTO ai_client_system_prompt (prompt_id, prompt_name, prompt_content, description, status)
VALUES (
    'law-consultant-system-prompt',
    '律师顾问系统提示词',
    '你是律师顾问 Agent。回答必须基于已检索到的法律文档，不得编造法条。回答时优先引用法律名称、条、款；如果依据不足，明确说明当前知识库无法判断。第三条第二款与第三条之二是不同法条结构，必须严格区分。输出末尾提示：本回答仅供参考，不构成正式法律意见，具体案件请咨询执业律师。',
    '律师顾问 Agent 角色约束和引用要求',
    1
)
ON DUPLICATE KEY UPDATE prompt_content = VALUES(prompt_content), description = VALUES(description), status = VALUES(status);

INSERT INTO ai_client_advisor (advisor_id, advisor_name, advisor_type, order_num, ext_param, status)
VALUES (
    'law-consultant-rag-advisor',
    '律师顾问RAG检索',
    'RAG_ANSWER',
    10,
    '{"topK":8,"filterExpression":"knowledge == ''law-consultant''"}',
    1
)
ON DUPLICATE KEY UPDATE advisor_name = VALUES(advisor_name), advisor_type = VALUES(advisor_type), order_num = VALUES(order_num), ext_param = VALUES(ext_param), status = VALUES(status);

INSERT INTO ai_client_advisor (advisor_id, advisor_name, advisor_type, order_num, ext_param, status)
VALUES (
    'law-consultant-chat-memory',
    '律师顾问上下文记忆',
    'CHAT_MEMORY',
    20,
    '{"maxMessages":20}',
    1
)
ON DUPLICATE KEY UPDATE advisor_name = VALUES(advisor_name), advisor_type = VALUES(advisor_type), order_num = VALUES(order_num), ext_param = VALUES(ext_param), status = VALUES(status);

-- 按你的 ai_client_model 实际 model_id 修改 target_id。
INSERT INTO ai_client_config (source_type, source_id, target_type, target_id, ext_param, status)
VALUES ('client', 'law-consultant', 'model', 'gpt-4o', NULL, 1),
       ('client', 'law-consultant', 'prompt', 'law-consultant-system-prompt', NULL, 1),
       ('client', 'law-consultant', 'advisor', 'law-consultant-rag-advisor', NULL, 1),
       ('client', 'law-consultant', 'advisor', 'law-consultant-chat-memory', NULL, 1);
