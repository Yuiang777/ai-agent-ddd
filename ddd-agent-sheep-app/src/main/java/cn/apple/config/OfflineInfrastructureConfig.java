package cn.apple.config;

import cn.apple.domain.agent.adapter.repository.IAgentRepository;
import cn.apple.domain.agent.model.valobj.AiClientAdvisorVO;
import cn.apple.domain.agent.model.valobj.AiClientApiVO;
import cn.apple.domain.agent.model.valobj.AiClientModelVO;
import cn.apple.domain.agent.model.valobj.AiClientSystemPromptVO;
import cn.apple.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.apple.domain.agent.model.valobj.AiClientVO;
import cn.apple.domain.legal.adapter.repository.ILegalDocumentRepository;
import cn.apple.domain.legal.adapter.repository.ILegalEvidenceRepository;
import cn.apple.domain.legal.adapter.repository.ILegalDocumentCatalogRepository;
import cn.apple.domain.legal.model.entity.LegalDocument;
import cn.apple.domain.legal.model.entity.LegalDocumentIngestionStats;
import cn.apple.domain.legal.model.valobj.LegalDocumentChunk;
import cn.apple.domain.legal.model.valobj.LegalDocumentSource;
import cn.apple.domain.legal.model.valobj.LegalEvidence;
import cn.apple.domain.legal.model.valobj.LegalRetrievalRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.InputStream;

@Configuration
@ConditionalOnProperty(name = "app.infrastructure.enabled", havingValue = "false", matchIfMissing = true)
public class OfflineInfrastructureConfig {

    @Bean
    public ILegalEvidenceRepository offlineLegalEvidenceRepository() {
        return new ILegalEvidenceRepository() {
            @Override
            public List<LegalEvidence> vectorSearch(LegalRetrievalRequest request) {
                throw new InfrastructureDisabledException("VECTOR_STORE_DISABLED");
            }

            @Override
            public List<LegalEvidence> keywordSearch(LegalRetrievalRequest request) {
                throw new InfrastructureDisabledException("FULL_TEXT_SEARCH_DISABLED");
            }
        };
    }

    @Bean
    public ILegalDocumentRepository offlineLegalDocumentRepository() {
        return new ILegalDocumentRepository() {
            @Override
            public void ensureWorkspace() {
            }

            @Override
            public List<LegalDocumentSource> listSources() {
                return List.of();
            }

            @Override
            public LegalDocument read(LegalDocumentSource source) {
                throw new InfrastructureDisabledException("DOCUMENT_INGESTION_DISABLED");
            }

            @Override
            public LegalDocumentSource stageSource(String documentId,
                                                   String originalName,
                                                   cn.apple.domain.legal.model.valobj.LegalDocumentScope scope,
                                                   String objectUri,
                                                   InputStream content) {
                throw new InfrastructureDisabledException("DOCUMENT_INGESTION_DISABLED");
            }

            @Override
            public void saveChunks(List<LegalDocumentChunk> chunks) {
                throw new InfrastructureDisabledException("VECTOR_STORE_DISABLED");
            }

            @Override
            public void replaceChunks(List<LegalDocumentChunk> chunks) {
                throw new InfrastructureDisabledException("VECTOR_STORE_DISABLED");
            }

            @Override
            public void synchronizeChunks(List<LegalDocumentChunk> chunks,
                                          Set<String> sourceDocumentIds,
                                          Set<String> processedDocumentIds) {
                throw new InfrastructureDisabledException("VECTOR_STORE_DISABLED");
            }

            @Override
            public void saveManifest(LegalDocumentIngestionStats stats) {
            }
        };
    }

    @Bean
    public ILegalDocumentCatalogRepository offlineLegalDocumentCatalogRepository() {
        return new ILegalDocumentCatalogRepository() {
            @Override public void ensureWritable() {
                throw new InfrastructureDisabledException("LEGAL_DOCUMENT_CATALOG_DISABLED");
            }
            @Override public void register(cn.apple.domain.legal.model.valobj.LegalDocumentRegistration registration) {
                ensureWritable();
            }
        };
    }

    @Bean
    public IAgentRepository offlineAgentRepository() {
        return new IAgentRepository() {
            @Override
            public List<AiClientApiVO> queryAiClientApiVOListByClientIds(List<String> clientIdList) { return List.of(); }
            @Override
            public List<AiClientModelVO> AiClientModelVOByClientIds(List<String> clientIdList) { return List.of(); }
            @Override
            public List<AiClientToolMcpVO> AiClientToolMcpVOByClientIds(List<String> clientIdList) { return List.of(); }
            @Override
            public List<AiClientSystemPromptVO> AiClientSystemPromptVOByClientIds(List<String> clientIdList) { return List.of(); }
            @Override
            public List<AiClientAdvisorVO> AiClientAdvisorVOByClientIds(List<String> clientIdList) { return List.of(); }
            @Override
            public List<AiClientVO> AiClientVOByClientIds(List<String> clientIdList) { return List.of(); }
            @Override
            public List<AiClientApiVO> queryAiClientApiVOListByModelIds(List<String> modelIdList) { return List.of(); }
            @Override
            public List<AiClientModelVO> AiClientModelVOByModelIds(List<String> modelIdList) { return List.of(); }
            @Override
            public Map<String, AiClientSystemPromptVO> queryAiClientSystemPromptMapByClientIds(List<String> clientIdList) { return Map.of(); }
        };
    }

    public static final class InfrastructureDisabledException extends IllegalStateException {
        public InfrastructureDisabledException(String code) {
            super(code);
        }
    }
}
