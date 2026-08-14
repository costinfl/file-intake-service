package com.fileintake.upload.xml;

import com.fileintake.upload.InitUpload;
import com.fileintake.upload.InitUploadRequest;
import com.fileintake.upload.UploadTransport;
import com.fileintake.upload.UploadedObject;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.SignUrlOption;
import java.net.URL;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real {@link UploadTransport}, backed by GCS's XML API (the endpoint {@link Storage#signUrl}
 * targets). Signs with IAM SignBlob under Workload Identity — see {@link StorageConfig} — rather
 * than static HMAC access keys, so the deployed service account needs {@code
 * roles/iam.serviceAccountTokenCreator} on itself. This yields one presigned PUT URL per file,
 * which pairs with Uppy's plain {@code aws-s3} plugin (a custom getUploadParameters callback
 * returning {url, method, headers} unchanged); true chunked multipart (Uppy's {@code
 * aws-s3-multipart} plugin) would need several signed URLs per file and is out of scope for the
 * current {@link UploadTransport} contract.
 */
@Component
@ConditionalOnProperty(name = "fileintake.upload.transport", havingValue = "xml-multipart")
public class XmlMultipartTransport implements UploadTransport {

    private static final String CONTENT_LENGTH_RANGE_HEADER = "x-goog-content-length-range";
    private static final String IF_GENERATION_MATCH_HEADER = "x-goog-if-generation-match";

    private final Storage storage;
    private final String bucket;

    public XmlMultipartTransport(Storage storage, @Value("${fileintake.upload.bucket}") String bucket) {
        this.storage = storage;
        this.bucket = bucket;
    }

    @Override
    public InitUpload init(InitUploadRequest request) {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, request.objectPath())).build();

        Map<String, String> requiredHeaders = new LinkedHashMap<>();
        requiredHeaders.put(CONTENT_LENGTH_RANGE_HEADER, request.declaredSize() + "-" + request.declaredSize());
        // A retry can't overwrite a different file that raced onto the same generation.
        requiredHeaders.put(IF_GENERATION_MATCH_HEADER, "0");

        URL signedUrl =
                storage.signUrl(
                        blobInfo,
                        request.credentialTtl().toMinutes(),
                        TimeUnit.MINUTES,
                        SignUrlOption.httpMethod(HttpMethod.PUT),
                        SignUrlOption.withV4Signature(),
                        SignUrlOption.withExtHeaders(requiredHeaders));

        Instant expiresAt = Instant.now().plus(request.credentialTtl());
        return new InitUpload(signedUrl.toString(), requiredHeaders, expiresAt);
    }

    @Override
    public UploadedObject verify(String objectPath) {
        // storage.get returns null for a missing object; a non-null result already carries
        // metadata from this same call, so no separate blob.exists() round trip is needed.
        Blob blob = storage.get(BlobId.of(bucket, objectPath));
        if (blob == null) {
            return UploadedObject.notFound();
        }
        return new UploadedObject(true, blob.getSize(), blob.getCrc32c(), blob.getGeneration());
    }
}
