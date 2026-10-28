package vn.com.coreacademy.storage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.minio.CompleteMultipartUploadArgs;
import io.minio.MinioAsyncClient;
import io.minio.messages.Part;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.com.coreacademy.storage.config.S3Properties;
import vn.com.coreacademy.storage.service.MultipartObjectStorage;
import vn.com.coreacademy.storage.service.MultipartObjectStorage.CommittedPart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MultipartObjectStorageTest {
    private final MinioAsyncClient client = mock(MinioAsyncClient.class);
    private final MultipartObjectStorage storage = new MultipartObjectStorage(client,
        new S3Properties("http://127.0.0.1:19029", "community-integration-test", "us-east-1",
            "synthetic-test-access", "synthetic-test-secret", true, 5, 60, 600));

    @Test
    void completeUsesAcknowledgedPartNumbersAndEtags() {
        when(client.completeMultipartUpload(any())).thenReturn(CompletableFuture.completedFuture(null));
        storage.complete("community/test-object", "test-upload", List.of(
            new CommittedPart(1, "etag-first", 8_388_608), new CommittedPart(2, "etag-last", 512)));

        var request = ArgumentCaptor.forClass(CompleteMultipartUploadArgs.class);
        verify(client).completeMultipartUpload(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("community-integration-test");
        assertThat(request.getValue().uploadId()).isEqualTo("test-upload");
        assertThat(request.getValue().parts()).extracting(Part::partNumber).containsExactly(1, 2);
        assertThat(request.getValue().parts()).extracting(Part::etag).containsExactly("etag-first", "etag-last");
    }

    @Test
    void incompleteOrUnorderedManifestIsRejectedBeforeProviderCall() {
        assertThatThrownBy(() -> storage.complete("community/test-object", "test-upload", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.complete("community/test-object", "test-upload",
            List.of(new CommittedPart(2, "etag", 512)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.complete("community/test-object", "test-upload",
            List.of(new CommittedPart(1, "", 512)))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    void providerFailureDoesNotBecomeSuccessfulCompletion() {
        when(client.completeMultipartUpload(any())).thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("Synthetic provider failure")));
        assertThatThrownBy(() -> storage.complete("community/test-object", "test-upload",
            List.of(new CommittedPart(1, "etag", 512)))).isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
