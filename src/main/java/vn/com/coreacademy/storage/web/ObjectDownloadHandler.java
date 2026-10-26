package vn.com.coreacademy.storage.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import vn.com.coreacademy.storage.dto.ObjectDownload;
import vn.com.coreacademy.storage.service.ObjectStorage;

/** Truyền object qua HTTP; bên gọi phải kiểm tra quyền và kết thúc transaction trước khi gọi. */
@Component
@RequiredArgsConstructor
public class ObjectDownloadHandler {
    private final ObjectStorage storage;

    public void write(ObjectDownload object, HttpServletRequest request, HttpServletResponse response)
        throws IOException {
        var info = storage.stat(object.key());
        if (info.size() != object.size()) {
            throw new IllegalStateException("Object size differs from asset metadata");
        }

        ByteRange range = requestedRange(request, response, info);
        if ("HEAD".equals(request.getMethod()) || range.length() == 0) {
            contentHeaders(response, object, info.etag(), range, info.size());
            return;
        }

        // Mở S3 thành công trước khi ghi header; luôn đóng stream khi client ngắt kết nối.
        try (InputStream input = storage.read(object.key(), range.offset(), range.length(), info.etag())) {
            contentHeaders(response, object, info.etag(), range, info.size());
            copy(input, response, range.length());
        }
    }

    private ByteRange requestedRange(HttpServletRequest request, HttpServletResponse response,
                                     ObjectStorage.ObjectInfo info) {
        // Range chỉ áp dụng GET; If-Range không khớp ETag thì trả toàn file.
        String header = "GET".equals(request.getMethod()) ? request.getHeader(HttpHeaders.RANGE) : null;
        String ifRange = request.getHeader(HttpHeaders.IF_RANGE);
        if (ifRange != null && !ifRange.equals("\"" + info.etag() + "\"")) {
            header = null;
        }
        try {
            return ByteRange.parse(header, info.size());
        } catch (IllegalArgumentException exception) {
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + info.size());
            throw new ResponseStatusException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        }
    }

    private void contentHeaders(HttpServletResponse response, ObjectDownload object, String etag,
                                ByteRange range, long total) {
        response.setStatus(range.partial() ? HttpStatus.PARTIAL_CONTENT.value() : HttpStatus.OK.value());
        response.setContentType(MediaType.parseMediaType(object.mimeType()).toString());
        response.setContentLengthLong(range.length());
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.ETAG, "\"" + etag + "\"");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.inline().filename(object.name(), StandardCharsets.UTF_8).build().toString());
        if (range.partial()) {
            response.setHeader(HttpHeaders.CONTENT_RANGE, range.contentRange(total));
        }
    }

    private void copy(InputStream input, HttpServletResponse response, long length) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) {
                throw new IOException("Object stream ended before the expected byte count");
            }
            response.getOutputStream().write(buffer, 0, count);
            remaining -= count;
        }
    }
}
