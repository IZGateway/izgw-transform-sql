package gov.cdc.izgateway.xform.sql.mapping;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ConceptMapEntry {
    @JsonProperty("from")
    private String from;
    @JsonProperty("to")
    private String to;

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
}
