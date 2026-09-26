-- auto-generated definition
create table feishu_conversation_mapping
(
    id              bigint auto_increment comment '主键ID'
        primary key,
    feishu_chat_id  varchar(128)                          not null comment '飞书会话ID（单聊/群聊）',
    conversation_id varchar(64)                           not null comment '内部会话ID（关联 rag_chat_conversation.conversation_id）',
    chat_type       varchar(16) default 'p2p'             not null comment '会话类型：p2p-单聊, group-群聊',
    created_at      datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    updated_at      datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '修改时间',
    constraint uk_feishu_chat_id
        unique (feishu_chat_id)
)
    comment '飞书会话映射表' collate = utf8mb4_unicode_ci;

create index idx_conversation_id
    on feishu_conversation_mapping (conversation_id);

-- auto-generated definition
create table knowledge_document_segment
(
    id             bigint auto_increment comment '片段ID'
        primary key,
    text           longtext                           not null comment '文本内容',
    chunk_id       varchar(255)                       null comment '分片ID',
    metadata       varchar(2048)                      null comment '元数据',
    document_id    bigint                             not null comment '所属文档ID',
    chunk_order    int                                not null comment '顺序',
    embedding_id   varchar(255)                       null comment '嵌入ID',
    status         varchar(255)                       null comment '状态：STORED, VECTOR_STORED',
    skip_embedding int                                null comment '是否跳过嵌入生成',
    created_at     datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updated_at     datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '修改时间',
    deleted        tinyint  default 0                 not null comment '是否删除：0-未删除，1-已删除'
)
    comment '知识片段表' collate = utf8mb4_unicode_ci;

create index idx_document_id
    on knowledge_document_segment (document_id);

create index idx_document_id_chunk_order
    on knowledge_document_segment (document_id, chunk_order);

create index idx_document_status_skip
    on knowledge_document_segment (document_id, status, skip_embedding);

create index idx_status
    on knowledge_document_segment (status);




-- auto-generated definition
create table rag_chat_conversation
(
    id              bigint auto_increment comment '主键ID'
        primary key,
    conversation_id varchar(64)                           not null comment '会话唯一标识',
    user_id         varchar(64)                           not null comment '用户ID',
    title           varchar(512)                          null comment '会话标题',
    created_time    datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    updated_time    datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '修改时间',
    deleted         tinyint     default 0                 not null comment '是否删除：0-未删除，1-已删除',
    status          varchar(32) default 'active'          not null comment '状态',
    constraint uk_conversation_id
        unique (conversation_id)
)
    comment 'AI对话会话表' collate = utf8mb4_unicode_ci;

-- auto-generated definition
create table rag_chat_message
(
    id                bigint auto_increment comment '主键ID'
        primary key,
    message_id        varchar(64)                        not null comment '消息唯一标识',
    conversation_id   varchar(64)                        not null comment '所属会话ID',
    type              varchar(32)                        not null comment '角色：USER/ASSISTANT',
    content           longtext                           null comment '消息内容',
    transform_content longtext                           null comment '改写后的内容',
    token_count       int                                null comment 'Token数量',
    model_name        varchar(128)                       null comment '使用的模型名称',
    rag_references    json                               null comment 'RAG引用内容JSON数组，包含document_id、document_title、chunk_id、chunk_content、similarity_score、retrieval_source等字段',
    created_time      datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updated_time      datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '修改时间',
    deleted           tinyint  default 0                 not null comment '是否删除：0-未删除，1-已删除',
    metadata          json                               null comment '扩展元数据JSON格式',
    constraint uk_message_id
        unique (message_id)
)
    comment 'AI对话消息表' collate = utf8mb4_unicode_ci;

create index idx_conversation_id
    on rag_chat_message (conversation_id);

create index idx_create_time
    on rag_chat_message (created_time);



-- auto-generated definition
create table rag_knowledge_document
(
    id               bigint auto_increment comment '文档ID'
        primary key,
    title            varchar(1024)                         not null comment '文档标题',
    file_url         varchar(2048)                         null comment '文件URL地址（MinIO存储路径）',
    file_type        varchar(32)                           null comment '文件类型（如：pdf、docx、pptx、xlsx、png、jpg 等）',
    expire_date      date                                  null comment '文档失效日期',
    status           varchar(32)                           not null comment '状态：INIT, UPLOADED, CONVERTING, CONVERTED, CHUNKED, VECTOR_STORED',
    accessible_by    varchar(32) default 'STAFF'           not null comment '文档访问权限：VISITOR-所有人, CUSTOMER-外部客户及以上, STAFF-仅客服',
    description      varchar(512)                          null comment '文档描述',
    extension        text                                  null comment '扩展字段，保存JSON字符串',
    mineru_task_id   varchar(128)                          null comment 'MinerU解析任务ID',
    parse_result_url varchar(2048)                         null comment '解析结果文件地址（MinIO存储路径）',
    processed_md_url varchar(2048)                         null comment '图片处理后的Markdown文件地址（MinIO存储路径）',
    chunk_size       int                                   null comment '文档分片大小（每个分块的最大字符数），默认500',
    overlap          int                                   null comment '相邻分块之间的重叠字符数，默认50',
    created_time     datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    updated_time     datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '修改时间',
    deleted          tinyint     default 0                 not null comment '是否删除：0-未删除，1-已删除'
)
    comment 'rag知识文档表' collate = utf8mb4_unicode_ci;

create index idx_created_time
    on rag_knowledge_document (created_time);




-- auto-generated definition
create table user_info
(
    id         bigint auto_increment comment '主键ID'
        primary key,
    phone      varchar(20)                           not null comment '手机号（登录账号）',
    password   varchar(128)                          not null comment '登录密码',
    name       varchar(64)                           null comment '姓名',
    nickname   varchar(128)                          null comment '昵称',
    avatar     varchar(512)                          null comment '头像地址',
    status     varchar(32) default 'ACTIVE'          null comment '状态：ACTIVE-正常、FROZEN-冻结',
    user_type  varchar(32) default 'CUSTOMER'        not null comment '用户类型：VISITOR-访客, CUSTOMER-外部客户, STAFF-客服',
    created_at datetime    default CURRENT_TIMESTAMP null comment '创建时间',
    updated_at datetime    default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint uk_phone
        unique (p
    comment '客户信息表' collate = utf8mb4_unicode_ci;



INSERT INTO rag_db.user_info (id, phone, password, name, nickname, avatar, status, user_type, created_at, updated_at) VALUES (1, '12345678911', '$2a$10$0AwGYQWYocZpkVcLhUHqJeM08kzFQzjCQPJ8HgbOX49r4PD9KBY6C', '测试客户', '小测', null, 'ACTIVE', 'STAFF', '2026-09-25 16:18:45', '2026-09-26 06:35:24');