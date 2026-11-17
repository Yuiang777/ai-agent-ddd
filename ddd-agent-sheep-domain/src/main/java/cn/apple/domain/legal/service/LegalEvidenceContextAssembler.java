package cn.apple.domain.legal.service;

import cn.apple.domain.legal.model.valobj.LegalContextAssembly;
import cn.apple.domain.legal.model.valobj.LegalEvidence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class LegalEvidenceContextAssembler {

    public static final int DEFAULT_CONTEXT_BUDGET = 6000;
    private static final int DEFAULT_MAX_CHARS_PER_EVIDENCE = 1400;
    private static final int DEFAULT_MAX_EVIDENCE_PER_DOCUMENT = 4;
    private static final int MIN_EVIDENCE_BODY_CHARS = 100;
    private static final int MAX_QUERY_GRAMS = 120;
    private static final int MAX_RELEVANT_SENTENCE_ANCHORS = 3;
    private static final String BLOCK_SEPARATOR = "\n\n---\n\n";
    private static final String COMPRESSION_MARKER = "\n...[中间内容已压缩]...\n";
    private static final String RELEVANCE_MARKER = " [低相关内容已省略] ";

    private final int defaultContextBudget;
    private final int maxCharsPerEvidence;
    private final int maxEvidencePerDocument;

    public LegalEvidenceContextAssembler() {
        this(DEFAULT_CONTEXT_BUDGET, DEFAULT_MAX_CHARS_PER_EVIDENCE, DEFAULT_MAX_EVIDENCE_PER_DOCUMENT);
    }

    @Autowired
    public LegalEvidenceContextAssembler(
            @Value("${app.legal.retrieval.context-budget-chars:6000}") int defaultContextBudget,
            @Value("${app.legal.retrieval.max-chars-per-evidence:1400}") int maxCharsPerEvidence,
            @Value("${app.legal.retrieval.max-evidence-per-document:4}") int maxEvidencePerDocument) {
        this.defaultContextBudget = Math.max(800, Math.min(20000, defaultContextBudget));
        this.maxCharsPerEvidence = Math.max(400, Math.min(4000, maxCharsPerEvidence));
        this.maxEvidencePerDocument = Math.max(1, Math.min(10, maxEvidencePerDocument));
    }

    public LegalContextAssembly assemble(List<LegalEvidence> candidates) {
        return assemble(candidates, "", defaultContextBudget);
    }

    public LegalContextAssembly assemble(List<LegalEvidence> candidates, String query) {
        return assemble(candidates, query, defaultContextBudget);
    }

    public LegalContextAssembly assemble(List<LegalEvidence> candidates, int requestedBudget) {
        return assemble(candidates, "", requestedBudget);
    }

    public LegalContextAssembly assemble(List<LegalEvidence> candidates, String query, int requestedBudget) {
        int budget = Math.max(800, Math.min(20000, requestedBudget));
        if (candidates == null || candidates.isEmpty()) {
            return new LegalContextAssembly("", List.of(), 0, 0);
        }

        List<LegalEvidence> ordered = diversityFirst(deduplicate(candidates));
        List<LegalEvidence> selected = new ArrayList<>();
        Map<String, Integer> documentCounts = new LinkedHashMap<>();
        Map<String, Set<String>> seenSentences = new LinkedHashMap<>();
        StringBuilder context = new StringBuilder();

        for (LegalEvidence evidence : ordered) {
            String documentKey = documentKey(evidence);
            if (documentCounts.getOrDefault(documentKey, 0) >= maxEvidencePerDocument) {
                continue;
            }

            String normalized = stripRepeatedCitationPrefix(normalizeText(evidence.text()), evidence);
            String focused = focusOnQuery(normalized, query);
            String uniqueText = removeRepeatedSentences(focused,
                    seenSentences.getOrDefault(documentKey, Set.of()));
            if (uniqueText.isBlank()) {
                continue;
            }

            int separatorLength = context.isEmpty() ? 0 : BLOCK_SEPARATOR.length();
            int remaining = budget - context.length() - separatorLength;
            String block = fitBlock(evidence, selected.size() + 1, uniqueText, remaining);
            if (block == null) {
                continue;
            }

            if (!context.isEmpty()) {
                context.append(BLOCK_SEPARATOR);
            }
            context.append(block);
            selected.add(evidence);
            documentCounts.merge(documentKey, 1, Integer::sum);
            seenSentences.computeIfAbsent(documentKey, ignored -> new LinkedHashSet<>())
                    .addAll(sentenceFingerprints(block.substring(block.indexOf('\n') + 1)));
        }

        int dropped = Math.max(0, candidates.size() - selected.size());
        return new LegalContextAssembly(context.toString(), List.copyOf(selected), context.length(), dropped);
    }

    private List<LegalEvidence> deduplicate(List<LegalEvidence> candidates) {
        Map<String, LegalEvidence> unique = new LinkedHashMap<>();
        for (LegalEvidence evidence : candidates) {
            if (evidence == null || evidence.text() == null || evidence.text().isBlank()) {
                continue;
            }
            unique.putIfAbsent(evidenceKey(evidence), evidence);
        }
        return List.copyOf(unique.values());
    }

    private List<LegalEvidence> diversityFirst(List<LegalEvidence> candidates) {
        List<LegalEvidence> ordered = new ArrayList<>();
        Set<String> representedDocuments = new LinkedHashSet<>();
        Set<String> selectedEvidence = new LinkedHashSet<>();

        for (LegalEvidence evidence : candidates) {
            if (representedDocuments.add(documentKey(evidence))) {
                ordered.add(evidence);
                selectedEvidence.add(evidenceKey(evidence));
            }
        }
        for (LegalEvidence evidence : candidates) {
            if (selectedEvidence.add(evidenceKey(evidence))) {
                ordered.add(evidence);
            }
        }
        return List.copyOf(ordered);
    }

    private String fitBlock(LegalEvidence evidence, int ordinal, String text, int available) {
        if (available <= 0) {
            return null;
        }
        String header = formatHeader(evidence, ordinal);
        int bodyBudget = Math.min(maxCharsPerEvidence, available - header.length() - 1);
        if (bodyBudget < MIN_EVIDENCE_BODY_CHARS) {
            return null;
        }
        String body = boundaryAwareExcerpt(text, bodyBudget);
        return body.isBlank() ? null : header + "\n" + body;
    }

    private String formatHeader(LegalEvidence evidence, int ordinal) {
        String citation = value(evidence.citation());
        StringBuilder header = new StringBuilder("[依据 ").append(ordinal).append("] ")
                .append(citation.isBlank() ? value(evidence.docName()) : citation);

        String parentRef = metadataValue(evidence, "parent_ref");
        if (!parentRef.isBlank() && !parentRef.equals(citation)) {
            header.append(" | 层级: ").append(compactPath(parentRef, 120));
        }
        String source = value(evidence.sourcePath());
        if (!source.isBlank()) {
            header.append(" | 来源: ").append(compactPath(source, 180));
        }
        return header.toString();
    }

    private String stripRepeatedCitationPrefix(String text, LegalEvidence evidence) {
        int colon = firstColon(text);
        if (colon <= 0 || colon > 260) {
            return text;
        }
        String prefix = text.substring(0, colon);
        String articleNo = value(evidence.articleNo());
        String lawName = value(evidence.lawName());
        boolean matchesArticle = articleNo.isBlank() || prefix.contains(articleNo);
        boolean matchesLaw = lawName.isBlank() || prefix.contains(lawName);
        return matchesArticle && matchesLaw ? text.substring(colon + 1).strip() : text;
    }

    private String removeRepeatedSentences(String text, Set<String> seen) {
        StringBuilder unique = new StringBuilder();
        for (String sentence : sentences(text)) {
            String fingerprint = fingerprint(sentence);
            if (fingerprint.length() >= 12 && seen.contains(fingerprint)) {
                continue;
            }
            if (!unique.isEmpty()) {
                unique.append(' ');
            }
            unique.append(sentence.strip());
        }
        return unique.toString().strip();
    }

    private Set<String> sentenceFingerprints(String text) {
        Set<String> fingerprints = new LinkedHashSet<>();
        for (String sentence : sentences(text)) {
            String fingerprint = fingerprint(sentence);
            if (fingerprint.length() >= 12) {
                fingerprints.add(fingerprint);
            }
        }
        return fingerprints;
    }

    private List<String> sentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            current.append(value);
            if (isBoundary(value)) {
                addSentence(sentences, current);
            }
        }
        addSentence(sentences, current);
        return sentences;
    }

    private String focusOnQuery(String text, String query) {
        List<String> sourceSentences = sentences(text);
        Set<String> grams = queryGrams(query);
        if (sourceSentences.size() <= 2 || grams.isEmpty()) {
            return text;
        }

        List<SentenceScore> scores = new ArrayList<>();
        for (int i = 0; i < sourceSentences.size(); i++) {
            int score = sentenceScore(sourceSentences.get(i), grams);
            if (score > 0) {
                scores.add(new SentenceScore(i, score));
            }
        }
        if (scores.isEmpty()) {
            return text;
        }
        scores.sort(Comparator.comparingInt(SentenceScore::score).reversed()
                .thenComparingInt(SentenceScore::index));
        Set<Integer> selected = new LinkedHashSet<>();
        scores.stream().limit(MAX_RELEVANT_SENTENCE_ANCHORS).forEach(item -> {
            selected.add(item.index());
            if (item.index() > 0) {
                selected.add(item.index() - 1);
            }
            if (item.index() + 1 < sourceSentences.size()) {
                selected.add(item.index() + 1);
            }
        });
        if (selected.size() >= sourceSentences.size()) {
            return text;
        }

        StringBuilder focused = new StringBuilder();
        boolean omitted = false;
        for (int i = 0; i < sourceSentences.size(); i++) {
            if (!selected.contains(i)) {
                omitted = true;
                continue;
            }
            if (omitted) {
                focused.append(RELEVANCE_MARKER);
            } else if (!focused.isEmpty()) {
                focused.append(' ');
            }
            focused.append(sourceSentences.get(i).strip());
            omitted = false;
        }
        if (omitted) {
            focused.append(RELEVANCE_MARKER);
        }
        return focused.toString().strip();
    }

    private Set<String> queryGrams(String query) {
        String comparable = comparable(query);
        if (comparable.length() < 2) {
            return Set.of();
        }
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i < comparable.length() - 1 && grams.size() < MAX_QUERY_GRAMS; i++) {
            grams.add(comparable.substring(i, i + 2));
        }
        return Set.copyOf(grams);
    }

    private int sentenceScore(String sentence, Set<String> grams) {
        String comparable = comparable(sentence);
        int score = 0;
        for (String gram : grams) {
            if (comparable.contains(gram)) {
                score++;
            }
        }
        return score;
    }

    private String comparable(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = Character.toLowerCase(value.charAt(i));
            if (Character.isLetterOrDigit(current)) {
                result.append(current);
            }
        }
        return result.toString();
    }

    private void addSentence(List<String> sentences, StringBuilder current) {
        String sentence = current.toString().strip();
        if (!sentence.isBlank()) {
            sentences.add(sentence);
        }
        current.setLength(0);
    }

    private String boundaryAwareExcerpt(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        if (maxLength < COMPRESSION_MARKER.length() + 80) {
            return text.substring(0, maxLength).strip();
        }

        int contentBudget = maxLength - COMPRESSION_MARKER.length();
        int requestedHead = contentBudget * 2 / 3;
        int headEnd = previousBoundary(text, requestedHead, Math.max(40, requestedHead / 2));
        int requestedTailStart = text.length() - (contentBudget - headEnd);
        int tailStart = nextBoundary(text, requestedTailStart,
                Math.min(text.length() - 1, requestedTailStart + Math.max(40, contentBudget / 4)));
        if (tailStart <= headEnd) {
            return text.substring(0, maxLength).strip();
        }
        String result = text.substring(0, headEnd).strip()
                + COMPRESSION_MARKER
                + text.substring(tailStart).strip();
        return result.length() <= maxLength ? result : result.substring(0, maxLength).strip();
    }

    private int previousBoundary(String text, int from, int floor) {
        for (int i = Math.min(from, text.length() - 1); i >= floor; i--) {
            if (isBoundary(text.charAt(i))) {
                return i + 1;
            }
        }
        return from;
    }

    private int nextBoundary(String text, int from, int ceiling) {
        for (int i = Math.max(0, from); i <= ceiling && i < text.length(); i++) {
            if (isBoundary(text.charAt(i))) {
                return i + 1;
            }
        }
        return from;
    }

    private boolean isBoundary(char value) {
        return value == '。' || value == '！' || value == '？' || value == '；'
                || value == '!' || value == '?' || value == ';' || value == '：' || value == ':' || value == '\n';
    }

    private int firstColon(String text) {
        int chinese = text.indexOf('：');
        int ascii = text.indexOf(':');
        if (chinese < 0) return ascii;
        if (ascii < 0) return chinese;
        return Math.min(chinese, ascii);
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private String fingerprint(String value) {
        return value.replaceAll("[\\s，。！？；、,:：;.!?]", "").strip();
    }

    private String compactPath(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return "..." + value.substring(value.length() - maxLength + 3);
    }

    private String documentKey(LegalEvidence evidence) {
        if (evidence.docId() != null && !evidence.docId().isBlank()) {
            return evidence.docId();
        }
        if (evidence.sourcePath() != null && !evidence.sourcePath().isBlank()) {
            return evidence.sourcePath();
        }
        return value(evidence.docName()) + "|" + value(evidence.lawName());
    }

    private String evidenceKey(LegalEvidence evidence) {
        if (evidence.id() != null && !evidence.id().isBlank()) {
            return evidence.id();
        }
        return String.join("|",
                documentKey(evidence),
                value(evidence.articleNo()),
                value(evidence.clauseNo()),
                Integer.toString(evidence.chunkIndex()),
                Integer.toHexString(normalizeText(evidence.text()).hashCode()));
    }

    private String metadataValue(LegalEvidence evidence, String key) {
        if (evidence.metadata() == null) {
            return "";
        }
        Object value = evidence.metadata().get(key);
        return value == null ? "" : String.valueOf(value).strip();
    }

    private String value(String value) {
        return value == null ? "" : value.strip();
    }

    private record SentenceScore(int index, int score) {
    }
}
