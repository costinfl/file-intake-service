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

class GetSubmissionIntegrationTest extends AbstractIntegrationTest {

    @Test
    void getSubmissionReflectsMixedPerFileStatus() throws Exception {
        CreateSubmissionResponse submission = createSubmission("owner-1");

        FileRef uploadedFile = submission.files().get(0);
        mockMvc.perform(
                post("/submissions/" + submission.submissionId() + "/files/" + uploadedFile.fileId() + "/upload-credentials"));
        fakeUploadTransport.simulateUploadSuccess(
                "staging/" + submission.submissionId() + "/" + uploadedFile.fileId(), 50_000_000L, "AAAAAA==");
        mockMvc.perform(post("/submissions/" + submission.submissionId() + "/files/" + uploadedFile.fileId() + "/complete"));

        FileRef uploadingFile = submission.files().get(1);
        mockMvc.perform(
                post("/submissions/" + submission.submissionId() + "/files/" + uploadingFile.fileId() + "/upload-credentials"));

        mockMvc
                .perform(get("/submissions/" + submission.submissionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.files[0].status").value("UPLOADED"))
                .andExpect(jsonPath("$.files[1].status").value("UPLOADING"))
                .andExpect(jsonPath("$.files[2].status").value("PENDING"));
    }

    @Test
    void getUnknownSubmissionReturns404() throws Exception {
        mockMvc.perform(get("/submissions/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
