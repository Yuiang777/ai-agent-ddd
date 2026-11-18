package cn.apple.domain.legal.service;

import cn.apple.domain.legal.adapter.repository.ILegalEvidenceRepository;
import cn.apple.domain.legal.adapter.reranker.ILegalEvidenceReranker;
import cn.apple.domain.legal.model.valobj.LegalEvidence;
import cn.apple.domain.legal.model.valobj.LegalContextAssembly;
import cn.apple.domain.legal.model.valobj.LegalKnowledgeMetadata;
import cn.apple.domain.legal.model.valobj.LegalRetrievalDiagnostics;
import cn.apple.domain.legal.model.valobj.LegalRetrievalRequest;
import cn.apple.domain.legal.model.valobj.LegalRetrievalResult;
import cn.apple.domain.legal.model.valobj.LegalRetrievalSourceDiagnostics;
import cn.apple.domain.legal.model.valobj.LegalRetrievalStatus;
import cn.apple.domain.legal.model.valobj.LegalScopeRoute;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class LegalHybridRetrievalService {

    private static final int RRF_K = 60;
    private static final double ROUTE_FILTER_CONFIDENCE = 0.88;

    private final ILegalEvidenceRepository legalEvidenceRepository;
    private final LegalScopeRouter legalScopeRouter;
    private final LegalEvidenceContextAssembler contextAssembler;
    private final ILegalEvidenceReranker evidenceReranker;

    public LegalHybridRetrievalService(ILegalEvidenceRepository legalEvidenceRepository,
                                       LegalScopeRouter legalScopeRouter,
                                       LegalEvidenceContextAssembler contextAssembler) {
        this(legalEvidenceRepository, legalScopeRouter, contextAssembler, new LegalEvidenceReranker());
    }

    @Autowired
    public LegalHybridRetrievalService(ILegalEvidenceRepository legalEvidenceRepository,
                                       LegalScopeRouter legalScopeRouter,
                                       LegalEvidenceContextAssembler contextAssembler,
                                       ILegalEvidenceReranker evidenceReranker) {
        this.legalEvidenceRepository = legalEvidenceRepository;
        this.legalScopeRouter = legalScopeRouter;
        this.contextAssembler = contextAssembler;
        this.evidenceReranker = evidenceReranker;
    }

    public List<LegalEvidence> retrieve(LegalRetrievalRequest request) {
        return retrieveWithDiagnostics(request).evidences();
    }

    public LegalRetrievalResult retrieveWithDiagnostics(LegalRetrievalRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("legal retrieval query must not be blank");
        }
        if (request.query().length() > 20000) {
            throw new IllegalArgumentException("legal retrieval query is too long");
        }
        LegalScopeRoute route = request.route() == null ? legalScopeRouter.route(request.query()) : request.route();
        LegalRetrievalRequest routedRequest = new LegalRetrievalRequest(
                request.query(),
                request.filterExpression(),
                request.topK(),
                request.vectorTopK(),
                request.keywordTopK(),
                route,
                request.normalizedKnowledgeTag(),
                request.normalizedTenantId(),
                request.corpusType(),
                request.normalizedMatterId(),
                request.normalizedActorId()
        );
        routedRequest.metadataFilters();
        SourceResult vector = search("vector", () -> legalEvidenceRepository.vectorSearch(routedRequest));
        SourceResult keyword = search("keyword", () -> legalEvidenceRepository.keywordSearch(routedRequest));
        List<LegalEvidence> vectorResults = vector.evidences();
        List<LegalEvidence> keywordResults = keyword.evidences();
        List<LegalEvidence> filteredVectorResults = applyRouteFilterIfConfident(vectorResults, route, routedRequest.normalizedTopK());
        List<LegalEvidence> filteredKeywordResults = applyRouteFilterIfConfident(keywordResults, route, routedRequest.normalizedTopK());
        if (!filteredVectorResults.isEmpty() || !filteredKeywordResults.isEmpty()) {
            vectorResults = filteredVectorResults.isEmpty() ? vectorResults : filteredVectorResults;
            keywordResults = filteredKeywordResults.isEmpty() ? keywordResults : filteredKeywordResults;
        }
        Map<String, RankedEvidence> merged = new LinkedHashMap<>();
        addRanked(merged, vectorResults, 1.0, route);
        addRanked(merged, keywordResults, 1.0, route);
        int candidatePoolSize = Math.min(100, Math.max(routedRequest.normalizedTopK() * 4, 20));
        List<LegalEvidence> fused = merged.values().stream()
                .sorted((left, right) -> Double.compare(right.score, left.score))
                .limit(candidatePoolSize)
                .map(item -> item.toEvidence(route))
                .toList();
        RerankResult rerank = rerank(routedRequest, route, fused);
        LegalContextAssembly assembly = contextAssembler.assemble(rerank.evidences(), routedRequest.query());
        List<String> reviewReasons = qualityReviewReasons(
                assembly.evidences(), route, vector.diagnostics(), keyword.diagnostics(), rerank.available());
        LegalRetrievalStatus status = status(
                assembly.evidences(), vector.diagnostics(), keyword.diagnostics(), rerank.available());
        LegalRetrievalDiagnostics diagnostics = new LegalRetrievalDiagnostics(
                status,
                route,
                vector.diagnostics(),
                keyword.diagnostics(),
                assembly.evidences().size(),
                assembly.contextChars(),
                assembly.droppedEvidenceCount(),
                reviewReasons
        );
        return new LegalRetrievalResult(assembly.evidences(), assembly.context(), diagnostics);
    }

    public LegalRetrievalResult retrieveFederated(List<LegalRetrievalRequest> requests) {
        if (requests == null || requests.isEmpty() || requests.size() > 4) {
            throw new IllegalArgumentException("federated legal retrieval requires between one and four scopes");
        }
        LegalRetrievalRequest primary = requests.get(0);
        String query = primary == null ? null : primary.query();
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("legal retrieval query must not be blank");
        }
        for (LegalRetrievalRequest request : requests) {
            if (request == null || !query.equals(request.query())) {
                throw new IllegalArgumentException("federated legal retrieval scopes must use the same query");
            }
            request.metadataFilters();
        }
        List<LegalRetrievalResult> scopedResults = new ArrayList<>();
        for (LegalRetrievalRequest request : requests) {
            scopedResults.add(retrieveWithDiagnostics(request));
        }
        if (scopedResults.size() == 1) {
            return scopedResults.get(0);
        }

        Map<String, LegalEvidence> candidates = new LinkedHashMap<>();
        for (LegalRetrievalResult result : scopedResults) {
            for (LegalEvidence evidence : result.evidences()) {
                candidates.merge(evidenceKey(evidence), evidence,
                        (left, right) -> right.score() > left.score() ? right : left);
            }
        }
        LegalScopeRoute route = scopedResults.get(0).diagnostics().route();
        RerankResult reranked = rerank(primary, route, new ArrayList<>(candidates.values()));
        LegalContextAssembly assembly = contextAssembler.assemble(reranked.evidences(), query);
        List<String> reviewReasons = federatedReviewReasons(requests, scopedResults, reranked.available());
        LegalRetrievalStatus status = federatedStatus(scopedResults, assembly.evidences(), reranked.available());
        LegalRetrievalDiagnostics diagnostics = new LegalRetrievalDiagnostics(
                status,
                route,
                aggregateSource("vector-federated", scopedResults, true),
                aggregateSource("keyword-federated", scopedResults, false),
                assembly.evidences().size(),
                assembly.contextChars(),
                assembly.droppedEvidenceCount(),
                reviewReasons);
        return new LegalRetrievalResult(assembly.evidences(), assembly.context(), diagnostics);
    }

    private LegalRetrievalSourceDiagnostics aggregateSource(String source,
                                                            List<LegalRetrievalResult> results,
                                                            boolean vector) {
        List<LegalRetrievalSourceDiagnostics> diagnostics = results.stream()
                .map(result -> vector ? result.diagnostics().vector() : result.diagnostics().keyword())
                .toList();
        boolean available = diagnostics.stream().allMatch(LegalRetrievalSourceDiagnostics::available);
        int count = diagnostics.stream().mapToInt(LegalRetrievalSourceDiagnostics::resultCount).sum();
        long cost = diagnostics.stream().mapToLong(LegalRetrievalSourceDiagnostics::costMs).sum();
        String errorCode = available ? null : diagnostics.stream()
                .filter(value -> !value.available())
                .map(LegalRetrievalSourceDiagnostics::errorCode)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .reduce((left, right) -> left + "," + right)
                .orElse("SCOPED_RETRIEVAL_FAILED");
        return new LegalRetrievalSourceDiagnostics(source, available, count, cost, errorCode);
    }

    private LegalRetrievalStatus federatedStatus(List<LegalRetrievalResult> results,
                                                  List<LegalEvidence> evidences,
                                                  boolean rerankerAvailable) {
        if (evidences == null || evidences.isEmpty()) {
            boolean anyAvailable = results.stream().anyMatch(result ->
                    result.diagnostics().vector().available() || result.diagnostics().keyword().available());
            return anyAvailable ? LegalRetrievalStatus.INSUFFICIENT : LegalRetrievalStatus.UNAVAILABLE;
        }
        boolean allHealthy = results.stream()
                .allMatch(result -> result.diagnostics().status() == LegalRetrievalStatus.HEALTHY);
        return allHealthy && rerankerAvailable ? LegalRetrievalStatus.HEALTHY : LegalRetrievalStatus.DEGRADED;
    }

    private List<String> federatedReviewReasons(List<LegalRetrievalRequest> requests,
                                                List<LegalRetrievalResult> results,
                                                boolean rerankerAvailable) {
        Set<String> reasons = new LinkedHashSet<>();
        for (int i = 0; i < results.size(); i++) {
            String corpus = requests.get(i).normalizedDocumentScope().corpusType().code();
            for (String reason : results.get(i).diagnostics().reviewReasons()) {
                reasons.add(corpus + ": " + reason);
            }
        }
        if (!rerankerAvailable) {
            reasons.add("跨语料证据重排序服务降级");
        }
        return List.copyOf(reasons);
    }

    private SourceResult search(String source, EvidenceSearch search) {
        long start = System.currentTimeMillis();
        try {
            List<LegalEvidence> result = search.execute();
            List<LegalEvidence> safeResult = result == null ? List.of() : result;
            return new SourceResult(safeResult, LegalRetrievalSourceDiagnostics.success(
                    source, safeResult.size(), System.currentTimeMillis() - start));
        } catch (Exception error) {
            return new SourceResult(List.of(), LegalRetrievalSourceDiagnostics.failed(
                    source, System.currentTimeMillis() - start, error));
        }
    }

    private LegalRetrievalStatus status(List<LegalEvidence> evidences,
                                        LegalRetrievalSourceDiagnostics vector,
                                        LegalRetrievalSourceDiagnostics keyword,
                                        boolean rerankerAvailable) {
        if (!vector.available() && !keyword.available()) {
            return LegalRetrievalStatus.UNAVAILABLE;
        }
        if (evidences == null || evidences.isEmpty()) {
            return LegalRetrievalStatus.INSUFFICIENT;
        }
        if (!vector.available() || !keyword.available() || !rerankerAvailable) {
            return LegalRetrievalStatus.DEGRADED;
        }
        return LegalRetrievalStatus.HEALTHY;
    }

    private List<String> qualityReviewReasons(List<LegalEvidence> evidences,
                                              LegalScopeRoute route,
                                              LegalRetrievalSourceDiagnostics vector,
                                              LegalRetrievalSourceDiagnostics keyword,
                                              boolean rerankerAvailable) {
        List<String> reasons = new ArrayList<>();
        if (!vector.available() && !keyword.available()) {
            reasons.add("知识检索服务不可用");
        } else if (!vector.available() || !keyword.available()) {
            reasons.add("知识检索部分降级，需核验召回完整性");
        }
        if (evidences == null || evidences.isEmpty()) {
            reasons.add("未检索到可验证的法律依据");
        }
        if (route == null || route.confidence() < 0.5) {
            reasons.add("法律领域路由置信度不足");
        }
        if (evidences != null && evidences.stream().anyMatch(this::missingTraceability)) {
            reasons.add("部分证据缺少可追溯元数据");
        }
        if (!rerankerAvailable) {
            reasons.add("证据重排序服务降级，当前使用 RRF 融合顺序");
        }
        return List.copyOf(reasons);
    }

    private RerankResult rerank(LegalRetrievalRequest request,
                                LegalScopeRoute route,
                                List<LegalEvidence> fused) {
        try {
            List<LegalEvidence> result = evidenceReranker.rerank(
                    request.query(), route, fused, request.normalizedTopK());
            if (result == null) {
                throw new IllegalStateException("legal evidence reranker returned null");
            }
            return new RerankResult(result, true);
        } catch (Exception ignored) {
            List<LegalEvidence> fallback = fused.stream()
                    .limit(request.normalizedTopK())
                    .map(this::markRerankFallback)
                    .toList();
            return new RerankResult(fallback, false);
        }
    }

    private LegalEvidence markRerankFallback(LegalEvidence evidence) {
        Map<String, Object> metadata = evidence.metadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(evidence.metadata());
        metadata.put(LegalKnowledgeMetadata.RERANK_STRATEGY, "fusion-fallback");
        return new LegalEvidence(
                evidence.id(), evidence.text(), evidence.docId(), evidence.docName(), evidence.lawName(),
                evidence.articleNo(), evidence.clauseNo(), evidence.chunkIndex(), evidence.sourcePath(),
                evidence.score(), metadata);
    }

    private boolean missingTraceability(LegalEvidence evidence) {
        if (evidence == null) {
            return true;
        }
        boolean noDocumentIdentity = value(evidence.docId()).isBlank()
                && value(evidence.docName()).isBlank()
                && value(evidence.lawName()).isBlank();
        boolean noSource = value(evidence.sourcePath()).isBlank()
                && (evidence.metadata() == null || metadataValue(evidence.metadata().get("source_url")).isBlank());
        return noDocumentIdentity || noSource;
    }

    private String metadataValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void addRanked(Map<String, RankedEvidence> merged, List<LegalEvidence> evidences, double weight, LegalScopeRoute route) {
        if (evidences == null || evidences.isEmpty()) {
            return;
        }
        for (int i = 0; i < evidences.size(); i++) {
            LegalEvidence evidence = evidences.get(i);
            String key = evidenceKey(evidence);
            double score = weight / (RRF_K + i + 1.0) + scopeBonus(evidence, route);
            merged.compute(key, (ignored, existing) -> existing == null
                    ? new RankedEvidence(evidence, score)
                    : existing.add(score));
        }
    }

    private double scopeBonus(LegalEvidence evidence, LegalScopeRoute route) {
        if (route == null || route.lawNameHints() == null || route.lawNameHints().isEmpty()) {
            return 0.0;
        }
        String text = String.join(" ",
                value(evidence.lawName()),
                value(evidence.docName()),
                value(evidence.sourcePath()),
                evidence.metadata() == null ? "" : evidence.metadata().toString()).toLowerCase(Locale.ROOT);
        boolean matched = route.lawNameHints().stream()
                .filter(hint -> hint != null && !hint.isBlank())
                .anyMatch(hint -> text.contains(hint.toLowerCase(Locale.ROOT)));
        return matched ? 0.018 * Math.max(0.5, route.confidence()) : 0.0;
    }

    private String evidenceKey(LegalEvidence evidence) {
        if (evidence.id() != null && !evidence.id().isBlank()) {
            return evidence.id();
        }
        return String.join("|",
                value(evidence.docId()),
                value(evidence.lawName()),
                value(evidence.articleNo()),
                value(evidence.clauseNo()),
                String.valueOf(evidence.chunkIndex()));
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private List<LegalEvidence> applyRouteFilterIfConfident(List<LegalEvidence> evidences, LegalScopeRoute route, int topK) {
        if (evidences == null || evidences.isEmpty()) {
            return List.of();
        }
        if (route == null || route.lawNameHints() == null || route.lawNameHints().isEmpty()) {
            return List.of();
        }
        if (route.confidence() < ROUTE_FILTER_CONFIDENCE) {
            return List.of();
        }
        List<LegalEvidence> filtered = evidences.stream()
                .filter(evidence -> matchesLawHints(evidence, route))
                .toList();
        if (filtered.isEmpty()) {
            return List.of();
        }
        int minimumToKeep = Math.max(2, Math.min(topK, topK / 2));
        if (filtered.size() < minimumToKeep) {
            return List.of();
        }
        return filtered;
    }

    private boolean matchesLawHints(LegalEvidence evidence, LegalScopeRoute route) {
        String text = String.join(" ",
                value(evidence.lawName()),
                value(evidence.docName()),
                value(evidence.sourcePath()),
                evidence.metadata() == null ? "" : evidence.metadata().toString()).toLowerCase(Locale.ROOT);
        return route.lawNameHints().stream()
                .filter(hint -> hint != null && !hint.isBlank())
                .anyMatch(hint -> text.contains(hint.toLowerCase(Locale.ROOT)));
    }

    private static final class RankedEvidence {
        private final LegalEvidence evidence;
        private double score;

        private RankedEvidence(LegalEvidence evidence, double score) {
            this.evidence = evidence;
            this.score = score;
        }

        private RankedEvidence add(double value) {
            this.score += value;
            return this;
        }

        private LegalEvidence toEvidence(LegalScopeRoute route) {
            Map<String, Object> metadata = evidence.metadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(evidence.metadata());
            if (route != null) {
                metadata.putAll(route.metadata());
                metadata.put("scope_law_hints", route.lawNameHints());
            }
            metadata.put(LegalKnowledgeMetadata.FUSION_SCORE, score);
            return new LegalEvidence(
                    evidence.id(),
                    evidence.text(),
                    evidence.docId(),
                    evidence.docName(),
                    evidence.lawName(),
                    evidence.articleNo(),
                    evidence.clauseNo(),
                    evidence.chunkIndex(),
                    evidence.sourcePath(),
                    score,
                    metadata
            );
        }
    }

    @FunctionalInterface
    private interface EvidenceSearch {
        List<LegalEvidence> execute();
    }

    private record SourceResult(List<LegalEvidence> evidences,
                                LegalRetrievalSourceDiagnostics diagnostics) {
    }

    private record RerankResult(List<LegalEvidence> evidences, boolean available) {
    }
}
