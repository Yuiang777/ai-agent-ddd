create table ai_agent
(
    id          bigint auto_increment comment '主键ID'
        primary key,
    agent_id    varchar(64)                          not null comment '智能体ID',
    agent_name  varchar(50)                          not null comment '智能体名称',
    description varchar(255)                         null comment '描述',
    channel     varchar(32)                          null comment '渠道类型(agent，chat_stream)',
    status      tinyint(1) default 1                 null comment '状态(0:禁用,1:启用)',
    create_time datetime   default CURRENT_TIMESTAMP null comment '创建时间',
    update_time datetime   default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint uk_agent_id
        unique (agent_id)
)
    comment 'AI智能体配置表';

create table ai_agent_flow_config
(
    id          bigint auto_increment comment '主键ID'
        primary key,
    agent_id    bigint                             not null comment '智能体ID',
    client_id   bigint                             not null comment '客户端ID',
    sequence    int                                not null comment '序列号(执行顺序)',
    create_time datetime default CURRENT_TIMESTAMP null comment '创建时间',
    constraint uk_agent_client_seq
        unique (agent_id, client_id, sequence)
)
    comment '智能体-客户端关联表';

create table ai_agent_task_schedule
(
    id              bigint auto_increment comment '主键ID'
        primary key,
    agent_id        bigint                               not null comment '智能体ID',
    task_name       varchar(64)                          null comment '任务名称',
    description     varchar(255)                         null comment '任务描述',
    cron_expression varchar(50)                          not null comment '时间表达式(如: 0/3 * * * * *)',
    task_param      text                                 null comment '任务入参配置(JSON格式)',
    status          tinyint(1) default 1                 null comment '状态(0:无效,1:有效)',
    create_time     datetime   default CURRENT_TIMESTAMP null comment '创建时间',
    update_time     datetime   default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间'
)
    comment '智能体任务调度配置表';

create index idx_agent_id
    on ai_agent_task_schedule (agent_id);

create table ai_client
(
    id          bigint auto_increment comment '主键ID'
        primary key,
    client_id   varchar(64)                          not null comment '客户端ID',
    client_name varchar(50)                          not null comment '客户端名称',
    description varchar(1024)                        null comment '描述',
    status      tinyint(1) default 1                 null comment '状态(0:禁用,1:启用)',
    create_time datetime   default CURRENT_TIMESTAMP null comment '创建时间',
    update_time datetime   default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint client_id
        unique (client_id)
)
    comment 'AI客户端配置表';

create table ai_client_advisor
(
    id           bigint auto_increment comment '主键ID'
        primary key,
    advisor_id   varchar(64)                          not null comment '顾问ID',
    advisor_name varchar(50)                          not null comment '顾问名称',
    advisor_type varchar(50)                          not null comment '顾问类型(PromptChatMemory/RagAnswer/SimpleLoggerAdvisor等)',
    order_num    int        default 0                 null comment '顺序号',
    ext_param    varchar(2048)                        null comment '扩展参数配置，json 记录',
    status       tinyint(1) default 1                 null comment '状态(0:禁用,1:启用)',
    create_time  datetime   default CURRENT_TIMESTAMP null comment '创建时间',
    update_time  datetime   default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint uk_advisor_id
        unique (advisor_id)
)
    comment '顾问配置表';

create table ai_client_api
(
    id               bigint auto_increment comment '自增主键ID'
        primary key,
    api_id           varchar(64)                        not null comment '全局唯一配置ID',
    base_url         varchar(255)                       not null comment 'API基础URL',
    api_key          varchar(255)                       not null comment 'API密钥',
    completions_path varchar(255)                       not null comment '补全API路径',
    embeddings_path  varchar(255)                       not null comment '嵌入API路径',
    status           tinyint  default 1                 not null comment '状态：0-禁用，1-启用',
    create_time      datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time      datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint uk_api_id
        unique (api_id)
)
    comment 'OpenAI API配置表';

create index idx_status
    on ai_client_api (status);

create table ai_client_config
(
    id          bigint auto_increment comment '主键ID'
        primary key,
    source_type varchar(32)                          not null comment '源类型（model、client）',
    source_id   varchar(64)                          not null comment '源ID（如 chatModelId、chatClientId 等）',
    target_type varchar(32)                          not null comment '目标类型（model、client）',
    target_id   varchar(64)                          not null comment '目标ID（如 openAiApiId、chatModelId、systemPromptId、advisorId 等）',
    ext_param   varchar(1024)                        null comment '扩展参数（JSON格式）',
    status      tinyint(1) default 1                 null comment '状态(0:禁用,1:启用)',
    create_time datetime   default CURRENT_TIMESTAMP null comment '创建时间',
    update_time datetime   default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间'
)
    comment 'AI客户端统一关联配置表';

create index idx_source_id
    on ai_client_config (source_id);

create index idx_target_id
    on ai_client_config (target_id);

create table ai_client_model
(
    id          bigint auto_increment comment '自增主键ID'
        primary key,
    model_id    varchar(64)                        not null comment '全局唯一模型ID',
    api_id      varchar(64)                        not null comment '关联的API配置ID',
    model_name  varchar(64)                        not null comment '模型名称',
    model_type  varchar(32)                        not null comment '模型类型：openai、deepseek、claude',
    status      tinyint  default 1                 not null comment '状态：0-禁用，1-启用',
    create_time datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint uk_model_id
        unique (model_id)
)
    comment '聊天模型配置表';

create index idx_api_config_id
    on ai_client_model (api_id);

create index idx_status
    on ai_client_model (status);

create table ai_client_rag_order
(
    id            bigint auto_increment comment '主键ID'
        primary key,
    rag_id        varchar(50)                          not null comment '知识库ID',
    rag_name      varchar(50)                          not null comment '知识库名称',
    knowledge_tag varchar(50)                          not null comment '知识标签',
    status        tinyint(1) default 1                 null comment '状态(0:禁用,1:启用)',
    create_time   datetime   default CURRENT_TIMESTAMP null comment '创建时间',
    update_time   datetime   default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint uk_rag_id
        unique (rag_id)
)
    comment '知识库配置表';

create table ai_client_system_prompt
(
    id             bigint auto_increment comment '主键ID'
        primary key,
    prompt_id      varchar(64)                          not null comment '提示词ID',
    prompt_name    varchar(50)                          not null comment '提示词名称',
    prompt_content text                                 not null comment '提示词内容',
    description    varchar(1024)                        null comment '描述',
    status         tinyint(1) default 1                 null comment '状态(0:禁用,1:启用)',
    create_time    datetime   default CURRENT_TIMESTAMP null comment '创建时间',
    update_time    datetime   default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint uk_prompt_id
        unique (prompt_id)
)
    comment '系统提示词配置表';

create table ai_client_tool_mcp
(
    id               bigint auto_increment comment '主键ID'
        primary key,
    mcp_id           varchar(64)                          not null comment 'MCP名称',
    mcp_name         varchar(50)                          not null comment 'MCP名称',
    transport_type   varchar(20)                          not null comment '传输类型(sse/stdio)',
    transport_config varchar(1024)                        null comment '传输配置(sse/stdio)',
    request_timeout  int        default 180               null comment '请求超时时间(分钟)',
    status           tinyint(1) default 1                 null comment '状态(0:禁用,1:启用)',
    create_time      datetime   default CURRENT_TIMESTAMP null comment '创建时间',
    update_time      datetime   default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint uk_mcp_id
        unique (mcp_id)
)
    comment 'MCP客户端配置表';

