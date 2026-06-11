package cn.apple.domain.legal.service;

import cn.apple.domain.legal.adapter.repository.ILegalEvidenceRepository;
import cn.apple.domain.legal.model.valobj.LegalEvidence;
import cn.apple.domain.legal.model.valobj.LegalRetrievalRequest;
import cn.apple.domain.legal.model.valobj.LegalScopeRoute;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LegalHybridRetrievalService {

    private static final int RRF_K = 60;
    private static final double ROUTE_FILTER_CONFIDENCE = 0.88;

    private final ILegalEvidenceRepository legalEvidenceRepository;
    private final LegalScopeRouter legalScopeRouter;

    public List<LegalEvidence> retrieve(LegalRetrievalRequest request) {
        LegalScopeRoute route = request.route() == null ? legalScopeRouter.route(request.query()) : request.route();
        LegalRetrievalRequest routedRequest = new LegalRetrievalRequest(
                request.query(),
                request.filterExpression(),
                request.topK(),
                request.vectorTopK(),
                request.keywordTopK(),
                route
        );
        List<LegalEvidence> vectorResults = legalEvidenceRepository.vectorSearch(routedRequest);
        List<LegalEvidence> keywordResults = legalEvidenceRepository.keywordSearch(routedRequest);
        List<LegalEvidence> filteredVectorResults = applyRouteFilterIfConfident(vectorResults, route, routedRequest.normalizedTopK());
        List<LegalEvidence> filteredKeywordResults = applyRouteFilterIfConfident(keywordResults, route, routedRequest.normalizedTopK());
        if (!filteredVectorResults.isEmpty() || !filteredKeywordResults.isEmpty()) {
            vectorResults = filteredVectorResults.isEmpty() ? vectorResults : filteredVectorResults;
            keywordResults = filteredKeywordResults.isEmpty() ? keywordResults : filteredKeywordResults;
        }
        Map<String, RankedEvidence> merged = new LinkedHashMap<>();
        addRanked(merged, vectorResults, 1.0, route);
        addRanked(merged, keywordResults, 1.0, route);
        return merged.values().stream()
                .sorted((left, right) -> Double.compare(right.score, left.score))
                .limit(routedRequest.normalizedTopK())
                .map(ranked -> ranked.toEvidence(route))
                .toList();
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
}
