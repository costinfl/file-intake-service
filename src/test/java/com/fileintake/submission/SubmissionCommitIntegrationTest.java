package com.fileintake.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fileintake.outbox.OutboxEvent;
import com.fileintake.outbox.OutboxRepository;
import com.fileintake.submission.dto.CreateSubmissionResponse;
import com.fileintake.submission.dto.FileRef;
import com.fileintake.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

class SubmissionCommitIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private SubmissionFileRepository submissionFileRepository;

    private String objectPathFor(UUID submissionId, UUID fileId) {
        return "staging/" + submissionId + "/" + fileId;
    }

    private CreateSubmissionResponse createAndUploadAllFiles() throws Exception {
        CreateSubmissionResponse submission = createSubmission("owner-1");
        for (FileRef file : submission.files()) {
            mockMvc.perform(
                    post("/submissions/" + submission.submissionId() + "/files/" + file.fileId() + "/upload-credentials"));
            fakeUploadTransport.simulateUploadSuccess(
                    objectPathFor(submission.submissionId(), file.fileId()), 50_000_000L, "AAAAAA==");
            mockMvc.perform(post("/submissions/" + submission.submissionId() + "/files/" + file.fileId() + "/complete"));
        }
        return submission;
    }

    @Test
    void commitHappyPathInsertsExactlyOneOutboxRow() throws Exception {
        CreateSubmissionResponse submission = createAndUploadAllFiles();

        mockMvc
                .perform(post("/submissions/" + submission.submissionId() + "/commit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.committedAt").exists());

        List<OutboxEvent> events = outboxRepository.findByAggregateId(submission.submissionId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getType()).isEqualTo("submission.committed");
        assertThat(events.get(0).getPayload()).contains(submission.submissionId().toString());
        for (FileRef file : submission.files()) {
            assertThat(events.get(0).getPayload()).contains(file.fileId().toString());
        }
    }

    @Test
    void commitWithMissingFileReturns409AndLeavesSubmissionPending() throws Exception {
        CreateSubmissionResponse submission = createSubmission("owner-1");
        List<FileRef> files = submission.files();
        for (int i = 0; i < 9; i++) {
            FileRef file = files.get(i);
            mockMvc.perform(
                    post("/submissions/" + submission.submissionId() + "/files/" + file.fileId() + "/upload-credentials"));
            fakeUploadTransport.simulateUploadSuccess(
                    objectPathFor(submission.submissionId(), file.fileId()), 50_000_000L, "AAAAAA==");
            mockMvc.perform(post("/submissions/" + submission.submissionId() + "/files/" + file.fileId() + "/complete"));
        }
        // ordinal 9 is left PENDING, never uploaded

        mockMvc
                .perform(post("/submissions/" + submission.submissionId() + "/commit"))
                .andExpect(status().isConflict());

        Submission stored = submissionRepository.findById(submission.submissionId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(SubmissionStatus.PENDING);
        List<SubmissionFile> storedFiles = submissionFileRepository.findBySubmissionId(submission.submissionId());
        assertThat(storedFiles).filteredOn(f -> f.getOrdinal() < 9).allMatch(f -> f.getStatus() == SubmissionFileStatus.UPLOADED);
        assertThat(storedFiles).filteredOn(f -> f.getOrdinal() == 9).allMatch(f -> f.getStatus() == SubmissionFileStatus.PENDING);
        assertThat(outboxRepository.findByAggregateId(submission.submissionId())).isEmpty();
    }

    @Test
    @Transactional
    void commitCatchesSizeMismatchWrittenDirectlyBypassingComplete() throws Exception {
        CreateSubmissionResponse submission = createAndUploadAllFiles();
        // Simulate a hypothetical bug/race: a file row marked UPLOADED with sizeBytes that
        // doesn't match declaredSize, bypassing completeFile's own guard entirely.
        SubmissionFile tampered = submissionFileRepository.findBySubmissionId(submission.submissionId()).get(0);
        tampered.setSizeBytes(tampered.getDeclaredSize() + 1);
        submissionFileRepository.saveAndFlush(tampered);

        mockMvc
                .perform(post("/submissions/" + submission.submissionId() + "/commit"))
                .andExpect(status().isConflict());
    }

    @Test
    void commitReplayIsIdempotent() throws Exception {
        CreateSubmissionResponse submission = createAndUploadAllFiles();

        MvcResult first =
                mockMvc.perform(post("/submissions/" + submission.submissionId() + "/commit")).andExpect(status().isOk()).andReturn();
        MvcResult second =
                mockMvc.perform(post("/submissions/" + submission.submissionId() + "/commit")).andExpect(status().isOk()).andReturn();

        String firstCommittedAt = objectMapper.readTree(first.getResponse().getContentAsString()).get("committedAt").asText();
        String secondCommittedAt = objectMapper.readTree(second.getResponse().getContentAsString()).get("committedAt").asText();
        assertThat(secondCommittedAt).isEqualTo(firstCommittedAt);
        assertThat(outboxRepository.findByAggregateId(submission.submissionId())).hasSize(1);
    }

    @Test
    void concurrentDoubleCommitProducesExactlyOneOutboxRowAndSameCommittedAt() throws Exception {
        CreateSubmissionResponse submission = createAndUploadAllFiles();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures =
                    List.of(
                            executor.submit(
                                    () -> {
                                        startLatch.await();
                                        return mockMvc.perform(post("/submissions/" + submission.submissionId() + "/commit")).andReturn();
                                    }),
                            executor.submit(
                                    () -> {
                                        startLatch.await();
                                        return mockMvc.perform(post("/submissions/" + submission.submissionId() + "/commit")).andReturn();
                                    }));
            startLatch.countDown();

            MvcResult resultA = futures.get(0).get(30, TimeUnit.SECONDS);
            MvcResult resultB = futures.get(1).get(30, TimeUnit.SECONDS);

            assertThat(resultA.getResponse().getStatus()).isEqualTo(200);
            assertThat(resultB.getResponse().getStatus()).isEqualTo(200);

            String committedAtA = objectMapper.readTree(resultA.getResponse().getContentAsString()).get("committedAt").asText();
            String committedAtB = objectMapper.readTree(resultB.getResponse().getContentAsString()).get("committedAt").asText();
            assertThat(committedAtA).isEqualTo(committedAtB);

            assertThat(outboxRepository.findByAggregateId(submission.submissionId())).hasSize(1);
            Submission stored = submissionRepository.findById(submission.submissionId()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(SubmissionStatus.COMMITTED);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void commitUnknownSubmissionReturns404() throws Exception {
        mockMvc.perform(post("/submissions/" + UUID.randomUUID() + "/commit")).andExpect(status().isNotFound());
    }

    @Test
    @Transactional
    void commitExpiredSubmissionReturns409() throws Exception {
        CreateSubmissionResponse submission = createSubmission("owner-1");
        Submission stored = submissionRepository.findById(submission.submissionId()).orElseThrow();
        stored.setStatus(SubmissionStatus.EXPIRED);
        submissionRepository.saveAndFlush(stored);

        mockMvc.perform(post("/submissions/" + submission.submissionId() + "/commit")).andExpect(status().isConflict());
    }

    @Test
    void reUploadAfterFailureThenCommitSucceeds() throws Exception {
        CreateSubmissionResponse submission = createSubmission("owner-1");
        FileRef failing = submission.files().get(0);

        mockMvc.perform(
                post("/submissions/" + submission.submissionId() + "/files/" + failing.fileId() + "/upload-credentials"));
        fakeUploadTransport.simulateUploadSuccess(
                objectPathFor(submission.submissionId(), failing.fileId()), 1L, "AAAAAA==");
        mockMvc
                .perform(post("/submissions/" + submission.submissionId() + "/files/" + failing.fileId() + "/complete"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(
                post("/submissions/" + submission.submissionId() + "/files/" + failing.fileId() + "/upload-credentials"));
        fakeUploadTransport.simulateUploadSuccess(
                objectPathFor(submission.submissionId(), failing.fileId()), 50_000_000L, "AAAAAA==");
        mockMvc
                .perform(post("/submissions/" + submission.submissionId() + "/files/" + failing.fileId() + "/complete"))
                .andExpect(status().isOk());

        for (int i = 1; i < 10; i++) {
            FileRef file = submission.files().get(i);
            mockMvc.perform(
                    post("/submissions/" + submission.submissionId() + "/files/" + file.fileId() + "/upload-credentials"));
            fakeUploadTransport.simulateUploadSuccess(
                    objectPathFor(submission.submissionId(), file.fileId()), 50_000_000L, "AAAAAA==");
            mockMvc.perform(post("/submissions/" + submission.submissionId() + "/files/" + file.fileId() + "/complete"));
        }

        mockMvc.perform(post("/submissions/" + submission.submissionId() + "/commit")).andExpect(status().isOk());
    }
}
