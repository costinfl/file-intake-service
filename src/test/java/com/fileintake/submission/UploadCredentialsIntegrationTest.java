package com.fileintake.submission;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fileintake.submission.dto.CreateSubmissionResponse;
import com.fileintake.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UploadCredentialsIntegrationTest extends AbstractIntegrationTest {

    @Test
    void issueUploadCredentialsHappyPath() throws Exception {
        CreateSubmissionResponse submission = createSubmission("owner-1");
        UUID fileId = submission.files().get(0).fileId();

        mockMvc
                .perform(post("/submissions/" + submission.submissionId() + "/files/" + fileId + "/upload-credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl").value(org.hamcrest.Matchers.containsString(fileId.toString())));
    }

    @Test
    void issueUploadCredentialsForUnknownSubmissionReturns404() throws Exception {
        mockMvc
                .perform(post("/submissions/" + UUID.randomUUID() + "/files/" + UUID.randomUUID() + "/upload-credentials"))
                .andExpect(status().isNotFound());
    }

    @Test
    void issueUploadCredentialsForUnknownFileReturns404() throws Exception {
        CreateSubmissionResponse submission = createSubmission("owner-1");

        mockMvc
                .perform(
                        post("/submissions/" + submission.submissionId() + "/files/" + UUID.randomUUID() + "/upload-credentials"))
                .andExpect(status().isNotFound());
    }

    @Test
    void issueUploadCredentialsForFileFromDifferentSubmissionReturns404() throws Exception {
        CreateSubmissionResponse submissionA = createSubmission("owner-a");
        CreateSubmissionResponse submissionB = createSubmission("owner-b");
        UUID fileFromB = submissionB.files().get(0).fileId();

        mockMvc
                .perform(post("/submissions/" + submissionA.submissionId() + "/files/" + fileFromB + "/upload-credentials"))
                .andExpect(status().isNotFound());
    }
}
