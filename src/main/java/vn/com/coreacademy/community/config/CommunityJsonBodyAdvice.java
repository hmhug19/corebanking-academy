package vn.com.coreacademy.community.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.zip.GZIPInputStream;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Đọc tối đa 8 MiB sau giải nén trước khi Jackson dựng object; không áp vào streaming upload part. */
@ControllerAdvice(basePackages = "vn.com.coreacademy.community.controller")
public class CommunityJsonBodyAdvice extends RequestBodyAdviceAdapter {
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return MappingJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        String encoding = input.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
        InputStream stream = input.getBody();
        if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
            if (!"gzip".equalsIgnoreCase(encoding)) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            }
            stream = new GZIPInputStream(stream);
        }
        byte[] bytes;
        try (InputStream boundedSource = stream) {
            bytes = boundedSource.readNBytes(MAX_JSON_BYTES + 1);
        }
        if (bytes.length > MAX_JSON_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(input.getHeaders());
        headers.remove(HttpHeaders.CONTENT_ENCODING);
        headers.setContentLength(bytes.length);
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }
}
