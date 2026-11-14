package vn.com.coreacademy.storage.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import io.minio.GetObjectArgs;
import io.minio.GetBucketVersioningArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import io.minio.messages.VersioningConfiguration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.storage.config.S3Properties;

/** Kho S3 dùng chung; bên gọi chịu trách nhiệm quyền, validation và metadata nghiệp vụ. */
@Service
@RequiredArgsConstructor
public class ObjectStorage {
    public static final String TYPE = "s3";

    private final MinioClient client;
    private final S3Properties properties;

    public record ObjectInfo(long size, String etag) {
    }

    public void put(String key, Path file, String mime, String checksum) throws IOException {
        validateKey(key);
        try (InputStream input = Files.newInputStream(file)) {
            client.putObject(PutObjectArgs.builder().bucket(properties.bucket()).object(key)
                .stream(input, Files.size(file), 10L * 1024 * 1024).contentType(mime)
                .userMetadata(Map.of("sha256", checksum)).build());
        } catch (MinioException exception) {
            throw new IllegalStateException("Object storage upload failed", exception);
        }
    }

    public ObjectInfo stat(String key) {
        validateKey(key);
        try {
            var value = client.statObject(StatObjectArgs.builder().bucket(properties.bucket()).object(key).build());
            return new ObjectInfo(value.size(), value.etag());
        } catch (MinioException exception) {
            throw new IllegalStateException("Object storage metadata read failed", exception);
        }
    }

    /** Đọc đúng byte range; bên gọi phải đóng stream, kể cả khi client ngắt kết nối. */
    public InputStream read(String key, long offset, long length, String etag) {
        validateKey(key);
        if (offset < 0 || length <= 0 || offset > Long.MAX_VALUE - length) {
            throw new IllegalArgumentException("Invalid object byte range");
        }
        try {
            var response = client.getObject(GetObjectArgs.builder().bucket(properties.bucket()).object(key)
                .offset(offset).length(length).matchETag("\"" + etag + "\"").build());
            String expectedRange = "bytes " + offset + "-" + (offset + length - 1) + "/";
            String actualRange = response.headers().get("Content-Range");
            if (actualRange == null || !actualRange.startsWith(expectedRange)
                || !Long.toString(length).equals(response.headers().get("Content-Length"))) {
                response.close();
                throw new IllegalStateException("Object storage returned an unexpected byte range");
            }
            return response;
        } catch (MinioException | IOException exception) {
            throw new IllegalStateException("Object storage download failed", exception);
        }
    }

    /** URL ký ngắn hạn chỉ trả cho gateway tin cậy, không trả qua API thư viện/player. */
    public String gatewayUrl(String key, boolean head) {
        validateKey(key);
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().bucket(properties.bucket())
                .object(key).method(head ? Method.HEAD : Method.GET).expiry(60).build());
        } catch (MinioException exception) {
            throw new IllegalStateException("Object storage signing failed", exception);
        }
    }

    public boolean exists(String key) {
        validateKey(key);
        try {
            client.statObject(StatObjectArgs.builder().bucket(properties.bucket()).object(key).build());
            return true;
        } catch (ErrorResponseException exception) {
            if ("NoSuchKey".equals(exception.errorResponse().code())) {
                return false;
            }
            throw new IllegalStateException("Object storage metadata read failed", exception);
        } catch (MinioException exception) {
            throw new IllegalStateException("Object storage metadata read failed", exception);
        }
    }

    public void delete(String key) {
        validateKey(key);
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(properties.bucket()).object(key).build());
        } catch (MinioException exception) {
            throw new IllegalStateException("Object storage delete failed", exception);
        }
    }

    /** Versioned/suspended buckets may retain old bytes after an ordinary DELETE. */
    public void requireUnversionedBucket() {
        try {
            if (client.getBucketVersioning(GetBucketVersioningArgs.builder().bucket(properties.bucket()).build())
                .status() != VersioningConfiguration.Status.OFF) {
                throw new IllegalStateException("Permanent deletion requires an unversioned bucket");
            }
        } catch (MinioException exception) {
            throw new IllegalStateException("Cannot verify bucket versioning", exception);
        }
    }

    private void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9/_-]{0,254}")) {
            throw new IllegalArgumentException("Invalid object key");
        }
    }
}
