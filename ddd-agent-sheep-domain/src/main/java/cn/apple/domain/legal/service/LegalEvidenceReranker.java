package cn.apple.domain.legal.service;

import cn.apple.domain.legal.adapter.reranker.ILegalEvidenceReranker;
import cn.apple.domain.legal.model.valobj.LegalEvidence;
import cn.apple.domain.legal.model.valobj.LegalKnowledgeMetadata;
import cn.apple.domain.legal.model.valobj.LegalScopeRoute;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class LegalEvidenceReranker implements ILegalEvidenceReranker {

    private static final String STRATEGY = "hybrid-feature-v1";
    private static final int MAX_QUERY_GRAMS = 160;

    @Override
    public List<LegalEvidence> rerank(String query,
                                      LegalScopeRoute route,
                                      List<LegalEvidence> candidates,
                                      int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(50, limit));
        double maxFusionScore = candidates.stream()
                .filter(item -> item != null)
                .mapToDouble(LegalEvidence::score)
                .max()
                .orElse(1.0);
        if (maxFusionScore <= 0.0) {
            maxFusionScore = 1.0;
        }

        List<ScoredEvidence> scored = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            LegalEvidence evidence = candidates.get(index);
            if (evidence == null || evidence.text() == null || evidence.text().isBlank()) {
                continue;
            }
            double score = rerankScore(query, route, evidence, maxFusionScore);
            scored.add(new ScoredEvidence(withScore(evidence, score), score, index));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredEvidence::score).reversed()
                        .thenComparingInt(ScoredEvidence::originalIndex))
                .limit(safeLimit)
                .map(ScoredEvidence::evidence)
                .toList();
    }

    private double rerankScore(String query,
                               LegalScopeRoute route,
                               LegalEvidence evidence,
                               double maxFusionScore) {
        String searchable = String.join(" ",
                value(evidence.text()),
                value(evidence.citation()),
                value(evidence.docName()),
                value(evidence.sourcePath()));
        String normalizedQuery = comparable(query);
        String normalizedEvidence = comparable(searchable);
        double normalizedFusion = Math.max(0.0, evidence.score()) / maxFusionScore;
        double lexicalCoverage = gramCoverage(normalizedQuery, normalizedEvidence);
        double exactPhrase = normalizedQuery.length() >= 4
                && normalizedQuery.length() <= 80
                && normalizedEvidence.contains(normalizedQuery) ? 0.14 : 0.0;
        double reference = referenceScore(query, evidence);
        double scope = matchesScope(evidence, route) ? 0.07 * Math.max(0.5, route.confidence()) : 0.0;
        double traceability = hasTraceability(evidence) ? 0.03 : 0.0;
        return 0.58 * normalizedFusion + 0.28 * lexicalCoverage
                + exactPhrase + reference + scope + traceability;
    }

    private double referenceScore(String query, LegalEvidence evidence) {
        String checkedQuery = comparable(query);
        double score = 0.0;
        String article = comparable(evidence.articleNo());
        String clause = comparable(evidence.clauseNo());
        String law = comparable(evidence.lawName());
        if (!article.isBlank() && checkedQuery.contains(article)) {
            score += 0.36;
        }
        if (!clause.isBlank() && checkedQuery.contains(clause)) {
            score += 0.18;
        }
        if (!law.isBlank() && checkedQuery.contains(law)) {
            score += 0.16;
        }
        return score;
    }

    private boolean matchesScope(LegalEvidence evidence, LegalScopeRoute route) {
        if (route == null || route.lawNameHints() == null || route.lawNameHints().isEmpty()) {
            return false;
        }
        String identity = comparable(String.join(" ",
                value(evidence.lawName()), value(evidence.docName()), value(evidence.sourcePath())));
        return route.lawNameHints().stream()
                .filter(item -> item != null && !item.isBlank())
                .map(this::comparable)
                .anyMatch(identity::contains);
    }

    private boolean hasTraceability(LegalEvidence evidence) {
        boolean hasDocument = !value(evidence.docId()).isBlank()
                || !value(evidence.docName()).isBlank()
                || !value(evidence.lawName()).isBlank();
        return hasDocument && !value(evidence.sourcePath()).isBlank();
    }

    private double gramCoverage(String query, String evidence) {
        if (query.length() < 2 || evidence.isBlank()) {
            return 0.0;
        }
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i < query.length() - 1 && grams.size() < MAX_QUERY_GRAMS; i++) {
            grams.add(query.substring(i, i + 2));
        }
        if (grams.isEmpty()) {
            return 0.0;
        }
        long matches = grams.stream().filter(evidence::contains).count();
        return (double) matches / grams.size();
    }

    private LegalEvidence withScore(LegalEvidence evidence, double rerankScore) {
        Map<String, Object> metadata = evidence.metadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(evidence.metadata());
        metadata.putIfAbsent(LegalKnowledgeMetadata.FUSION_SCORE, evidence.score());
        metadata.put(LegalKnowledgeMetadata.RERANK_SCORE, rerankScore);
        metadata.put(LegalKnowledgeMetadata.RERANK_STRATEGY, STRATEGY);
        return new LegalEvidence(
                evidence.id(), evidence.text(), evidence.docId(), evidence.docName(), evidence.lawName(),
                evidence.articleNo(), evidence.clauseNo(), evidence.chunkIndex(), evidence.sourcePath(),
                rerankScore, Collections.unmodifiableMap(metadata));
    }

    private String comparable(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char current = lower.charAt(i);
            if (Character.isLetterOrDigit(current)) {
                result.append(current);
            }
        }
        return result.toString();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private record ScoredEvidence(LegalEvidence evidence, double score, int originalIndex) {
    }
}
