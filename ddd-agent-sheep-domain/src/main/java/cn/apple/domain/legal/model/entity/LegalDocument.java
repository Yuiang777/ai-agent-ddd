package cn.apple.domain.legal.model.entity;

import cn.apple.domain.legal.model.valobj.LegalDocumentSource;

public record LegalDocument(LegalDocumentSource source, String lawName, String content) {
}
