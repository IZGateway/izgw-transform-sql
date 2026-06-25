package gov.cdc.izgateway.xform.sql.bulk;

import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V1 in-memory job store. Job state is lost on restart and not shared
 * across instances. Single-instance deployment only.
 */
@Component
public class InMemoryBulkExportJobStore implements BulkExportJobStore {

    private final ConcurrentHashMap<UUID, BulkExportJob> jobs = new ConcurrentHashMap<>();

    @Override
    public BulkExportJob create(BulkExportJob job) {
        jobs.put(job.getId(), job);
        return job;
    }

    @Override
    public BulkExportJob get(UUID id) {
        return jobs.get(id);
    }

    @Override
    public BulkExportJob update(BulkExportJob job) {
        jobs.put(job.getId(), job);
        return job;
    }

    @Override
    public void delete(UUID id) {
        jobs.remove(id);
    }
}
