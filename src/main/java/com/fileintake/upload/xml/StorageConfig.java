package com.fileintake.upload.xml;

import com.google.auth.ServiceAccountSigner;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.NoCredentials;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Real GCS (default): {@link StorageOptions#getDefaultInstance()} resolves credentials via
 * Application Default Credentials, which on GKE means the pod's Workload Identity-bound service
 * account — no SA JSON key is read from disk or config. {@code signUrl()} falls back to those
 * same credentials as the signer, which under Workload Identity means IAM SignBlob.
 *
 * <p>Local emulator (fileintake.upload.gcs-host set, e.g. by docker-compose's fake-gcs-server):
 * ordinary API calls (bucket create/get, blob get) use {@link NoCredentials} — the emulator
 * doesn't check auth, and a fake {@code ServiceAccountCredentials} would otherwise try to
 * authenticate against real Google OAuth and fail outright. {@code signUrl()} still needs
 * something that can produce a real signature, so a throwaway in-memory RSA keypair is exposed as
 * a separate {@code ServiceAccountSigner} bean — present only in emulator mode, via {@code
 * Optional<ServiceAccountSigner>} constructor injection into {@link XmlMultipartTransport}, which
 * passes it explicitly via {@code SignUrlOption.signWith(...)} only when present. fake-gcs-server
 * doesn't validate the signature, only that the URL is shaped like one. The keypair is generated
 * fresh on every startup and never persisted; it grants access to nothing real.
 *
 * <p>Only active when the real transport is selected at all, so local dev/tests using the fake
 * transport never need GCP credentials or a running emulator.
 */
@Configuration
@ConditionalOnProperty(name = "fileintake.upload.transport", havingValue = "xml-multipart")
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    public Storage storage(
            @Value("${fileintake.upload.gcs-host:}") String gcsHost,
            @Value("${fileintake.upload.gcs-project-id:local-project}") String projectId,
            @Value("${fileintake.upload.bucket}") String bucket) {
        if (gcsHost.isBlank()) {
            return StorageOptions.getDefaultInstance().getService();
        }

        log.warn("Using GCS emulator at {} — not for production use", gcsHost);
        Storage storage =
                StorageOptions.newBuilder()
                        .setHost(gcsHost)
                        .setProjectId(projectId)
                        .setCredentials(NoCredentials.getInstance())
                        .build()
                        .getService();

        if (storage.get(bucket) == null) {
            storage.create(BucketInfo.of(bucket));
            log.info("Created bucket {} on GCS emulator at {}", bucket, gcsHost);
        }
        return storage;
    }

    /**
     * Registered only when {@code fileintake.upload.gcs-host} resolves to a non-empty value.
     * Uses {@code @ConditionalOnExpression} rather than {@code @ConditionalOnProperty} because
     * the property is deliberately declared with an empty-string default in application.yml (so
     * {@code @Value} injection above never fails on a missing key) — a plain presence check would
     * see that empty-string default as "present" and register this bean unconditionally.
     *
     * <p>Declared as a bare {@code ServiceAccountSigner}, not {@code Optional<ServiceAccountSigner>}
     * — Spring's constructor injection has built-in handling for {@code Optional<T>} parameters
     * that resolves against beans of the raw type {@code T} and wraps them, so a bean whose own
     * declared type is literally {@code Optional<T>} is never matched by it and would silently
     * leave {@link XmlMultipartTransport}'s {@code localSigner} parameter empty.
     */
    @Bean
    @ConditionalOnExpression("!'${fileintake.upload.gcs-host:}'.isEmpty()")
    public ServiceAccountSigner localUrlSigner(@Value("${fileintake.upload.gcs-project-id:local-project}") String projectId)
            throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        return ServiceAccountCredentials.newBuilder()
                .setClientEmail("fake-local-signer@" + projectId + ".iam.gserviceaccount.com")
                .setPrivateKey(keyPair.getPrivate())
                .setPrivateKeyId("local-fake-key")
                .setProjectId(projectId)
                .build();
    }
}
