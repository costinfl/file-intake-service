package com.fileintake.submission;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "submission")
@Getter
@Setter
@NoArgsConstructor
public class Submission implements Persistable<UUID> {

    @Id
    private UUID id;

    // IDs are assigned in the service layer (see class javadoc-free rationale in the plan:
    // object_path embeds the file's own id, so it must exist before the first INSERT).
    // Persistable.isNew() is required because Hibernate's default new-vs-detached check
    // (id == null) always says "detached" for app-assigned ids, which would route save()
    // through merge() instead of persist() and fail with EntityNotFound on first insert.
    @Transient
    private boolean isNew = true;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SubmissionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "committed_at")
    private Instant committedAt;

    @OneToMany(mappedBy = "submission", cascade = CascadeType.PERSIST)
    @OrderBy("ordinal ASC")
    private List<SubmissionFile> files = new ArrayList<>();

    public Submission(UUID id, String ownerId, SubmissionStatus status, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.status = status;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.isNew = true;
    }

    public void addFile(SubmissionFile file) {
        file.setSubmission(this);
        this.files.add(file);
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
