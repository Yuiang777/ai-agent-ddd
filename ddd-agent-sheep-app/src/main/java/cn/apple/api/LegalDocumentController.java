package cn.apple.api;

import cn.apple.domain.legal.model.entity.LegalDocumentIngestionStats;
import cn.apple.application.legal.LegalDocumentIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/legal/documents")
@RequiredArgsConstructor
public class LegalDocumentController {

    private final LegalDocumentIngestionService legalDocumentIngestionService;

    @PostMapping("/ingest")
    public ApiResponse<LegalDocumentIngestionStats> ingest() {
        return ApiResponse.ok(legalDocumentIngestionService.ingest());
    }

    @GetMapping("/stats")
    public ApiResponse<LegalDocumentIngestionStats> stats() {
        return ApiResponse.ok(legalDocumentIngestionService.stats());
    }
}
