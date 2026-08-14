package com.fileintake.upload.xml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fileintake.upload.InitUpload;
import com.fileintake.upload.InitUploadRequest;
import com.fileintake.upload.UploadedObject;
import com.google.auth.ServiceAccountSigner;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.SignUrlOption;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class XmlMultipartTransportTest {

    private static final String BUCKET = "test-bucket";

    private final Storage storage = mock(Storage.class);
    private final XmlMultipartTransport transport = new XmlMultipartTransport(storage, BUCKET, Optional.empty());

    @Test
    void initReturnsSignedUrlWithRequiredHeaders() throws MalformedURLException {
        URL signedUrl =
                URI.create("https://storage.googleapis.com/" + BUCKET + "/staging/sub-1/file-1?X-Goog-Signature=abc")
                        .toURL();
        when(storage.signUrl(
                        any(BlobInfo.class),
                        anyLong(),
                        eq(TimeUnit.MINUTES),
                        any(SignUrlOption.class),
                        any(SignUrlOption.class),
                        any(SignUrlOption.class)))
                .thenReturn(signedUrl);

        InitUpload result =
                transport.init(new InitUploadRequest("staging/sub-1/file-1", 50_000_000L, Duration.ofMinutes(15)));

        assertThat(result.uploadUrl()).isEqualTo(signedUrl.toString());
        assertThat(result.requiredHeaders()).containsEntry("x-goog-content-length-range", "50000000-50000000");
        assertThat(result.requiredHeaders()).containsEntry("x-goog-if-generation-match", "0");
        assertThat(result.expiresAt()).isNotNull();
    }

    @Test
    void initPassesLocalSignerExplicitlyWhenPresent() throws MalformedURLException {
        URL signedUrl = URI.create("http://localhost:4443/" + BUCKET + "/staging/sub-1/file-1?X-Goog-Signature=abc").toURL();
        ServiceAccountSigner localSigner = mock(ServiceAccountSigner.class);
        XmlMultipartTransport emulatorTransport = new XmlMultipartTransport(storage, BUCKET, Optional.of(localSigner));
        when(storage.signUrl(
                        any(BlobInfo.class),
                        anyLong(),
                        eq(TimeUnit.MINUTES),
                        any(SignUrlOption.class),
                        any(SignUrlOption.class),
                        any(SignUrlOption.class),
                        any(SignUrlOption.class)))
                .thenReturn(signedUrl);

        InitUpload result =
                emulatorTransport.init(new InitUploadRequest("staging/sub-1/file-1", 50_000_000L, Duration.ofMinutes(15)));

        assertThat(result.uploadUrl()).isEqualTo(signedUrl.toString());
    }

    @Test
    void verifyReturnsNotFoundWhenBlobMissing() {
        when(storage.get(BlobId.of(BUCKET, "staging/sub-1/file-1"))).thenReturn(null);

        UploadedObject result = transport.verify("staging/sub-1/file-1");

        assertThat(result.exists()).isFalse();
    }

    @Test
    void verifyMapsBlobMetadataWhenPresent() {
        Blob blob = mock(Blob.class);
        when(blob.getSize()).thenReturn(50_000_000L);
        when(blob.getCrc32c()).thenReturn("AAAAAA==");
        when(blob.getGeneration()).thenReturn(42L);
        when(storage.get(BlobId.of(BUCKET, "staging/sub-1/file-1"))).thenReturn(blob);

        UploadedObject result = transport.verify("staging/sub-1/file-1");

        assertThat(result.exists()).isTrue();
        assertThat(result.sizeBytes()).isEqualTo(50_000_000L);
        assertThat(result.crc32c()).isEqualTo("AAAAAA==");
        assertThat(result.generation()).isEqualTo(42L);
    }
}
