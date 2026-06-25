package gov.cdc.izgateway.xform.sql.bulk;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BulkExportJob {

    public enum Status { PENDING, RUNNING, COMPLETE, FAILED }

    private final UUID id = UUID.randomUUID();
    private volatile Status status = Status.PENDING;
    private final Instant kickoffTime = Instant.now();
    private Instant transactionTime;
    private String sinceParam;
    private String typeFilter;
    private String typeParam;
    private final List<OutputFile> outputFiles = new ArrayList<>();
    private String errorMessage;

    public UUID getId() { return id; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getKickoffTime() { return kickoffTime; }
    public Instant getTransactionTime() { return transactionTime; }
    public void setTransactionTime(Instant transactionTime) { this.transactionTime = transactionTime; }
    public String getSinceParam() { return sinceParam; }
    public void setSinceParam(String sinceParam) { this.sinceParam = sinceParam; }
    public String getTypeFilter() { return typeFilter; }
    public void setTypeFilter(String typeFilter) { this.typeFilter = typeFilter; }
    public String getTypeParam() { return typeParam; }
    public void setTypeParam(String typeParam) { this.typeParam = typeParam; }
    public List<OutputFile> getOutputFiles() { return outputFiles; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public static class OutputFile {
        private final String type;
        private final String url;
        private final int count;
        public OutputFile(String type, String url, int count) {
            this.type = type; this.url = url; this.count = count;
        }
        public String getType() { return type; }
        public String getUrl() { return url; }
        public int getCount() { return count; }
    }
}
