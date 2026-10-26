package vn.com.coreacademy.storage.config;

import java.net.URI;
import java.time.Duration;

import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(S3Properties.class)
public class S3Config {
    /** Chỉ kết nối data plane của bucket đã cấp; không tạo bucket/IAM hoặc bỏ kiểm tra TLS. */
    @Bean(destroyMethod = "close")
    MinioClient minioClient(S3Properties properties) {
        URI endpoint = URI.create(properties.endpoint());
        if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
            || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
            || endpoint.getFragment() != null || !(endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()))) {
            throw new IllegalArgumentException("S3 endpoint must be an HTTP(S) origin without credentials or path");
        }

        var http = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
            .readTimeout(Duration.ofSeconds(properties.readTimeoutSeconds()))
            .writeTimeout(Duration.ofSeconds(properties.readTimeoutSeconds()))
            .callTimeout(Duration.ofSeconds(properties.callTimeoutSeconds()))
            .retryOnConnectionFailure(false).build();
        MinioClient client = MinioClient.builder().endpoint(properties.endpoint()).region(properties.region())
            .credentials(properties.accessKey(), properties.secretKey()).httpClient(http, true).build();
        if (properties.pathStyleAccess()) {
            client.disableVirtualStyleEndpoint();
        } else {
            client.enableVirtualStyleEndpoint();
        }
        return client;
    }

    @Bean(destroyMethod = "close")
    MinioAsyncClient multipartClient(S3Properties properties, MinioClient validatedClient) {
        // validatedClient bảo đảm cấu hình origin đã được kiểm trước khi tạo client multipart.
        var http = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
            .readTimeout(Duration.ofSeconds(properties.readTimeoutSeconds()))
            .writeTimeout(Duration.ofSeconds(properties.readTimeoutSeconds()))
            .callTimeout(Duration.ofSeconds(properties.multipartCallTimeoutSeconds()))
            .retryOnConnectionFailure(false).build();
        var client = MinioAsyncClient.builder().endpoint(properties.endpoint()).region(properties.region())
            .credentials(properties.accessKey(), properties.secretKey()).httpClient(http, true).build();
        if (properties.pathStyleAccess()) {
            client.disableVirtualStyleEndpoint();
        } else {
            client.enableVirtualStyleEndpoint();
        }
        return client;
    }
}
