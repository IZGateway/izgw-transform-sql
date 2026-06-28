package gov.cdc.izgateway.xform.sql;

import gov.cdc.izgateway.xform.sql.mapping.*;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CSV-backed backend for the one-table denormalized all_vax_event format.
 * Each CSV row carries both patient demographics and one immunization event.
 *
 * Patient search: stream-filter rows by demographics, deduplicate by patient ID.
 * Immunization retrieval: return all rows for the matched patient ID.
 *
 * The patient ID column is derived from the first "identifier" path in the
 * Patient resource mapping.
 */
public class SqlTestBackend implements IQueryBackend {

    private static final Logger log = LoggerFactory.getLogger(SqlTestBackend.class);

    private final List<Map<String, String>> rows;
    private final SqlPatientRowMapper patientMapper;
    private final SqlImmunizationRowMapper immunizationMapper;
    private final TabularFhirConverter converter;
    private final SqlMappingConfiguration mappingConfig;
    private final double matchThreshold;
    private final String patientIdColumn;

    public SqlTestBackend(SqlBackendConfig config, SqlMappingConfiguration mappingConfig, double matchThreshold) {
        this.mappingConfig = mappingConfig;
        this.matchThreshold = matchThreshold;
        this.patientMapper = new SqlPatientRowMapper(mappingConfig);
        this.immunizationMapper = new SqlImmunizationRowMapper(mappingConfig);
        this.converter = new TabularFhirConverter(patientMapper, immunizationMapper);
        this.patientIdColumn = resolvePatientIdColumn(mappingConfig);
        this.rows = loadCsv(config.getDataPath());
        log.info("sql-test fixture loaded: {} rows from {}", rows.size(), config.getDataPath());
    }

    @Override
    public QueryResult query(Patient searchPatient, String lastUpdated) {
        String lastName = searchPatient.hasName() ? searchPatient.getNameFirstRep().getFamily() : "";
        String dob = searchPatient.hasBirthDate() ? searchPatient.getBirthDateElement().asStringValue() : "";
        String lastNameCol = mappingConfig.columnForPath("Patient", "name.family");
        String dobCol = mappingConfig.columnForPath("Patient", "birthDate");

        if (lastNameCol == null || dobCol == null) {
            log.warn("Mapping missing name.family or birthDate column -- cannot search");
            return QueryResult.noMatch();
        }

        final String luCol = lastUpdatedColumn();
        final String luFilter = lastUpdated;

        // Find all rows matching patient demographics, applying lastUpdated filter
        List<Map<String, String>> candidates = rows.stream()
            .filter(row -> matchesPatient(row, lastName, dob, lastNameCol, dobCol))
            .filter(row -> passesLastUpdated(row, luCol, luFilter))
            .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return QueryResult.noMatch();
        }

        // Deduplicate: get distinct patient IDs from matching rows
        List<String> distinctPatientIds = candidates.stream()
            .map(row -> patientIdValue(row))
            .filter(id -> !id.isBlank())
            .distinct()
            .collect(Collectors.toList());

        if (distinctPatientIds.size() > 1) {
            log.info("Ambiguous match: {} distinct patient IDs matched", distinctPatientIds.size());
            OperationOutcome outcome = new OperationOutcome();
            outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(OperationOutcome.IssueType.MULTIPLEMATCHES)
                .getDetails().setText("Multiple patients matched the search criteria");
            return QueryResult.ambiguous(outcome);
        }

        String matchedId = distinctPatientIds.get(0);

        // Build patient from first matching row
        Map<String, String> firstRow = candidates.stream()
            .filter(r -> matchedId.equals(patientIdValue(r)))
            .findFirst().orElse(candidates.get(0));

        Patient patient = patientMapper.map(new HashMap<>(firstRow));
        patient.setId(matchedId);

        // All rows for this patient are immunization events
        List<Map<String, Object>> immRows = rows.stream()
            .filter(row -> matchedId.equals(patientIdValue(row)))
            .filter(row -> passesLastUpdated(row, luCol, luFilter))
            .map(row -> (Map<String, Object>) (Map<?, ?>) row)
            .collect(Collectors.toList());

        return QueryResult.bundle(converter.toBundle(patient, immRows));
    }

    private boolean matchesPatient(Map<String, String> row, String lastName, String dob,
                                   String lastNameCol, String dobCol) {
        String rowLastName = getCaseInsensitive(row, lastNameCol);
        String rowDob = getCaseInsensitive(row, dobCol);
        return rowLastName.equalsIgnoreCase(lastName) && rowDob.equals(dob);
    }

    private boolean passesLastUpdated(Map<String, String> row, String luCol, String lastUpdated) {
        if (luCol == null || lastUpdated == null || lastUpdated.isBlank()) return true;
        String rowVal = getCaseInsensitive(row, luCol);
        if (rowVal.isBlank()) return true;
        String op = lastUpdated.length() > 2 && lastUpdated.substring(0, 2).matches("[a-z]{2}")
            ? lastUpdated.substring(0, 2) : "ge";
        String filterVal = lastUpdated.length() > 2 && op.length() == 2
            ? lastUpdated.substring(2) : lastUpdated;
        int cmp = rowVal.compareTo(filterVal);
        return switch (op) {
            case "ge" -> cmp >= 0;
            case "gt" -> cmp > 0;
            case "le" -> cmp <= 0;
            case "lt" -> cmp < 0;
            default -> cmp >= 0;
        };
    }

    private String patientIdValue(Map<String, String> row) {
        if (patientIdColumn == null) return "";
        return getCaseInsensitive(row, patientIdColumn);
    }

    private String lastUpdatedColumn() {
        ResourceMapping lu = mappingConfig.lastUpdatedColumn("Patient");
        return lu != null ? lu.getColumn() : null;
    }

    private static String getCaseInsensitive(Map<String, String> row, String key) {
        if (key == null) return "";
        String val = row.get(key);
        if (val != null) return val;
        for (Map.Entry<String, String> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue() != null ? e.getValue() : "";
        }
        return "";
    }

    private static String resolvePatientIdColumn(SqlMappingConfiguration config) {
        String col = config.columnForPath("Patient", "identifier");
        if (col != null) return col;
        log.warn("No 'identifier' path in Patient mapping -- patient ID deduplication may fail");
        return null;
    }

    private static List<Map<String, String>> loadCsv(String path) {
        List<Map<String, String>> rows = new ArrayList<>();
        try {
            BufferedReader reader;
            if (path.startsWith("classpath:")) {
                String resource = path.substring("classpath:".length());
                URL url = SqlTestBackend.class.getClassLoader().getResource(resource);
                if (url == null) { log.warn("Classpath resource not found: {}", path); return rows; }
                reader = new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8));
            } else {
                reader = new BufferedReader(new FileReader(path, StandardCharsets.UTF_8));
            }
            try (reader) {
                String headerLine = reader.readLine();
                if (headerLine == null) return rows;
                String[] headers = SqlDevBackend.parseCsvLine(headerLine);
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] values = SqlDevBackend.parseCsvLine(line);
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 0; i < headers.length && i < values.length; i++) {
                        row.put(headers[i], values[i]);
                    }
                    rows.add(row);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load CSV from {}: {}", path, e.getMessage());
        }
        return rows;
    }
}
