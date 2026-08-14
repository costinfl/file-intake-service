package com.fileintake.submission;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fileintake.submission.dto.CreateSubmissionResponse;
import com.fileintake.submission.dto.FileRef;
import com.fileintake.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompleteFileIntegrationTest extends AbstractIntegrationTest {

    private String objectPathFor(UUID submissionId, UUID fileId) {
        return "staging/" + submissionId + "/" + fileId;
    }

    @Test
    void completeFileHappyPath() throws Exception {
        CreateSubmissionResponse submission = createSubmission("owner-1");
        FileRef file = submission.files().get(0);
        mockMvc.perform(
                post("/submissions/" + submission.submissionId() + "/files/" + file.fileId() + "/upload-credentials"));
        fakeUploadTransport.simulateUploadSuccess(
                objectPathFor(submission.submissionId(), file.fileId()), 50_000_000L, "AAAAAA==");

        mockMvc
                .perform(post("/submissions/" + submission.submissionId() + "/files/" + file.fileId() + "/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.sizeBytes").value(50_000_000))
                .andExpect(jsonPath("$.crc32c").value("AAAAAA=="));

        mockMvc
                .perform(get("/submissions/" + submission.submissionId()))
                .andExpect(jsonPath("$.files[0].status").value("UPLOADED"))
                .andExpect(jsonPath("$.files[0].sizeBytes").value(50_000_000));
    }

    @Test
    void completeFileNeverUploadedReturns422AndMarksFailed() throws Exception {
        CreateSubmissionResponse submission = createSubmission("owner-1");
        FileRef file = submission.files().get(0);
        mockMvc.perform(
                post("/submissions/" + submission.submissionId() + "/files/" + file.fileId() + "/upload-credentials"));

        mockMvc
                .perform(post("/submissions/" + submission.submissionId() + "/files/" + file.fileId() + "/complete"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc
                .perform(get("/submissions/" + submission.submissionId()))
                .andExpect(jsonPath("$.files[0].status").value("FAILED"));
    }

    @Test
    void completeFileSizeMismatchReturns422AndLeavesOtherFilesUntouched() throws Exception {
        CreateSubmissionResponse submission = createSubmission("owner-1");
        FileRef file = submission.files().get(0);
        mockMvc.perform(
                post("/submissions/" + submission.submissionId() + "/files/" + file.fileId() + "/upload-credentials"));
        fakeUploadTransport.simulateUploadSuccess(
                objectPathFor(submission.submissionId(), file.fileId()), 1234L, "AAAAAA==");

        mockMvc
                .perform(post("/submissions/" + submission.submissionId() + "/files/" + file.fileId() + "/complete"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc
                .perform(get("/submissions/" + submission.submissionId()))
                .andExpect(jsonPath("$.files[0].status").value("FAILED"))
                .andExpect(jsonPath("$.files[1].status").value("PENDING"))
                .andExpect(jsonPath("$.files[9].status").value("PENDING"));
    }
}
