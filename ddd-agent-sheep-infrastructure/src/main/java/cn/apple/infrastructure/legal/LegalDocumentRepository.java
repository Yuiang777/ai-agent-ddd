package cn.apple.infrastructure.legal;

import cn.apple.domain.legal.adapter.repository.ILegalDocumentRepository;
import cn.apple.domain.legal.model.entity.LegalDocument;
import cn.apple.domain.legal.model.entity.LegalDocumentIngestionStats;
import cn.apple.domain.legal.model.valobj.LegalDocumentChunk;
import cn.apple.domain.legal.model.valobj.LegalDocumentSource;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Repository
@RequiredArgsConstructor
public class LegalDocumentRepository implements ILegalDocumentRepository {

    private static final Path LEGAL_ROOT = Path.of("data", "legal");
    private static final Path RAW_DIR = LEGAL_ROOT.resolve("raw");
    private static final Path MANIFEST_DIR = LEGAL_ROOT.resolve("manifest");
    private static final String KNOWLEDGE_TAG = "law-consultant";

    private final PgVectorStore pgVectorStore;

    @Override
    public void ensureWorkspace() {
        try {
            Files.createDirectories(RAW_DIR);
            Files.createDirectories(LEGAL_ROOT.resolve("processed"));
            Files.createDirectories(LEGAL_ROOT.resolve("eval"));
            Files.createDirectories(MANIFEST_DIR);
        } catch (IOException e) {
            throw new IllegalStateException("创建法律文档目录失败", e);
        }
    }

    @Override
    public List<LegalDocumentSource> listSources() {
        try (Stream<Path> stream = Files.walk(RAW_DIR)) {
            return stream.filter(Files::isRegularFile)
                    .filter(this::isSupportedFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .map(file -> new LegalDocumentSource(stableDocId(file), file.getFileName().toString(), file))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("读取法律文档目录失败: " + RAW_DIR.toAbsolutePath(), e);
        }
    }

    @Override
    public LegalDocument read(LegalDocumentSource source) {
        String text = readText(source.sourcePath());
        return new LegalDocument(source, resolveLawName(source.sourcePath(), text), text);
    }

    @Override
    public void saveChunks(List<LegalDocumentChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        pgVectorStore.accept(chunks.stream().map(this::toDocument).toList());
    }

    @Override
    public void saveManifest(LegalDocumentIngestionStats stats) {
        Path manifest = MANIFEST_DIR.resolve("law-documents-manifest.json");
        try {
            Files.writeString(manifest, ManifestJsonWriter.toJson(stats), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入法律文档清单失败: " + manifest, e);
        }
    }

    private Document toDocument(LegalDocumentChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("knowledge", KNOWLEDGE_TAG);
        metadata.put("doc_id", chunk.docId());
        metadata.put("doc_name", chunk.docName());
        metadata.put("law_name", chunk.lawName());
        metadata.put("article_no", chunk.articleNo());
        metadata.put("clause_no", chunk.clauseNo());
        metadata.put("chunk_index", chunk.chunkIndex());
        metadata.put("source_path", chunk.sourcePath());
        return new Document(chunk.text(), metadata);
    }

    private String readText(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".txt") || fileName.endsWith(".md")) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("读取文本文件失败: " + file, e);
            }
        }
        TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(file));
        return reader.get().stream().map(Document::getText).reduce("", (left, right) -> left + System.lineSeparator() + right);
    }

    private String resolveLawName(Path file, String text) {
        String firstLine = text.lines().map(String::trim).filter(line -> !line.isBlank()).findFirst().orElse("");
        Matcher bookTitle = Pattern.compile("《([^》]+)》").matcher(firstLine);
        if (bookTitle.find()) {
            return bookTitle.group(1);
        }
        return file.getFileName().toString().replaceFirst("\\.[^.]+$", "");
    }

    private boolean isSupportedFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".pdf") || name.endsWith(".docx");
    }

    private String stableDocId(Path file) {
        return RAW_DIR.relativize(file).toString().replace('\\', '/');
    }
}
