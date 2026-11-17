package cn.apple.application.legal;

import cn.apple.config.UploadFileValidator;
import cn.apple.domain.legal.adapter.repository.ILegalDocumentCatalogRepository;
import cn.apple.domain.legal.adapter.repository.ILegalDocumentRepository;
import cn.apple.domain.legal.adapter.repository.ILegalObjectStorage;
import cn.apple.domain.legal.model.valobj.LegalCorpusType;
import cn.apple.domain.legal.model.valobj.LegalDocumentRegistration;
import cn.apple.domain.legal.model.valobj.LegalDocumentScope;
import cn.apple.domain.legal.model.valobj.LegalDocumentSource;
import cn.apple.domain.legal.model.valobj.LegalObjectWriteRequest;
import cn.apple.domain.legal.model.valobj.StoredLegalObject;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

@Service
public class LegalKnowledgeStorageService {

    public static final Set<String> LEGAL_EXTENSIONS = Set.of(".pdf", ".doc", ".docx", ".txt", ".md");

    private final UploadFileValidator uploadFileValidator;
    private final ILegalObjectStorage objectStorage;
    private final ILegalDocumentCatalogRepository catalogRepository;
    private final ILegalDocumentRepository documentRepository;

    public LegalKnowledgeStorageService(UploadFileValidator uploadFileValidator,
                                        ILegalObjectStorage objectStorage,
                                        ILegalDocumentCatalogRepository catalogRepository,
                                        ILegalDocumentRepository documentRepository) {
        this.uploadFileValidator = uploadFileValidator;
        this.objectStorage = objectStorage;
        this.catalogRepository = catalogRepository;
        this.documentRepository = documentRepository;
    }

    public LegalDocumentUploadResult store(MultipartFile file,
                                           String requestedDocumentId,
                                           LegalCorpusType corpusType,
                                           String tenantId,
                                           String matterId,
                                           String supersedesVersion,
                                           String authorityLevel,
                                           String effectiveFrom,
                                           String effectiveTo,
                                           String registeredBy) {
        catalogRepository.ensureWritable();
        String extension = uploadFileValidator.validateAndExtension(file);
        if (!LEGAL_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("only legal text and document files can enter the knowledge base");
        }
        LegalDocumentScope scope = new LegalDocumentScope(
                corpusType, tenantId, matterId,
                corpusType == LegalCorpusType.MATTER_PRIVATE ? registeredBy : null);
        String checksum = checksum(file);
        String originalName = safeOriginalName(file);
        String documentId = requestedDocumentId == null || requestedDocumentId.isBlank()
                ? UUID.randomUUID().toString().replace("-", "")
                : requestedDocumentId.trim();
        String objectKey = objectKey(scope, checksum, extension);
        String contentType = file.getContentType();
        LegalObjectWriteRequest writeRequest = new LegalObjectWriteRequest(
                objectKey, originalName, contentType, file.getSize(), checksum);
        StoredLegalObject stored;
        try (InputStream input = file.getInputStream()) {
            stored = objectStorage.putIfAbsent(writeRequest, input);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to read legal document upload", e);
        }

        LegalDocumentRegistration registration = new LegalDocumentRegistration(
                documentId, scope, originalName, stored, checksum,
                validateOptionalHash(supersedesVersion), authorityLevel,
                validateDate(effectiveFrom), validateDate(effectiveTo), registeredBy, Instant.now());
        catalogRepository.register(registration);

        LegalDocumentSource staged;
        try (InputStream input = objectStorage.open(stored.objectKey())) {
            staged = documentRepository.stageSource(
                    documentId, originalName, scope, stored.objectUri(), input);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to close legal object stream", e);
        }
        return new LegalDocumentUploadResult(documentId, checksum, stored.objectUri(), scope,
                staged.sourcePath() == null ? "" : staged.sourcePath().toString().replace('\\', '/'), "STORED");
    }

    private String checksum(MultipartFile file) {
        try (InputStream input = file.getInputStream();
             DigestInputStream digest = new DigestInputStream(input, MessageDigest.getInstance("SHA-256"))) {
            digest.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(digest.getMessageDigest().digest());
        } catch (Exception e) {
            throw new IllegalStateException("failed to checksum legal document upload", e);
        }
    }

    private String safeOriginalName(MultipartFile file) {
        String value = file.getOriginalFilename();
        if (value == null || value.isBlank()) return "legal-document";
        return java.nio.file.Path.of(value).getFileName().toString();
    }

    private String objectKey(LegalDocumentScope scope, String checksum, String extension) {
        String namespace = scope.tenantId() == null ? "public" : "tenant-" + shortHash(scope.tenantId());
        if (scope.matterId() != null) namespace += "/matter-" + shortHash(scope.matterId());
        return scope.corpusType().code() + "/" + namespace + "/" + checksum.substring(0, 2)
                + "/" + checksum + extension;
    }

    private String shortHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String validateOptionalHash(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase();
        if (!normalized.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("supersedesVersion must be a SHA-256 value");
        }
        return normalized;
    }

    private String validateDate(String value) {
        if (value == null || value.isBlank()) return null;
        return LocalDate.parse(value.trim()).toString();
    }

    public record LegalDocumentUploadResult(String documentId,
                                            String versionHash,
                                            String objectUri,
                                            LegalDocumentScope scope,
                                            String stagingPath,
                                            String status) {
    }
}
