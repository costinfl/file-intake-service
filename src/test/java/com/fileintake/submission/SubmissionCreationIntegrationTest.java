package com.fileintake.submission;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fileintake.support.AbstractIntegrationTest;
import com.fileintake.support.TestRequests;
import com.fileintake.submission.dto.CreateSubmissionRequest;
import com.fileintake.submission.dto.DeclaredFile;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class SubmissionCreationIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createSubmissionReturnsTenPendingFiles() throws Exception {
        CreateSubmissionRequest request = TestRequests.tenFileRequest("owner-1");

        mockMvc
                .perform(
                        post("/submissions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.submissionId").exists())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.files", hasSize(10)));
    }

    @Test
    void createSubmissionWithWrongFileCountReturns400() throws Exception {
        List<DeclaredFile> nineFiles = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nineFiles.add(new DeclaredFile("file-" + i + ".bin", 50_000_000L));
        }
        CreateSubmissionRequest request = new CreateSubmissionRequest("owner-1", nineFiles);

        mockMvc
                .perform(
                        post("/submissions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
