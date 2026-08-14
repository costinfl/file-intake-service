package com.fileintake.support;

import com.fileintake.submission.dto.CreateSubmissionRequest;
import com.fileintake.submission.dto.DeclaredFile;
import java.util.ArrayList;
import java.util.List;

public final class TestRequests {

    private TestRequests() {}

    public static CreateSubmissionRequest tenFileRequest(String ownerId) {
        List<DeclaredFile> files = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            files.add(new DeclaredFile("file-" + i + ".bin", 50_000_000L));
        }
        return new CreateSubmissionRequest(ownerId, files);
    }
}
