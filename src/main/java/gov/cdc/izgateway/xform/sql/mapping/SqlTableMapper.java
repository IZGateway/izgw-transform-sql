package gov.cdc.izgateway.xform.sql.mapping;

import org.hl7.fhir.r4.model.*;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Abstract base class owning all column-to-FHIR conversion logic.
 * Subclasses implement {@link #newResource()} and {@link #applyField} for
 * resource-type-specific path assignments.
 */
public abstract class SqlTableMapper<T extends Resource> {

    protected static final Logger log = LoggerFactory.getLogger(SqlTableMapper.class);

    protected final SqlMappingConfiguration config;
    protected final String resourceType;

    protected SqlTableMapper(SqlMappingConfiguration config, String resourceType) {
        this.config = config;
        this.resourceType = resourceType;
    }

    protected abstract T newResource();

    /**
     * Apply a single mapped value to the target resource at the declared path.
     * Subclasses handle the resource-specific paths.
     */
    protected abstract void applyField(T resource, ResourceMapping mapping, String value);

    public T map(Map<String, Object> row) {
        T resource = newResource();
        List<ResourceMapping> mappings = config.forResource(resourceType);
        for (ResourceMapping m : mappings) {
            String colKey = m.getColumn().toLowerCase();
            Object raw = row.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().equals(colKey))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
            if (raw == null) continue;
            String value = m.mapValue(raw.toString().trim());
            if (value == null || value.isEmpty()) continue;
            try {
                applyField(resource, m, value);
            } catch (Exception e) {
                log.debug("Could not apply column {} to {}.{}: {}", m.getColumn(), resourceType, m.getPath(), e.getMessage());
            }
        }
        return resource;
    }

    // ── Type converters ──────────────────────────────────────────────────────

    protected static DateType toDate(String value) {
        return new DateType(value);
    }

    protected static DateTimeType toDateTime(String value) {
        return new DateTimeType(value);
    }

    protected static InstantType toInstant(String value) {
        return new InstantType(new DateTimeType(value).getValue());
    }

    protected static IntegerType toInteger(String value) {
        return new IntegerType(Integer.parseInt(value));
    }

    protected static DecimalType toDecimal(String value) {
        return new DecimalType(new BigDecimal(value));
    }

    protected static BooleanType toBoolean(String value) {
        return new BooleanType(Boolean.parseBoolean(value));
    }

    protected static CodeType toCode(String value) {
        return new CodeType(value);
    }

    protected static Coding toCoding(String value, String system) {
        Coding c = new Coding();
        c.setSystem(system);
        c.setCode(value);
        return c;
    }

    protected static CodeableConcept toCodeableConcept(String value, String system, String display) {
        CodeableConcept cc = new CodeableConcept();
        Coding c = cc.addCoding();
        c.setSystem(system);
        c.setCode(value);
        if (display != null) c.setDisplay(display);
        return cc;
    }

    protected static Date parseDate(String value) {
        try {
            return new DateType(value).getValue();
        } catch (Exception e) {
            return null;
        }
    }
}
