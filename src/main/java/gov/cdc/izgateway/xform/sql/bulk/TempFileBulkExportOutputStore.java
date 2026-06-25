package gov.cdc.izgateway.xform.sql.bulk;

import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;

/**
 * V1 temp-file output store. Files are local to this instance and lost
 * on restart. Single-instance deployment only.
 */
@Component
public class TempFileBulkExportOutputStore implements BulkExportOutputStore {

    private Path filePath(UUID jobId, int fileIndex) {
        return Path.of(System.getProperty("java.io.tmpdir"),
            "izg-bulk-" + jobId + "-" + fileIndex + ".ndjson");
    }

    @Override
    public void write(UUID jobId, int fileIndex, InputStream data) throws Exception {
        Files.copy(data, filePath(jobId, fileIndex), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void stream(UUID jobId, int fileIndex, OutputStream out) throws Exception {
        Files.copy(filePath(jobId, fileIndex), out);
    }

    @Override
    public void delete(UUID jobId) throws Exception {
        for (int i = 0; ; i++) {
            Path p = filePath(jobId, i);
            if (!Files.deleteIfExists(p)) break;
        }
    }
}
