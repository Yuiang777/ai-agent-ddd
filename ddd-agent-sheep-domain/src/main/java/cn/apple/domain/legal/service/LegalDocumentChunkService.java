package cn.apple.domain.legal.service;

import cn.apple.domain.legal.model.entity.LegalDocument;
import cn.apple.domain.legal.model.valobj.LegalArticle;
import cn.apple.domain.legal.model.valobj.LegalClause;
import cn.apple.domain.legal.model.valobj.LegalDocumentChunk;
import cn.apple.domain.legal.model.valobj.LegalDocumentSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LegalDocumentChunkService {

    private static final Pattern ARTICLE_PATTERN = Pattern.compile("第[一二三四五六七八九十百千万零〇两]+条(?:之[一二三四五六七八九十百千万零〇两]+)?");
    private static final Pattern CLAUSE_PATTERN = Pattern.compile("第[一二三四五六七八九十百千万零〇两]+款");
    private static final int MAX_CHUNK_LENGTH = 1200;
    private static final int CHUNK_OVERLAP = 120;

    public List<LegalDocumentChunk> split(LegalDocument document) {
        List<LegalDocumentChunk> chunks = new ArrayList<>();
        int chunkIndex = 0;
        for (LegalArticle article : splitArticles(document.content())) {
            for (LegalClause clause : splitClauses(article.content())) {
                String prefix = buildPrefix(document.lawName(), article.articleNo(), clause.clauseNo());
                for (String text : splitLongText(prefix, clause.content())) {
                    LegalDocumentSource source = document.source();
                    chunks.add(new LegalDocumentChunk(
                            text,
                            source.docId(),
                            source.docName(),
                            document.lawName(),
                            article.articleNo(),
                            clause.clauseNo() == null ? "" : clause.clauseNo(),
                            chunkIndex,
                            source.sourcePath().toString().replace('\\', '/')
                    ));
                    chunkIndex++;
                }
            }
        }
        return chunks;
    }

    private List<LegalArticle> splitArticles(String text) {
        Matcher matcher = ARTICLE_PATTERN.matcher(text);
        List<LegalArticle> articles = new ArrayList<>();
        String preface = null;
        int previousStart = -1;
        String previousArticle = null;

        while (matcher.find()) {
            if (previousArticle == null) {
                preface = text.substring(0, matcher.start()).trim();
            } else {
                articles.add(new LegalArticle(previousArticle, text.substring(previousStart, matcher.start()).trim()));
            }
            previousArticle = matcher.group();
            previousStart = matcher.start();
        }

        if (previousArticle != null) {
            articles.add(new LegalArticle(previousArticle, text.substring(previousStart).trim()));
        }
        if (articles.isEmpty() && !text.isBlank()) {
            articles.add(new LegalArticle("全文", text.trim()));
        }
        if (preface != null && !preface.isBlank() && !articles.isEmpty()) {
            LegalArticle first = articles.get(0);
            articles.set(0, new LegalArticle(first.articleNo(), preface + System.lineSeparator() + first.content()));
        }
        return articles;
    }

    private List<LegalClause> splitClauses(String text) {
        Matcher matcher = CLAUSE_PATTERN.matcher(text);
        List<LegalClause> clauses = new ArrayList<>();
        int previousStart = -1;
        String previousClause = null;

        while (matcher.find()) {
            if (previousClause != null) {
                clauses.add(new LegalClause(previousClause, text.substring(previousStart, matcher.start()).trim()));
            }
            previousClause = matcher.group();
            previousStart = matcher.start();
        }

        if (previousClause != null) {
            clauses.add(new LegalClause(previousClause, text.substring(previousStart).trim()));
        }
        if (clauses.isEmpty()) {
            clauses.add(new LegalClause(null, text.trim()));
        }
        return clauses;
    }

    private String buildPrefix(String lawName, String articleNo, String clauseNo) {
        return "《" + lawName + "》 " + articleNo + (clauseNo == null ? "" : " " + clauseNo) + "：";
    }

    private List<String> splitLongText(String prefix, String content) {
        String normalized = content.replaceAll("\\s+", " ").trim();
        List<String> chunks = new ArrayList<>();
        if (normalized.length() + prefix.length() <= MAX_CHUNK_LENGTH) {
            chunks.add(prefix + normalized);
            return chunks;
        }

        int maxContentLength = Math.max(300, MAX_CHUNK_LENGTH - prefix.length());
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + maxContentLength);
            chunks.add(prefix + normalized.substring(start, end));
            if (end == normalized.length()) {
                break;
            }
            start = Math.max(0, end - CHUNK_OVERLAP);
        }
        return chunks;
    }
}
