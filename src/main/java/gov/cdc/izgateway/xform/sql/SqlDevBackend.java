package gov.cdc.izgateway.xform.sql;

import gov.cdc.izgateway.xform.sql.mapping.*;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CSV-backed dev fixture for the sql-dev backend. No JDBC driver required.
 * Patient search is a Java stream filter; immunization retrieval is a
 * second filter on patient ID.
 */
public class SqlDevBackend {

    private static final Logger log = LoggerFactory.getLogger(SqlDevBackend.class);

    private final List<Map<String, String>> patients;
    private final List<Map<String, String>> immunizations;
    private final SqlPatientRowMapper patientMapper;
    private final SqlImmunizationRowMapper immunizationMapper;
    private final TabularFhirConverter converter;
    private final double matchThreshold;

    public SqlDevBackend(SqlBackendProperties props, SqlMappingConfiguration config) {
        this.matchThreshold = props.getMatchingThreshold();
        this.patientMapper = new SqlPatientRowMapper(config);
        this.immunizationMapper = new SqlImmunizationRowMapper(config);
        this.converter = new TabularFhirConverter(patientMapper, immunizationMapper);
        this.patients = loadCsv(props.getDev().getPatientsPath(), "patients");
        this.immunizations = loadCsv(props.getDev().getImmunizationsPath(), "immunizations");
        log.info("sql-dev fixture loaded: {} patients, {} immunizations",
            patients.size(), immunizations.size());
    }

    public Bundle query(Patient searchPatient, String lastUpdated) {
        String lastName = searchPatient.getNameFirstRep().getFamily();
        String dob = searchPatient.getBirthDateElement().asStringValue();

        List<Map<String, String>> candidates = patients.stream()
            .filter(row -> matches(row, lastName, dob))
            .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            Bundle empty = new Bundle();
            empty.setType(Bundle.BundleType.SEARCHSET);
            empty.setTotal(0);
            return empty;
        }

        Map<String, String> matched = candidates.get(0);
        String rawId = matched.get("IIS Patient ID");
        final String patientId = rawId != null ? rawId : matched.getOrDefault("iis patient id", "");

        Patient patient = patientMapper.map(new HashMap<>(matched));
        patient.setId(patientId);

        List<Map<String, Object>> immRows = immunizations.stream()
            .filter(row -> patientId.equals(row.get("IIS Patient ID")))
            .map(row -> (Map<String, Object>) (Map<?, ?>) row)
            .collect(Collectors.toList());

        return converter.toBundle(patient, immRows);
    }

    private boolean matches(Map<String, String> row, String lastName, String dob) {
        String rowLastName = row.getOrDefault("Name - Last", "");
        String rowDob = row.getOrDefault("Date of Birth", "");
        return rowLastName.equalsIgnoreCase(lastName) && rowDob.equals(dob);
    }

    private static List<Map<String, String>> loadCsv(String path, String label) {
        List<Map<String, String>> rows = new ArrayList<>();
        try {
            Resource resource = new DefaultResourceLoader().getResource(path);
            if (!resource.exists()) {
                log.warn("sql-dev {} file not found at: {}", label, path);
                return rows;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String headerLine = reader.readLine();
                if (headerLine == null) return rows;
                String[] headers = parseCsvLine(headerLine);
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] values = parseCsvLine(line);
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 0; i < headers.length && i < values.length; i++) {
                        row.put(headers[i], values[i]);
                    }
                    rows.add(row);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load sql-dev {} from {}: {}", label, path, e.getMessage());
        }
        return rows;
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }
}
