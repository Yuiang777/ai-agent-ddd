package cn.apple.domain.legal.service;

import cn.apple.domain.legal.model.entity.LegalDocument;
import cn.apple.domain.legal.model.valobj.LegalCorpusType;
import cn.apple.domain.legal.model.valobj.LegalDocumentChunk;
import cn.apple.domain.legal.model.valobj.LegalDocumentSource;
import cn.apple.domain.legal.model.valobj.LegalKnowledgeMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LegalDocumentChunkService {

    private static final String ORDINAL = "[0-9０-９一二三四五六七八九十百千万零〇两]+";
    private static final Pattern ARTICLE_HEADING = Pattern.compile(
            "^(?:#{1,6}\\s*)?(第" + ORDINAL + "条(?:之" + ORDINAL + ")?)[\\s　]*(.*)$");
    private static final Pattern STRUCTURE_HEADING = Pattern.compile(
            "^(?:#{1,6}\\s*)?(第" + ORDINAL + "(?:分编|编|章|节|篇|部分))[\\s　]*(.*)$");
    private static final Pattern ITEM_HEADING = Pattern.compile(
            "^[（(](" + ORDINAL + ")[）)]|^(" + ORDINAL + ")[、.．]");
    private static final Pattern CASE_SECTION_HEADING = Pattern.compile(
            "^(?:#{1,6}\\s*)?(案件基本信息|当事人信息|审理经过|一审法院查明|一审法院认为|"
                    + "二审法院查明|二审法院认为|原告诉称|上诉人诉称|被告辩称|被上诉人辩称|"
                    + "第三人述称|诉辩主张|本院查明|经审理查明|本院认为|裁判理由|裁判结果|"
                    + "判决如下|裁定如下|案件结果|相关法条|附录)[\\s　]*(?:[：:]\\s*(.*))?$");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*$");
    private static final Pattern DECIMAL_HEADING = Pattern.compile(
            "^([0-9０-９]+(?:[.．][0-9０-９]+){0,4})[、.．\\s　]+(.+)$");
    private static final Pattern CHINESE_NUMBERED_HEADING = Pattern.compile(
            "^([一二三四五六七八九十百千万]+[、.．])[\\s　]*(.+)$");
    private static final int DEFAULT_MAX_CHUNK_LENGTH = 1200;
    private static final int DEFAULT_CHUNK_OVERLAP = 120;
    private static final int MIN_CHUNK_LENGTH = 400;
    private static final int MAX_CHUNK_LENGTH = 4000;
    private static final int MAX_CHUNK_OVERLAP = 800;
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "^(?:(?:\\u7b2c\\s*)?[0-9\\uff10-\\uff19]{1,4}\\s*\\u9875"
                    + "|[-\\u2014]\\s*[0-9\\uff10-\\uff19]{1,4}\\s*[-\\u2014]"
                    + "|[0-9\\uff10-\\uff19]{1,3})$");
    private static final Pattern LABELED_EFFECTIVE_DATE = Pattern.compile(
            "(?:\\u65bd\\u884c\\u65e5\\u671f|\\u751f\\u6548\\u65e5\\u671f|\\u5b9e\\u65bd\\u65e5\\u671f)\\s*[:\\uff1a]?\\s*"
                    + "(\\d{4})[\\u5e74./-](\\d{1,2})[\\u6708./-](\\d{1,2})(?:\\u65e5)?");
    private static final Pattern SENTENCE_EFFECTIVE_DATE = Pattern.compile(
            "\\u81ea\\s*(\\d{4})\\u5e74(\\d{1,2})\\u6708(\\d{1,2})\\u65e5\\s*\\u8d77?\\s*"
                    + "(?:\\u65bd\\u884c|\\u751f\\u6548|\\u5b9e\\u65bd)");

    private final int maxChunkLength;
    private final int chunkOverlap;

    public LegalDocumentChunkService() {
        this(DEFAULT_MAX_CHUNK_LENGTH, DEFAULT_CHUNK_OVERLAP);
    }

    @Autowired
    public LegalDocumentChunkService(
            @Value("${app.legal.knowledge.chunking.max-chars:1200}") int maxChunkLength,
            @Value("${app.legal.knowledge.chunking.overlap-chars:120}") int chunkOverlap) {
        if (maxChunkLength < MIN_CHUNK_LENGTH || maxChunkLength > MAX_CHUNK_LENGTH) {
            throw new IllegalArgumentException("legal chunk max-chars must be between 400 and 4000");
        }
        if (chunkOverlap < 0 || chunkOverlap > MAX_CHUNK_OVERLAP || chunkOverlap >= maxChunkLength / 2) {
            throw new IllegalArgumentException("legal chunk overlap-chars must be non-negative and less than half max-chars");
        }
        this.maxChunkLength = maxChunkLength;
        this.chunkOverlap = chunkOverlap;
    }

    public List<LegalDocumentChunk> split(LegalDocument document) {
        if (document == null || document.source() == null) {
            throw new IllegalArgumentException("legal document and source must not be null");
        }
        String content = cleanSourceContent(document.content());
        if (content.isBlank()) {
            return List.of();
        }

        List<LegalDocumentChunk> chunks = new ArrayList<>();
        String sourceChecksum = sha256(content);
        LegalDocumentSource source = document.source();
        LegalCorpusType corpusType = source.scope().corpusType();
        String chunkSchemaVersion = chunkSchemaVersion(corpusType);
        String documentVersion = sha256(chunkSchemaVersion + ":" + sourceChecksum).substring(0, 32);
        String effectiveDate = extractEffectiveDate(content);
        int chunkIndex = 0;

        if (corpusType == LegalCorpusType.PUBLIC_LAW) {
            for (ParsedArticle article : splitArticles(content)) {
                for (ParsedClause clause : splitClauses(article.content())) {
                    String prefix = buildPrefix(document.lawName(), article, clause.clauseNo());
                    String parentRef = buildParentRef(article, clause.clauseNo());
                    for (String text : splitLongText(prefix, clause.content())) {
                        chunks.add(chunk(document, text, value(document.lawName()), article.articleNo(),
                                value(clause.clauseNo()), chunkIndex++, sourceChecksum, documentVersion,
                                chunkSchemaVersion, effectiveDate, parentRef));
                    }
                }
            }
        } else {
            List<ParsedSection> sections = corpusType == LegalCorpusType.PUBLIC_CASE
                    ? splitCaseSections(content)
                    : splitGenericSections(content);
            for (ParsedSection section : sections) {
                String parentRef = String.join("/", section.path());
                String prefix = buildDocumentPrefix(source, section.path());
                for (String text : splitLongText(prefix, section.content())) {
                    chunks.add(chunk(document, text, "", "", "", chunkIndex++, sourceChecksum,
                            documentVersion, chunkSchemaVersion, effectiveDate, parentRef));
                }
            }
        }
        return List.copyOf(chunks);
    }

    private LegalDocumentChunk chunk(LegalDocument document,
                                     String text,
                                     String lawName,
                                     String articleNo,
                                     String clauseNo,
                                     int chunkIndex,
                                     String sourceChecksum,
                                     String documentVersion,
                                     String chunkSchemaVersion,
                                     String effectiveDate,
                                     String parentRef) {
        LegalDocumentSource source = document.source();
        return new LegalDocumentChunk(
                text,
                source.docId(),
                source.docName(),
                lawName,
                articleNo,
                clauseNo,
                chunkIndex,
                source.sourcePath() == null ? "" : source.sourcePath().toString().replace('\\', '/'),
                sourceChecksum,
                documentVersion,
                chunkSchemaVersion,
                effectiveDate,
                parentRef,
                source.scope(),
                source.objectUri()
        );
    }

    private String cleanSourceContent(String value) {
        String normalized = normalizeLineEndings(value)
                .replace("\uFEFF", "")
                .replaceAll("[\\u200B-\\u200D\\u2060]", "")
                .replaceAll("[\\u0000-\\u0008\\u000B\\u000E-\\u001F\\u007F]", "")
                .replace('\u00a0', ' ');
        List<List<String>> pages = new ArrayList<>();
        for (String page : normalized.split("\\f", -1)) {
            pages.add(new ArrayList<>(List.of(page.split("\\n", -1))));
        }
        Set<String> repeatedBoundaries = repeatedBoundaryLines(pages);
        List<String> cleanedPages = new ArrayList<>();
        for (List<String> page : pages) {
            int first = firstBoundaryLine(page);
            int last = lastBoundaryLine(page);
            List<String> cleanedLines = new ArrayList<>();
            for (int i = 0; i < page.size(); i++) {
                String line = page.get(i);
                String compact = normalizeNoiseLine(line);
                if (PAGE_NUMBER.matcher(compact).matches()) {
                    continue;
                }
                if ((i == first || i == last) && repeatedBoundaries.contains(compact)) {
                    continue;
                }
                cleanedLines.add(line.stripTrailing());
            }
            cleanedPages.add(String.join("\n", cleanedLines).strip());
        }
        return String.join("\n\n", cleanedPages)
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private Set<String> repeatedBoundaryLines(List<List<String>> pages) {
        if (pages.size() < 2) {
            return Set.of();
        }
        Map<String, Integer> occurrences = new HashMap<>();
        for (List<String> page : pages) {
            Set<String> pageBoundaries = new HashSet<>();
            addBoundaryCandidate(pageBoundaries, page, firstBoundaryLine(page));
            addBoundaryCandidate(pageBoundaries, page, lastBoundaryLine(page));
            pageBoundaries.forEach(line -> occurrences.merge(line, 1, Integer::sum));
        }
        int threshold = Math.max(2, (int) Math.ceil(pages.size() * 0.6));
        Set<String> repeated = new HashSet<>();
        occurrences.forEach((line, count) -> {
            if (count >= threshold) {
                repeated.add(line);
            }
        });
        return Set.copyOf(repeated);
    }

    private void addBoundaryCandidate(Set<String> candidates, List<String> page, int index) {
        if (index < 0 || index >= page.size()) {
            return;
        }
        String line = normalizeNoiseLine(page.get(index));
        if (!line.isBlank() && line.length() <= 120
                && !ARTICLE_HEADING.matcher(line).matches()
                && !STRUCTURE_HEADING.matcher(line).matches()
                && !PAGE_NUMBER.matcher(line).matches()) {
            candidates.add(line);
        }
    }

    private int firstBoundaryLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = normalizeNoiseLine(lines.get(i));
            if (!line.isBlank() && !PAGE_NUMBER.matcher(line).matches()) {
                return i;
            }
        }
        return -1;
    }

    private int lastBoundaryLine(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = normalizeNoiseLine(lines.get(i));
            if (!line.isBlank() && !PAGE_NUMBER.matcher(line).matches()) {
                return i;
            }
        }
        return -1;
    }

    private String normalizeNoiseLine(String value) {
        return value == null ? "" : value.replaceAll("[\\s\\u3000]+", " ").strip();
    }

    private String extractEffectiveDate(String content) {
        String date = extractDate(LABELED_EFFECTIVE_DATE.matcher(content));
        return date.isBlank() ? extractDate(SENTENCE_EFFECTIVE_DATE.matcher(content)) : date;
    }

    private String extractDate(Matcher matcher) {
        if (!matcher.find()) {
            return "";
        }
        try {
            return LocalDate.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))).toString();
        } catch (DateTimeException | NumberFormatException ignored) {
            return "";
        }
    }

    private String chunkSchemaVersion(LegalCorpusType corpusType) {
        return LegalKnowledgeMetadata.LEGAL_CHUNK_SCHEMA_VERSION
                + "-c" + corpusType.code()
                + "-m" + maxChunkLength + "-o" + chunkOverlap;
    }

    private List<ParsedSection> splitCaseSections(String text) {
        List<ParsedSection> sections = new ArrayList<>();
        MutableSection current = new MutableSection(List.of("文书首部"));
        for (String rawLine : normalizeLineEndings(text).split("\\n", -1)) {
            String line = rawLine.strip();
            Matcher matcher = CASE_SECTION_HEADING.matcher(line);
            if (matcher.matches()) {
                finishSection(sections, current);
                current = new MutableSection(List.of(matcher.group(1)));
                current.append(matcher.group(2));
            } else {
                current.append(rawLine);
            }
        }
        finishSection(sections, current);
        return List.copyOf(sections);
    }

    private List<ParsedSection> splitGenericSections(String text) {
        List<ParsedSection> sections = new ArrayList<>();
        List<SectionPathNode> hierarchy = new ArrayList<>();
        MutableSection current = new MutableSection(List.of("正文"));
        for (String rawLine : normalizeLineEndings(text).split("\\n", -1)) {
            GenericHeading heading = genericHeading(rawLine.strip());
            if (heading == null) {
                current.append(rawLine);
                continue;
            }

            finishSection(sections, current);
            while (!hierarchy.isEmpty()
                    && hierarchy.get(hierarchy.size() - 1).level() >= heading.level()) {
                hierarchy.remove(hierarchy.size() - 1);
            }
            hierarchy.add(new SectionPathNode(heading.level(), heading.title()));
            current = new MutableSection(hierarchy.stream().map(SectionPathNode::title).toList());
            current.append(heading.remainder());
        }
        finishSection(sections, current);
        return List.copyOf(sections);
    }

    private GenericHeading genericHeading(String line) {
        if (line.isBlank()) {
            return null;
        }
        Matcher matcher = MARKDOWN_HEADING.matcher(line);
        if (matcher.matches()) {
            return new GenericHeading(matcher.group(1).length(), matcher.group(2).strip(), "");
        }
        matcher = STRUCTURE_HEADING.matcher(line);
        if (matcher.matches()) {
            return new GenericHeading(structureLevel(matcher.group(1)),
                    (matcher.group(1) + " " + value(matcher.group(2))).strip(), "");
        }
        matcher = ARTICLE_HEADING.matcher(line);
        if (matcher.matches()) {
            return new GenericHeading(4, matcher.group(1), value(matcher.group(2)));
        }
        matcher = DECIMAL_HEADING.matcher(line);
        if (matcher.matches() && isTitleLike(line, matcher.group(2))) {
            int level = (int) matcher.group(1).chars().filter(value -> value == '.' || value == '．').count() + 1;
            return new GenericHeading(Math.min(level, 6),
                    (matcher.group(1) + " " + matcher.group(2)).strip(), "");
        }
        matcher = CHINESE_NUMBERED_HEADING.matcher(line);
        if (matcher.matches() && isTitleLike(line, matcher.group(2))) {
            return new GenericHeading(1, (matcher.group(1) + matcher.group(2)).strip(), "");
        }
        return null;
    }

    private int structureLevel(String marker) {
        if (marker.endsWith("分编")) {
            return 2;
        }
        if (marker.endsWith("章")) {
            return 3;
        }
        if (marker.endsWith("节")) {
            return 4;
        }
        return 1;
    }

    private boolean isTitleLike(String line, String title) {
        if (line.length() > 160 || title == null || title.isBlank() || title.length() > 120) {
            return false;
        }
        char last = title.charAt(title.length() - 1);
        return last != '。' && last != '！' && last != '？' && last != '；'
                && last != '!' && last != '?' && last != ';';
    }

    private void finishSection(List<ParsedSection> sections, MutableSection current) {
        if (current == null) {
            return;
        }
        String content = current.content();
        if (!content.isBlank()) {
            sections.add(new ParsedSection(current.path, content));
        }
    }

    private List<ParsedArticle> splitArticles(String text) {
        List<ParsedArticle> articles = new ArrayList<>();
        Hierarchy hierarchy = new Hierarchy();
        MutableArticle current = null;

        for (String rawLine : normalizeLineEndings(text).split("\\n", -1)) {
            String line = rawLine.strip();
            Matcher structureMatcher = STRUCTURE_HEADING.matcher(line);
            if (structureMatcher.matches()) {
                current = finishArticle(articles, current);
                hierarchy.update(structureMatcher.group(1), structureMatcher.group(2));
                continue;
            }

            Matcher articleMatcher = ARTICLE_HEADING.matcher(line);
            if (articleMatcher.matches()) {
                current = finishArticle(articles, current);
                current = new MutableArticle(articleMatcher.group(1), hierarchy.snapshot());
                current.append(articleMatcher.group(2));
                continue;
            }

            if (current != null) {
                current.append(rawLine);
            }
        }
        finishArticle(articles, current);

        if (articles.isEmpty()) {
            String normalized = normalizeContent(text);
            return normalized.isBlank()
                    ? List.of()
                    : List.of(new ParsedArticle("全文", List.of(), normalized));
        }
        return List.copyOf(articles);
    }

    private MutableArticle finishArticle(List<ParsedArticle> articles, MutableArticle current) {
        if (current != null) {
            String content = current.content();
            if (!content.isBlank()) {
                articles.add(new ParsedArticle(current.articleNo, current.hierarchy, content));
            }
        }
        return null;
    }

    private List<ParsedClause> splitClauses(String text) {
        List<String> blocks = paragraphBlocks(text);
        if (blocks.isEmpty()) {
            return List.of();
        }

        List<ClauseGroup> groups = new ArrayList<>();
        for (String block : blocks) {
            Matcher itemMatcher = ITEM_HEADING.matcher(block);
            if (itemMatcher.find()) {
                if (groups.isEmpty()) {
                    groups.add(new ClauseGroup(""));
                }
                String ordinal = itemMatcher.group(1) == null ? itemMatcher.group(2) : itemMatcher.group(1);
                groups.get(groups.size() - 1).items.add(new ClauseItem(displayOrdinal(ordinal), block));
            } else {
                groups.add(new ClauseGroup(block));
            }
        }

        List<ParsedClause> clauses = new ArrayList<>();
        boolean multipleClauses = groups.size() > 1;
        for (int i = 0; i < groups.size(); i++) {
            ClauseGroup group = groups.get(i);
            String clauseNo = multipleClauses ? "第" + toChineseNumber(i + 1) + "款" : null;
            if (group.items.isEmpty()) {
                if (!group.lead.isBlank()) {
                    clauses.add(new ParsedClause(clauseNo, group.lead));
                }
                continue;
            }
            for (ClauseItem item : group.items) {
                String itemNo = value(clauseNo) + "第" + item.ordinal + "项";
                String itemContent = group.lead.isBlank()
                        ? item.content
                        : group.lead + System.lineSeparator() + item.content;
                clauses.add(new ParsedClause(itemNo, itemContent));
            }
        }
        return List.copyOf(clauses);
    }

    private List<String> paragraphBlocks(String text) {
        String normalized = normalizeLineEndings(text).replace('\u00a0', ' ').strip();
        if (normalized.isBlank()) {
            return List.of();
        }

        String[] rawBlocks = normalized.split("\\n\\s*\\n+");
        List<String> blocks = new ArrayList<>();
        for (String rawBlock : rawBlocks) {
            String block = normalizeParagraph(rawBlock);
            if (!block.isBlank()) {
                blocks.add(block);
            }
        }

        if (blocks.size() == 1) {
            List<String> completeLines = normalized.lines()
                    .map(this::normalizeParagraph)
                    .filter(line -> !line.isBlank())
                    .toList();
            if (completeLines.size() > 1 && completeLines.stream().allMatch(this::endsAtSentenceBoundary)) {
                return completeLines;
            }
        }
        return List.copyOf(blocks);
    }

    private String buildPrefix(String lawName, ParsedArticle article, String clauseNo) {
        List<String> parts = new ArrayList<>();
        if (lawName != null && !lawName.isBlank()) {
            parts.add("《" + lawName.trim() + "》");
        }
        parts.addAll(article.hierarchy());
        parts.add(article.articleNo());
        if (clauseNo != null && !clauseNo.isBlank()) {
            parts.add(clauseNo);
        }
        return String.join(" ", parts) + "：";
    }

    private String buildParentRef(ParsedArticle article, String clauseNo) {
        List<String> parts = new ArrayList<>(article.hierarchy());
        parts.add(article.articleNo());
        if (clauseNo != null && !clauseNo.isBlank()) {
            parts.add(clauseNo);
        }
        return String.join("/", parts);
    }

    private String buildDocumentPrefix(LegalDocumentSource source, List<String> sectionPath) {
        List<String> parts = new ArrayList<>();
        parts.add(source.docName() == null || source.docName().isBlank() ? source.docId() : source.docName());
        parts.addAll(sectionPath);
        return "【" + String.join(" / ", parts) + "】：";
    }

    private List<String> splitLongText(String prefix, String content) {
        prefix = compactPrefix(prefix);
        String normalized = normalizeContent(content);
        if (normalized.isBlank()) {
            return List.of();
        }
        int maxContentLength = Math.max(200, maxChunkLength - prefix.length());
        if (normalized.length() <= maxContentLength) {
            return List.of(prefix + normalized);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int hardEnd = Math.min(normalized.length(), start + maxContentLength);
            int end = hardEnd == normalized.length()
                    ? hardEnd
                    : findChunkEnd(normalized, start, hardEnd);
            String window = normalized.substring(start, end).strip();
            if (!window.isBlank()) {
                chunks.add(prefix + window);
            }
            if (end >= normalized.length()) {
                break;
            }
            int nextStart = findOverlapStart(normalized, start, end);
            start = nextStart > start ? nextStart : end;
        }
        return List.copyOf(chunks);
    }

    private String compactPrefix(String prefix) {
        int maxPrefixLength = maxChunkLength - 200;
        if (prefix.length() <= maxPrefixLength) {
            return prefix;
        }
        int headLength = Math.max(40, maxPrefixLength / 3);
        int tailLength = maxPrefixLength - headLength - 5;
        return prefix.substring(0, headLength).stripTrailing()
                + " ... "
                + prefix.substring(prefix.length() - tailLength).stripLeading();
    }

    private int findChunkEnd(String text, int start, int hardEnd) {
        int preferredStart = start + Math.max(1, (hardEnd - start) * 3 / 5);
        for (int i = hardEnd - 1; i >= preferredStart; i--) {
            if (isStrongBoundary(text.charAt(i))) {
                return i + 1;
            }
        }
        for (int i = hardEnd - 1; i >= preferredStart; i--) {
            if (isWeakBoundary(text.charAt(i))) {
                return i + 1;
            }
        }
        return hardEnd;
    }

    private int findOverlapStart(String text, int start, int end) {
        int target = Math.max(start + 1, end - chunkOverlap);
        int floor = Math.max(start + 1, end - chunkOverlap * 2);
        for (int i = target; i >= floor; i--) {
            if (i > 0 && isStrongBoundary(text.charAt(i - 1))) {
                return i;
            }
        }
        return target;
    }

    private String normalizeContent(String value) {
        return normalizeLineEndings(value)
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll(" *\\n *", "\\n")
                .replaceAll("\\n{3,}", "\\n\\n")
                .strip();
    }

    private String normalizeParagraph(String value) {
        return normalizeLineEndings(value)
                .lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .reduce((left, right) -> left + " " + right)
                .orElse("")
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .strip();
    }

    private String normalizeLineEndings(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private boolean endsAtSentenceBoundary(String value) {
        return !value.isBlank() && isStrongBoundary(value.charAt(value.length() - 1));
    }

    private boolean isStrongBoundary(char value) {
        return value == '。' || value == '！' || value == '？' || value == '；'
                || value == '.' || value == '!' || value == '?' || value == ';' || value == '\n';
    }

    private boolean isWeakBoundary(char value) {
        return value == '，' || value == '、' || value == ',' || Character.isWhitespace(value);
    }

    private String displayOrdinal(String value) {
        String normalized = value == null ? "" : value
                .replace('０', '0').replace('１', '1').replace('２', '2').replace('３', '3').replace('４', '4')
                .replace('５', '5').replace('６', '6').replace('７', '7').replace('８', '8').replace('９', '9');
        if (normalized.chars().allMatch(Character::isDigit)) {
            try {
                return toChineseNumber(Integer.parseInt(normalized));
            } catch (NumberFormatException ignored) {
                return normalized;
            }
        }
        return normalized;
    }

    private String toChineseNumber(int number) {
        if (number <= 0 || number > 9999) {
            return Integer.toString(number);
        }
        String[] digits = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        String[] units = {"", "十", "百", "千"};
        String raw = Integer.toString(number);
        StringBuilder result = new StringBuilder();
        boolean pendingZero = false;
        for (int i = 0; i < raw.length(); i++) {
            int digit = raw.charAt(i) - '0';
            int unitIndex = raw.length() - i - 1;
            if (digit == 0) {
                pendingZero = !result.isEmpty();
                continue;
            }
            if (pendingZero) {
                result.append(digits[0]);
                pendingZero = false;
            }
            if (!(digit == 1 && unitIndex == 1 && result.isEmpty())) {
                result.append(digits[digit]);
            }
            result.append(units[unitIndex]);
        }
        return result.toString();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("failed to calculate legal document checksum", e);
        }
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private record ParsedArticle(String articleNo, List<String> hierarchy, String content) {
    }

    private record ParsedClause(String clauseNo, String content) {
    }

    private record ParsedSection(List<String> path, String content) {
    }

    private record GenericHeading(int level, String title, String remainder) {
    }

    private record SectionPathNode(int level, String title) {
    }

    private record ClauseItem(String ordinal, String content) {
    }

    private static final class ClauseGroup {
        private final String lead;
        private final List<ClauseItem> items = new ArrayList<>();

        private ClauseGroup(String lead) {
            this.lead = lead;
        }
    }

    private static final class MutableArticle {
        private final String articleNo;
        private final List<String> hierarchy;
        private final StringBuilder content = new StringBuilder();

        private MutableArticle(String articleNo, List<String> hierarchy) {
            this.articleNo = articleNo;
            this.hierarchy = hierarchy;
        }

        private void append(String line) {
            content.append(line == null ? "" : line).append('\n');
        }

        private String content() {
            return content.toString().strip();
        }
    }

    private static final class MutableSection {
        private final List<String> path;
        private final StringBuilder content = new StringBuilder();

        private MutableSection(List<String> path) {
            this.path = path;
        }

        private void append(String line) {
            if (line != null && !line.isBlank()) {
                content.append(line).append('\n');
            }
        }

        private String content() {
            return content.toString().strip();
        }
    }

    private static final class Hierarchy {
        private String part = "";
        private String subpart = "";
        private String chapter = "";
        private String section = "";

        private void update(String marker, String title) {
            String heading = (marker + " " + valueOf(title)).strip();
            if (marker.endsWith("分编")) {
                subpart = heading;
                chapter = "";
                section = "";
            } else if (marker.endsWith("编") || marker.endsWith("篇") || marker.endsWith("部分")) {
                part = heading;
                subpart = "";
                chapter = "";
                section = "";
            } else if (marker.endsWith("章")) {
                chapter = heading;
                section = "";
            } else if (marker.endsWith("节")) {
                section = heading;
            }
        }

        private List<String> snapshot() {
            return List.of(part, subpart, chapter, section).stream()
                    .filter(item -> item != null && !item.isBlank())
                    .toList();
        }

        private static String valueOf(String value) {
            return value == null ? "" : value.replaceAll("[\\s　]+", " ").strip();
        }
    }
}
