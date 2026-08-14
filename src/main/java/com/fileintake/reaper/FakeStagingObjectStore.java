package com.fileintake.reaper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "fileintake.upload.transport", havingValue = "fake", matchIfMissing = true)
public class FakeStagingObjectStore implements StagingObjectStore {

    private final List<String> deleted = new CopyOnWriteArrayList<>();

    @Override
    public void delete(String objectPath) {
        deleted.add(objectPath);
    }

    public List<String> deleted() {
        return List.copyOf(deleted);
    }

    public void reset() {
        deleted.clear();
    }
}
