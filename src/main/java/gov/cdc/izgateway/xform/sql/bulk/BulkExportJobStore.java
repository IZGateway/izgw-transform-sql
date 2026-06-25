package gov.cdc.izgateway.xform.sql.bulk;

import java.util.UUID;

public interface BulkExportJobStore {
    BulkExportJob create(BulkExportJob job);
    BulkExportJob get(UUID id);
    BulkExportJob update(BulkExportJob job);
    void delete(UUID id);
}
