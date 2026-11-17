package cn.apple.domain.legal.adapter.repository;

import cn.apple.domain.legal.model.valobj.LegalObjectWriteRequest;
import cn.apple.domain.legal.model.valobj.StoredLegalObject;

import java.io.InputStream;

public interface ILegalObjectStorage {

    StoredLegalObject putIfAbsent(LegalObjectWriteRequest request, InputStream content);

    InputStream open(String objectKey);
}
