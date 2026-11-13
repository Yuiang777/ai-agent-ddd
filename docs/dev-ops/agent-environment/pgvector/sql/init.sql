-- 首次建表前，请确认 VECTOR(1536) 与嵌入模型维度一致。
-- 本脚本是幂等的，不会删除已有向量数据。
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS public.vector_store_openai (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding VECTOR(1536)
);

UPDATE public.vector_store_openai
   SET metadata = '{}'::jsonb
 WHERE metadata IS NULL;

ALTER TABLE public.vector_store_openai
    ALTER COLUMN metadata SET DEFAULT '{}'::jsonb,
    ALTER COLUMN metadata SET NOT NULL;

CREATE INDEX IF NOT EXISTS vector_store_openai_embedding_hnsw_idx
    ON public.vector_store_openai
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- pg_trgm 对连续中文文本和模糊法名检索比 simple 全文分词更有效。
CREATE INDEX IF NOT EXISTS vector_store_openai_content_trgm_idx
    ON public.vector_store_openai
    USING gin (content gin_trgm_ops);

CREATE INDEX IF NOT EXISTS vector_store_openai_metadata_idx
    ON public.vector_store_openai
    USING gin (metadata jsonb_path_ops);

CREATE INDEX IF NOT EXISTS vector_store_openai_scope_v2_idx
    ON public.vector_store_openai (
        (metadata ->> 'knowledge'),
        (metadata ->> 'ingestion_status'),
        (metadata ->> 'corpus_type'),
        (metadata ->> 'tenant_id'),
        (metadata ->> 'matter_id'),
        (metadata ->> 'acl_subject_id')
    );

CREATE INDEX IF NOT EXISTS vector_store_openai_managed_document_idx
    ON public.vector_store_openai (
        (metadata ->> 'knowledge'),
        (metadata ->> 'ingestion_source'),
        (metadata ->> 'doc_id'),
        (metadata ->> 'document_version'),
        (metadata ->> 'ingestion_status')
    );

CREATE INDEX IF NOT EXISTS vector_store_openai_legal_reference_idx
    ON public.vector_store_openai (
        (metadata ->> 'law_name'),
        (metadata ->> 'article_no')
    );

CREATE TABLE IF NOT EXISTS public.legal_document (
    document_id VARCHAR(128) PRIMARY KEY,
    corpus_type VARCHAR(32) NOT NULL,
    tenant_id VARCHAR(128),
    matter_id VARCHAR(128),
    owner_subject_id VARCHAR(128),
    title TEXT NOT NULL,
    authority_level VARCHAR(64) NOT NULL DEFAULT 'UNVERIFIED',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT legal_document_scope_check CHECK (
        (corpus_type IN ('public_law', 'public_case') AND tenant_id IS NULL AND matter_id IS NULL
            AND owner_subject_id IS NULL)
        OR (corpus_type = 'firm_knowledge' AND tenant_id IS NOT NULL AND matter_id IS NULL
            AND owner_subject_id IS NULL)
        OR (corpus_type = 'matter_private' AND tenant_id IS NOT NULL AND matter_id IS NOT NULL
            AND owner_subject_id IS NOT NULL)
    )
);

CREATE TABLE IF NOT EXISTS public.legal_document_version (
    document_id VARCHAR(128) NOT NULL REFERENCES public.legal_document(document_id),
    version_hash CHAR(64) NOT NULL,
    source_checksum CHAR(64) NOT NULL,
    original_name TEXT NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    object_key TEXT NOT NULL,
    object_uri TEXT NOT NULL,
    supersedes_version CHAR(64),
    effective_from DATE,
    effective_to DATE,
    chunk_schema_version VARCHAR(128),
    chunk_count INTEGER CHECK (chunk_count IS NULL OR chunk_count >= 0),
    ingestion_status VARCHAR(32) NOT NULL DEFAULT 'STORED',
    registered_by VARCHAR(128) NOT NULL,
    ingested_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, version_hash),
    CONSTRAINT legal_document_supersedes_self_check CHECK (
        supersedes_version IS NULL OR supersedes_version <> version_hash
    ),
    CONSTRAINT legal_document_supersedes_fk FOREIGN KEY (document_id, supersedes_version)
        REFERENCES public.legal_document_version(document_id, version_hash),
    CONSTRAINT legal_document_effective_period_check CHECK (
        effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from
    )
);

CREATE TABLE IF NOT EXISTS public.legal_document_acl (
    document_id VARCHAR(128) NOT NULL REFERENCES public.legal_document(document_id) ON DELETE CASCADE,
    subject_type VARCHAR(32) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    permission VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, subject_type, subject_id, permission)
);

CREATE INDEX IF NOT EXISTS legal_document_scope_idx
    ON public.legal_document (corpus_type, tenant_id, matter_id, owner_subject_id, status);

CREATE INDEX IF NOT EXISTS legal_document_version_object_idx
    ON public.legal_document_version (object_key);

CREATE INDEX IF NOT EXISTS legal_document_version_status_idx
    ON public.legal_document_version (ingestion_status, created_at);

CREATE INDEX IF NOT EXISTS legal_document_acl_subject_idx
    ON public.legal_document_acl (subject_type, subject_id, permission);

ANALYZE public.vector_store_openai;
ANALYZE public.legal_document;
ANALYZE public.legal_document_version;
ANALYZE public.legal_document_acl;
