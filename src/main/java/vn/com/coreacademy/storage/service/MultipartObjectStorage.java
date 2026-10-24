package vn.com.coreacademy.storage.service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletionException;

import io.minio.AbortMultipartUploadArgs;
import io.minio.CompleteMultipartUploadArgs;
import io.minio.CreateMultipartUploadArgs;
import io.minio.Http;
import io.minio.ListPartsArgs;
import io.minio.MinioAsyncClient;
import io.minio.UploadPartArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Part;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.storage.config.S3Properties;

/** Adapter multipart S3; không giữ quyền, manifest hoặc policy của module nghiệp vụ. */
@Service
@RequiredArgsConstructor
@Slf4j
public class MultipartObjectStorage {
    private final MinioAsyncClient client;
    private final S3Properties properties;

    public record CommittedPart(int number, String etag, long size) {
    }

    public String initiate(String key) {
        validate(key);
        try {
            return client.createMultipartUpload(CreateMultipartUploadArgs.builder()
                .bucket(properties.bucket()).object(key).build()).join().result().uploadId();
        } catch (CompletionException exception) {
            throw storageFailure(exception);
        }
    }

    public String put(String key, String uploadId, int number, Path path, long size, String sha256) throws IOException {
        validate(key);
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            return client.uploadPart(UploadPartArgs.builder().bucket(properties.bucket()).object(key)
                .uploadId(uploadId).partNumber(number).file(file, size)
                .headers(new Http.Headers(Http.Headers.X_AMZ_CONTENT_SHA256, sha256,
                    Http.Headers.CONTENT_MD5, transportMd5(path))).build()).join().part().etag();
        } catch (CompletionException exception) {
            throw storageFailure(exception);
        }
    }

    public void complete(String key, String uploadId, List<CommittedPart> parts) {
        validate(key);
        try {
            var actual = client.listParts(partsRequest(key, uploadId)).join().result();
            if (actual.isTruncated() || actual.parts().size() != parts.size()) {
                throw new IllegalStateException("Multipart provider manifest differs");
            }
            for (int index = 0; index < parts.size(); index++) {
                if (actual.parts().get(index).partNumber() != parts.get(index).number()
                    || actual.parts().get(index).partSize() != parts.get(index).size()) {
                    throw new IllegalStateException("Multipart provider part differs");
                }
            }
            client.completeMultipartUpload(CompleteMultipartUploadArgs.builder().bucket(properties.bucket())
                .object(key).uploadId(uploadId).parts(actual.parts().toArray(Part[]::new)).build()).join();
        } catch (CompletionException exception) {
            throw storageFailure(exception);
        }
    }

    public void abort(String key, String uploadId) {
        validate(key);
        if (uploadId == null) {
            return;
        }
        try {
            client.abortMultipartUpload(AbortMultipartUploadArgs.builder().bucket(properties.bucket())
                .object(key).uploadId(uploadId).build()).join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof ErrorResponseException response
                && "NoSuchUpload".equals(response.errorResponse().code())) {
                return;
            }
            throw storageFailure(exception);
        }
    }

    private String transportMd5(Path path) throws IOException {
        // MD5 chỉ là checksum vận chuyển theo giao thức S3; danh tính part vẫn dùng SHA-256 đã kiểm.
        // Cung cấp sẵn checksum để SDK 9.0.3 không đọc hết RandomAccessFile trước khi gửi body.
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("S3 transport checksum unavailable", exception);
        }
    }

    private ListPartsArgs partsRequest(String key, String uploadId) {
        // SDK 9.0.3 dùng BucketArgs.Builder cho ListPartsArgs, thiếu setter object.
        // Cô lập tương thích ở adapter; không reflection hoặc đổi SDK toàn ứng dụng.
        return new ListPartsArgs() {
            @Override
            public String bucket() {
                return properties.bucket();
            }

            @Override
            public String object() {
                return key;
            }

            @Override
            public String uploadId() {
                return uploadId;
            }

            @Override
            public Integer maxParts() {
                return 1000;
            }
        };
    }

    private IllegalStateException storageFailure(CompletionException exception) {
        Throwable cause = exception.getCause();
        String code = cause instanceof ErrorResponseException response ? response.errorResponse().code() : "";
        log.error("S3 multipart failed type={} code={} location={}", cause.getClass().getSimpleName(), code,
            cause.getStackTrace().length == 0 ? "" : cause.getStackTrace()[0]);
        return new IllegalStateException("Object storage multipart operation failed", cause);
    }

    private void validate(String key) {
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9/_-]{0,254}")) {
            throw new IllegalArgumentException("Invalid object key");
        }
    }
}
