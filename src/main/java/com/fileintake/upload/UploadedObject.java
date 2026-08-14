package com.fileintake.upload;

public record UploadedObject(boolean exists, long sizeBytes, String crc32c, long generation) {

    public static UploadedObject notFound() {
        return new UploadedObject(false, 0, null, 0);
    }
}
