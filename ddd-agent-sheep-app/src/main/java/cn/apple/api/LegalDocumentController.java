package cn.apple.api;

import cn.apple.domain.legal.model.entity.LegalDocumentIngestionStats;
import cn.apple.application.legal.LegalDocumentIngestionService;
import cn.apple.application.legal.LegalKnowledgeStorageService;
import cn.apple.application.legal.matter.LegalMatterApplicationService;
import cn.apple.config.TenantContext;
import cn.apple.config.UploadProperties;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import java.util.List;
import cn.apple.domain.legal.model.valobj.LegalCorpusType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/legal/documents")
public class LegalDocumentController {

    private final LegalDocumentIngestionService legalDocumentIngestionService;
    private final LegalKnowledgeStorageService legalKnowledgeStorageService;
    private final boolean securityContextEnabled;
    private final LegalMatterApplicationService matterService;
    private final UploadProperties uploadProperties;
    private final MultipartProperties multipartProperties;
    private final boolean infrastructureEnabled;

    public LegalDocumentController(LegalDocumentIngestionService legalDocumentIngestionService,
                                   LegalKnowledgeStorageService legalKnowledgeStorageService,
                                   LegalMatterApplicationService matterService,
                                   UploadProperties uploadProperties,
                                   MultipartProperties multipartProperties,
                                   @Value("${app.infrastructure.enabled:false}") boolean infrastructureEnabled,
                                   @Value("${app.enterprise.security-context-enabled:false}") boolean securityContextEnabled) {
        this.legalDocumentIngestionService = legalDocumentIngestionService;
        this.legalKnowledgeStorageService = legalKnowledgeStorageService;
        this.securityContextEnabled = securityContextEnabled;
        this.matterService = matterService;
        this.uploadProperties = uploadProperties;
        this.multipartProperties = multipartProperties;
        this.infrastructureEnabled = infrastructureEnabled;
    }

    @GetMapping("/upload-capabilities")
    public ApiResponse<UploadCapabilities> uploadCapabilities() {
        try {
            authenticatedPrincipal();
            List<String> extensions = LegalKnowledgeStorageService.LEGAL_EXTENSIONS.stream()
                    .map(value -> value.substring(1))
                    .filter(value -> uploadProperties.getAllowedExtensions().contains(value))
                    .sorted().toList();
            boolean enabled = infrastructureEnabled && multipartProperties.getEnabled() && !extensions.isEmpty();
            String reason = !infrastructureEnabled ? "当前环境未启用文档存储目录，可登记已有材料引用。"
                    : !multipartProperties.getEnabled() ? "当前环境已关闭文件上传。"
                    : extensions.isEmpty() ? "当前环境未配置可上传的法律文档类型。"
                    : "已配置上传能力，实际存储连接和目录健康仍以提交结果为准。";
            return ApiResponse.ok(new UploadCapabilities(enabled, enabled ? "CONFIGURED_UNVERIFIED" : "DISABLED",
                    reason, Math.min(uploadProperties.getMaxFileSize().toBytes(), multipartProperties.getMaxFileSize().toBytes()),
                    multipartProperties.getMaxRequestSize().toBytes(), extensions));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    public record UploadCapabilities(boolean enabled, String status, String reason, long maxFileBytes,
                                     long maxRequestBytes, List<String> allowedExtensions) { }

    @PostMapping("/upload")
    public ApiResponse<LegalKnowledgeStorageService.LegalDocumentUploadResult> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "documentId", required = false) String documentId,
            @RequestParam(value = "corpusType", defaultValue = "PUBLIC_LAW") LegalCorpusType corpusType,
            @RequestParam(value = "matterId", required = false) String matterId,
            @RequestParam(value = "supersedesVersion", required = false) String supersedesVersion,
            @RequestParam(value = "authorityLevel", required = false) String authorityLevel,
            @RequestParam(value = "effectiveFrom", required = false) String effectiveFrom,
            @RequestParam(value = "effectiveTo", required = false) String effectiveTo) {
        try {
            TenantContext.Principal principal = authenticatedPrincipal();
            String tenantId = corpusType.tenantRequired() ? principal.tenantId() : null;
            if (corpusType == LegalCorpusType.MATTER_PRIVATE) {
                // Check the authenticated tenant before writing any private file.
                matterService.get(principal.tenantId(), matterId);
            }
            return ApiResponse.ok(legalKnowledgeStorageService.store(
                    file, documentId, corpusType, tenantId, matterId, supersedesVersion,
                    authorityLevel, effectiveFrom, effectiveTo, principal.actorId()));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @PostMapping("/ingest")
    public ApiResponse<LegalDocumentIngestionStats> ingest() {
        authenticatedPrincipal();
        return ApiResponse.ok(legalDocumentIngestionService.ingest());
    }

    @GetMapping("/stats")
    public ApiResponse<LegalDocumentIngestionStats> stats() {
        authenticatedPrincipal();
        return ApiResponse.ok(legalDocumentIngestionService.stats());
    }

    private TenantContext.Principal authenticatedPrincipal() {
        TenantContext.Principal principal = TenantContext.current();
        if (securityContextEnabled && principal == null) {
            throw new SecurityException("AUTHENTICATED_ACTOR_CONTEXT_REQUIRED");
        }
        if (securityContextEnabled && (principal.role() != cn.apple.domain.legal.matter.MatterActorRole.LAWYER
                && principal.role() != cn.apple.domain.legal.matter.MatterActorRole.PARALEGAL)) {
            throw new SecurityException("INTERNAL_LEGAL_STAFF_REQUIRED");
        }
        return principal == null
                ? new TenantContext.Principal("offline-tenant", "offline-user", null)
                : principal;
    }
}
