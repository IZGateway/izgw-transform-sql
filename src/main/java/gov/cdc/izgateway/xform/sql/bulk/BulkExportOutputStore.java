package gov.cdc.izgateway.xform.sql.bulk;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public interface BulkExportOutputStore {
    void write(UUID jobId, int fileIndex, InputStream data) throws Exception;
    void stream(UUID jobId, int fileIndex, OutputStream out) throws Exception;
    void delete(UUID jobId) throws Exception;
}
