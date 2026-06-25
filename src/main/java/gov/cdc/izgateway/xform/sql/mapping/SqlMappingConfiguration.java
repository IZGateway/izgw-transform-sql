package gov.cdc.izgateway.xform.sql.mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SqlMappingConfiguration {
    private List<ResourceMapping> mappings = new ArrayList<>();

    public List<ResourceMapping> getMappings() { return mappings; }
    public void setMappings(List<ResourceMapping> mappings) { this.mappings = mappings; }

    public List<ResourceMapping> forResource(String resourceType) {
        return mappings.stream()
            .filter(m -> resourceType.equalsIgnoreCase(m.getResource()))
            .collect(Collectors.toList());
    }

    public Map<String, ResourceMapping> byColumn(String resourceType) {
        return forResource(resourceType).stream()
            .collect(Collectors.toMap(
                m -> m.getColumn().toLowerCase(),
                m -> m,
                (a, b) -> a));
    }

    public ResourceMapping lastUpdatedColumn(String resourceType) {
        return forResource(resourceType).stream()
            .filter(ResourceMapping::isLastUpdated)
            .findFirst()
            .orElse(null);
    }
}
