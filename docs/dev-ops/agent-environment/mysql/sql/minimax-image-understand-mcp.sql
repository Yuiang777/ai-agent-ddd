-- MiniMax Token Plan MCP：图片理解工具 understand_image
-- 执行前：把 ${MINIMAX_API_KEY} 换成你的 MiniMax Token Plan API Key。

INSERT INTO ai_client_tool_mcp (mcp_id, mcp_name, transport_type, transport_config, request_timeout, status)
VALUES (
    'minimax-image-understand',
    'MiniMax',
    'stdio',
    '{"MiniMax":{"command":"C:\\\\Users\\\\35418\\\\.local\\\\bin\\\\uvx.exe","args":["minimax-coding-plan-mcp","-y"],"env":{"MINIMAX_API_KEY":"${MINIMAX_API_KEY}","MINIMAX_API_HOST":"https://api.minimaxi.com"}}}',
    60,
    1
)
ON DUPLICATE KEY UPDATE
    mcp_name = VALUES(mcp_name),
    transport_type = VALUES(transport_type),
    transport_config = VALUES(transport_config),
    request_timeout = VALUES(request_timeout),
    status = VALUES(status);

-- 绑定到 law-consultant 当前使用的模型。
-- 如果你的 law-consultant 不是只绑定一个模型，请把子查询替换成实际 model_id。
INSERT INTO ai_client_config (source_type, source_id, target_type, target_id, ext_param, status)
SELECT 'model', target_id, 'tool_mcp', 'minimax-image-understand', NULL, 1
FROM ai_client_config
WHERE source_type = 'client'
  AND source_id = 'law-consultant'
  AND target_type = 'model'
  AND status = 1
ON DUPLICATE KEY UPDATE status = VALUES(status), ext_param = VALUES(ext_param);
