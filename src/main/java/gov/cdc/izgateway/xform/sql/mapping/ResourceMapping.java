package gov.cdc.izgateway.xform.sql.mapping;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class ResourceMapping {
    private String column;
    private String resource;
    private String path;
    private String type;
    private String system;
    private String display;

    @JsonProperty("concept_map")
    private List<ConceptMapEntry> conceptMap;

    @JsonProperty("is_last_updated")
    private boolean lastUpdated;

    private String value;

    public String getColumn() { return column; }
    public void setColumn(String column) { this.column = column; }
    public String getResource() { return resource; }
    public void setResource(String resource) { this.resource = resource; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSystem() { return system; }
    public void setSystem(String system) { this.system = system; }
    public String getDisplay() { return display; }
    public void setDisplay(String display) { this.display = display; }
    public List<ConceptMapEntry> getConceptMap() { return conceptMap; }
    public void setConceptMap(List<ConceptMapEntry> conceptMap) { this.conceptMap = conceptMap; }
    public boolean isLastUpdated() { return lastUpdated; }
    public void setLastUpdated(boolean lastUpdated) { this.lastUpdated = lastUpdated; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String mapValue(String raw) {
        if (conceptMap == null || raw == null) return raw;
        return conceptMap.stream()
            .filter(e -> raw.equalsIgnoreCase(e.getFrom()))
            .map(ConceptMapEntry::getTo)
            .findFirst()
            .orElse(raw);
    }
}
