package cn.apple.infrastructure.legal;

import cn.apple.domain.legal.adapter.repository.ILegalObjectStorage;
import cn.apple.domain.legal.model.valobj.LegalObjectWriteRequest;
import cn.apple.domain.legal.model.valobj.StoredLegalObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "app.legal.object-storage.provider", havingValue = "filesystem", matchIfMissing = true)
public class FilesystemLegalObjectStorage implements ILegalObjectStorage {

    private final Path root;

    public FilesystemLegalObjectStorage(
            @Value("${app.legal.object-storage.filesystem-root:data/legal/objects}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public StoredLegalObject putIfAbsent(LegalObjectWriteRequest request, InputStream content) {
        Path target = resolve(request.objectKey());
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                verifyExisting(target, request);
                return stored(request, target);
            }
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
            try {
                Files.copy(content, temporary);
                verifyExisting(temporary, request);
                moveAtomically(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            verifyExisting(target, request);
            return stored(request, target);
        } catch (IOException e) {
            throw new IllegalStateException("failed to store immutable legal object", e);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return Files.newInputStream(resolve(objectKey));
        } catch (IOException e) {
            throw new IllegalStateException("failed to open legal object", e);
        }
    }

    private Path resolve(String objectKey) {
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("objectKey escapes legal object storage root");
        }
        return target;
    }

    private void verifyExisting(Path path, LegalObjectWriteRequest request) throws IOException {
        if (Files.size(path) != request.sizeBytes()) {
            throw new IllegalStateException("immutable legal object size mismatch: " + request.objectKey());
        }
        try (InputStream input = Files.newInputStream(path);
             DigestInputStream digestInput = new DigestInputStream(input, MessageDigest.getInstance("SHA-256"))) {
            digestInput.transferTo(java.io.OutputStream.nullOutputStream());
            String checksum = HexFormat.of().formatHex(digestInput.getMessageDigest().digest());
            if (!checksum.equals(request.sha256())) {
                throw new IllegalStateException("immutable legal object checksum mismatch: " + request.objectKey());
            }
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // Another uploader won the content-addressed write.
        }
    }

    private StoredLegalObject stored(LegalObjectWriteRequest request, Path target) {
        return new StoredLegalObject(request.objectKey(), target.toUri().toString(), request.originalName(),
                request.contentType(), request.sizeBytes(), request.sha256(), Instant.now());
    }
}
