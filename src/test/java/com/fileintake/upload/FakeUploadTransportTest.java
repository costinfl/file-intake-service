package com.fileintake.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FakeUploadTransportTest {

    private final FakeUploadTransport transport = new FakeUploadTransport();

    @Test
    void verifyDefaultsToNotFoundWhenNothingUploaded() {
        UploadedObject result = transport.verify("staging/sub-1/file-1");

        assertThat(result.exists()).isFalse();
    }

    @Test
    void initReturnsUrlScopedToObjectPathWithoutMarkingItUploaded() {
        InitUpload init =
                transport.init(new InitUploadRequest("staging/sub-1/file-1", 1024, Duration.ofMinutes(15)));

        assertThat(init.uploadUrl()).contains("staging/sub-1/file-1");
        assertThat(transport.verify("staging/sub-1/file-1").exists()).isFalse();
    }

    @Test
    void simulateUploadSuccessMakesVerifyReportTheUploadedObject() {
        transport.simulateUploadSuccess("staging/sub-1/file-1", 2048, "AAAAAA==");

        UploadedObject result = transport.verify("staging/sub-1/file-1");

        assertThat(result.exists()).isTrue();
        assertThat(result.sizeBytes()).isEqualTo(2048);
        assertThat(result.crc32c()).isEqualTo("AAAAAA==");
        assertThat(result.generation()).isEqualTo(1);
    }

    @Test
    void repeatedUploadSuccessBumpsGeneration() {
        transport.simulateUploadSuccess("staging/sub-1/file-1", 2048, "AAAAAA==");
        transport.simulateUploadSuccess("staging/sub-1/file-1", 4096, "BBBBBB==");

        assertThat(transport.verify("staging/sub-1/file-1").generation()).isEqualTo(2);
    }

    @Test
    void simulateUploadFailureClearsAnyPriorSuccess() {
        transport.simulateUploadSuccess("staging/sub-1/file-1", 2048, "AAAAAA==");

        transport.simulateUploadFailure("staging/sub-1/file-1");

        assertThat(transport.verify("staging/sub-1/file-1").exists()).isFalse();
    }

    @Test
    void resetClearsAllState() {
        transport.simulateUploadSuccess("staging/sub-1/file-1", 2048, "AAAAAA==");

        transport.reset();

        assertThat(transport.verify("staging/sub-1/file-1").exists()).isFalse();
    }
}
