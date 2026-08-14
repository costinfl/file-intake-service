package com.fileintake.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileintake.submission.dto.CreateSubmissionRequest;
import com.fileintake.submission.dto.CreateSubmissionResponse;
import com.fileintake.upload.FakeUploadTransport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    // Singleton container pattern: started once for the whole JVM/test run and deliberately
    // never stopped here. Using @Testcontainers + @Container ties the container's lifecycle to
    // each test class's afterAll, which stops the one shared static instance after the first
    // class finishes and breaks every subsequent class in the same run.
    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("mirror.gcr.io/library/postgres:16-alpine")
                    .asCompatibleSubstituteFor("postgres");

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected FakeUploadTransport fakeUploadTransport;

    @BeforeEach
    void resetFakeUploadTransport() {
        fakeUploadTransport.reset();
    }

    protected CreateSubmissionResponse createSubmission(String ownerId) throws Exception {
        CreateSubmissionRequest request = TestRequests.tenFileRequest(ownerId);
        String body =
                mockMvc
                        .perform(
                                post("/submissions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(request)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return objectMapper.readValue(body, CreateSubmissionResponse.class);
    }
}
