package cn.apple.domain.legal.model.valobj;

public final class LegalKnowledgeMetadata {

    public static final String DEFAULT_KNOWLEDGE_TAG = "law-consultant";
    public static final String DEFAULT_VECTOR_TABLE = "vector_store_openai";

    public static final String KNOWLEDGE = "knowledge";
    public static final String CORPUS_TYPE = "corpus_type";
    public static final String TENANT_ID = "tenant_id";
    public static final String MATTER_ID = "matter_id";
    public static final String ACL_SUBJECT_ID = "acl_subject_id";
    public static final String DOC_ID = "doc_id";
    public static final String DOC_NAME = "doc_name";
    public static final String LAW_NAME = "law_name";
    public static final String ARTICLE_NO = "article_no";
    public static final String CLAUSE_NO = "clause_no";
    public static final String CHUNK_INDEX = "chunk_index";
    public static final String CHUNK_COUNT = "chunk_count";
    public static final String CHUNK_SCHEMA_VERSION = "chunk_schema_version";
    public static final String SOURCE_PATH = "source_path";
    public static final String OBJECT_URI = "object_uri";
    public static final String SOURCE_CHECKSUM = "source_checksum";
    public static final String DOCUMENT_VERSION = "document_version";
    public static final String EFFECTIVE_DATE = "effective_date";
    public static final String PARENT_REF = "parent_ref";
    public static final String SECTION_PATH = "section_path";
    public static final String INGESTION_STATUS = "ingestion_status";
    public static final String INGESTION_SOURCE = "ingestion_source";
    public static final String INGESTION_RUN_ID = "ingestion_run_id";
    public static final String INGESTED_AT = "ingested_at";
    public static final String FUSION_SCORE = "fusion_score";
    public static final String RERANK_SCORE = "rerank_score";
    public static final String RERANK_STRATEGY = "rerank_strategy";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_STAGING = "staging";
    public static final String SOURCE_LEGAL_FILESYSTEM = "legal-filesystem";
    public static final String LEGAL_CHUNK_SCHEMA_VERSION = "legal-hierarchy-v5";

    private LegalKnowledgeMetadata() {
    }
}
