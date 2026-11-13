package cn.apple.infrastructure.legal;

import cn.apple.domain.legal.adapter.repository.ILegalObjectStorage;
import cn.apple.domain.legal.model.valobj.LegalObjectWriteRequest;
import cn.apple.domain.legal.model.valobj.StoredLegalObject;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.io.InputStream;
import java.time.Instant;
import java.util.Map;

@Repository
@ConditionalOnProperty(name = "app.legal.object-storage.provider", havingValue = "minio")
public class MinioLegalObjectStorage implements ILegalObjectStorage {

    private final MinioClient client;
    private final String bucket;

    public MinioLegalObjectStorage(@Value("${app.legal.object-storage.minio.endpoint}") String endpoint,
                                   @Value("${app.legal.object-storage.minio.access-key}") String accessKey,
                                   @Value("${app.legal.object-storage.minio.secret-key}") String secretKey,
                                   @Value("${app.legal.object-storage.minio.bucket:legal-originals}") String bucket) {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
        ensureBucket();
    }

    @Override
    public StoredLegalObject putIfAbsent(LegalObjectWriteRequest request, InputStream content) {
        try {
            try {
                var stat = client.statObject(StatObjectArgs.builder().bucket(bucket).object(request.objectKey()).build());
                verifyExisting(request, stat);
                return stored(request);
            } catch (ErrorResponseException e) {
                if (!"NoSuchKey".equals(e.errorResponse().code())) {
                    throw e;
                }
            }
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(request.objectKey())
                    .stream(content, request.sizeBytes(), -1)
                    .contentType(request.contentType())
                    .headers(Map.of(
                            "x-amz-meta-sha256", request.sha256(),
                            "If-None-Match", "*"))
                    .build());
            verifyExisting(request, client.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(request.objectKey()).build()));
            return stored(request);
        } catch (Exception e) {
            throw new IllegalStateException("failed to store immutable legal object in MinIO", e);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new IllegalStateException("failed to open legal object from MinIO", e);
        }
    }

    private void ensureBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize legal object bucket", e);
        }
    }

    private void verifyExisting(LegalObjectWriteRequest request, io.minio.StatObjectResponse stat) {
        String checksum = stat.userMetadata().get("sha256");
        if (stat.size() != request.sizeBytes() || !request.sha256().equalsIgnoreCase(checksum)) {
            throw new IllegalStateException("immutable MinIO object checksum or size mismatch: " + request.objectKey());
        }
    }

    private StoredLegalObject stored(LegalObjectWriteRequest request) {
        return new StoredLegalObject(request.objectKey(), "s3://" + bucket + "/" + request.objectKey(),
                request.originalName(), request.contentType(), request.sizeBytes(), request.sha256(), Instant.now());
    }
}
