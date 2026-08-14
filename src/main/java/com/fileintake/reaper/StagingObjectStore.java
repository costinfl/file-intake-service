package com.fileintake.reaper;

/**
 * Deletes staged objects. Deliberately separate from {@link com.fileintake.upload.UploadTransport}
 * — deleting abandoned staging objects is an operational concern the reaper owns, not part of the
 * upload-signing contract that {@code UploadTransport} and its test double are built around.
 */
public interface StagingObjectStore {

    void delete(String objectPath);
}
