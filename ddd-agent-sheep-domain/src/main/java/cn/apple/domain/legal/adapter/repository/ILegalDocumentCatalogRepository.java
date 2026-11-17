package cn.apple.domain.legal.adapter.repository;

import cn.apple.domain.legal.model.valobj.LegalDocumentRegistration;

public interface ILegalDocumentCatalogRepository {

    /** Fail before writing objects when this adapter is intentionally disabled. */
    default void ensureWritable() { }

    void register(LegalDocumentRegistration registration);
}
