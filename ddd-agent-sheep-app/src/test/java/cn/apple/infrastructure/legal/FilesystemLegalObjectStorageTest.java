package cn.apple.infrastructure.legal;

import cn.apple.domain.legal.model.valobj.LegalObjectWriteRequest;
import cn.apple.domain.legal.model.valobj.StoredLegalObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class FilesystemLegalObjectStorageTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldStoreContentAddressedObjectIdempotently() throws Exception {
        byte[] content = "immutable legal original".getBytes(StandardCharsets.UTF_8);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        LegalObjectWriteRequest request = new LegalObjectWriteRequest(
                "public_law/public/" + checksum.substring(0, 2) + "/" + checksum + ".txt",
                "law.txt", "text/plain", content.length, checksum);
        FilesystemLegalObjectStorage storage = new FilesystemLegalObjectStorage(
                temporaryFolder.newFolder("objects").getAbsolutePath());

        StoredLegalObject first = storage.putIfAbsent(request, new ByteArrayInputStream(content));
        StoredLegalObject second = storage.putIfAbsent(request, new ByteArrayInputStream(content));

        assertEquals(first.objectUri(), second.objectUri());
        try (var input = storage.open(request.objectKey())) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }
}
