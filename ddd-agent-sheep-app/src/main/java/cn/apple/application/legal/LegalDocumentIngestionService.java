package cn.apple.application.legal;

import cn.apple.domain.legal.model.entity.LegalDocumentIngestionStats;
import cn.apple.domain.legal.service.LegalDocumentIngestionDomainService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LegalDocumentIngestionService {

    private final LegalDocumentIngestionDomainService legalDocumentIngestionDomainService;

    public LegalDocumentIngestionStats ingest() {
        return legalDocumentIngestionDomainService.ingest();
    }

    public LegalDocumentIngestionStats stats() {
        return legalDocumentIngestionDomainService.stats();
    }
}

