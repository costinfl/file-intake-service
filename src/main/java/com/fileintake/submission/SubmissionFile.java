package com.fileintake.submission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "submission_file")
@Getter
@Setter
@NoArgsConstructor
public class SubmissionFile implements Persistable<UUID> {

    @Id
    private UUID id;

    // See Submission for why Persistable is needed with app-assigned UUIDs.
    @Transient
    private boolean isNew = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "object_path", nullable = false, length = 512)
    private String objectPath;

    @Column(name = "declared_name", nullable = false)
    private String declaredName;

    @Column(name = "declared_size", nullable = false)
    private long declaredSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SubmissionFileStatus status;

    @Column(name = "gcs_generation")
    private Long gcsGeneration;

    @Column(name = "crc32c", length = 16)
    private String crc32c;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public SubmissionFile(
            UUID id,
            int ordinal,
            String objectPath,
            String declaredName,
            long declaredSize,
            SubmissionFileStatus status,
            Instant updatedAt) {
        this.id = id;
        this.ordinal = ordinal;
        this.objectPath = objectPath;
        this.declaredName = declaredName;
        this.declaredSize = declaredSize;
        this.status = status;
        this.updatedAt = updatedAt;
        this.isNew = true;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
