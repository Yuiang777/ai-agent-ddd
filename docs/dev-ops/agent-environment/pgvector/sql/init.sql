-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 删除旧表（如果存在）
DROP TABLE IF EXISTS public.vector_store;

-- 创建 Spring AI 官方默认表名：vector_store
CREATE TABLE public.vector_store (
                                     id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                     content TEXT NOT NULL,
                                     metadata JSONB,
                                     embedding VECTOR(1536)
);

-- 添加索引（强烈推荐，可提升向量搜索速度 10 倍）
CREATE INDEX IF NOT EXISTS vector_store_embedding_idx
    ON public.vector_store
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
