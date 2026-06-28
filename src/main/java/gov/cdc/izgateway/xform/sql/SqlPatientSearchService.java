package gov.cdc.izgateway.xform.sql;

import gov.cdc.izgateway.xform.sql.mapping.ResourceMapping;
import gov.cdc.izgateway.xform.sql.mapping.SqlMappingConfiguration;
import gov.cdc.izgateway.xform.sql.mapping.SqlPatientRowMapper;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Executes a broad ANSI SQL candidate query for patient matching.
 * All parameter values are bound via named parameters -- no string concatenation
 * of user-supplied values. Column names come from the trusted sql-mapping.yml.
 */
public class SqlPatientSearchService {

    private static final Logger log = LoggerFactory.getLogger(SqlPatientSearchService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final SqlBackendProperties props;
    private final SqlMappingConfiguration config;
    private final SqlPatientRowMapper patientMapper;
    private final PatientMatchScorer scorer;

    public SqlPatientSearchService(NamedParameterJdbcTemplate jdbc,
                                   SqlBackendProperties props,
                                   SqlMappingConfiguration config,
                                   SqlPatientRowMapper patientMapper,
                                   PatientMatchScorer scorer) {
        this.jdbc = jdbc;
        this.props = props;
        this.config = config;
        this.patientMapper = patientMapper;
        this.scorer = scorer;
    }

    /**
     * Finds a single matching patient or signals no-match / ambiguous-match.
     *
     * @param searchPatient FHIR Patient built from request parameters
     * @param lastUpdated   optional _lastUpdated prefix string, e.g. "ge2020-01-01"
     * @return result indicating match, no-match, or ambiguous
     */
    public PatientSearchResult findMatch(Patient searchPatient, String lastUpdated) {
        String table = props.getTables().getPatient();
        String lastNameCol = config.columnForPath("Patient", "name.family");
        String dobCol = config.columnForPath("Patient", "birthDate");
        String genderCol = config.columnForPath("Patient", "gender");

        if (lastNameCol == null || dobCol == null) {
            log.warn("Patient mapping is missing name.family or birthDate -- cannot query");
            return PatientSearchResult.noMatch();
        }

        String patientColumns = buildPatientColumnList();
        StringBuilder sql = new StringBuilder("SELECT DISTINCT ");
        sql.append(patientColumns).append(" FROM ");
        sql.append(table);
        sql.append(" WHERE ((");
        sql.append(lastNameCol).append(" = :lastName AND ");
        sql.append(dobCol).append(" = :dob)");

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("lastName", lastName(searchPatient));
        params.addValue("dob", dob(searchPatient));

        if (genderCol != null && searchPatient.hasGender()) {
            sql.append(" OR (");
            sql.append(dobCol).append(" = :dob AND ");
            sql.append(genderCol).append(" = :gender)");
            params.addValue("gender", searchPatient.getGender().toCode());
        }

        sql.append(")");
        appendLastUpdated(sql, params, "Patient", lastUpdated);

        log.debug("Patient search SQL: {}", sql);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params);
        log.debug("Patient search returned {} rows", rows.size());

        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Patient candidate = patientMapper.map(row);
            double score = scorer.score(searchPatient, candidate);
            log.debug("Candidate score: {}", score);
            if (score >= props.getMatchingThreshold()) {
                matches.add(row);
            }
        }

        if (matches.size() == 1) {
            return PatientSearchResult.match(matches.get(0));
        }
        if (matches.size() > 1) {
            log.info("Ambiguous match: {} candidates above threshold {}", matches.size(), props.getMatchingThreshold());
            return PatientSearchResult.ambiguous();
        }
        return PatientSearchResult.noMatch();
    }

    private void appendLastUpdated(StringBuilder sql, MapSqlParameterSource params,
                                   String resourceType, String lastUpdated) {
        if (lastUpdated == null || lastUpdated.isBlank()) return;
        ResourceMapping lu = config.lastUpdatedColumn(resourceType);
        if (lu == null) {
            log.debug("No is_last_updated column configured for {} -- ignoring _lastUpdated", resourceType);
            return;
        }
        String col = lu.getColumn();
        String sqlOp;
        String dateVal;
        if (lastUpdated.length() > 2 && lastUpdated.substring(0, 2).matches("[a-z]{2}")) {
            sqlOp = switch (lastUpdated.substring(0, 2)) {
                case "ge" -> ">=";
                case "gt" -> ">";
                case "le" -> "<=";
                case "lt" -> "<";
                default -> ">=";
            };
            dateVal = lastUpdated.substring(2);
        } else {
            sqlOp = ">=";
            dateVal = lastUpdated;
        }
        sql.append(" AND ").append(col).append(" ").append(sqlOp).append(" :lastUpdated");
        params.addValue("lastUpdated", dateVal);
    }

    private String buildPatientColumnList() {
        List<String> cols = config.forResource("Patient").stream()
            .map(m -> m.getColumn())
            .distinct()
            .collect(java.util.stream.Collectors.toList());
        return cols.isEmpty() ? "*" : String.join(", ", cols);
    }

    private static String lastName(Patient p) {
        return p.hasName() && p.getNameFirstRep().hasFamily()
            ? p.getNameFirstRep().getFamily() : "";
    }

    private static String dob(Patient p) {
        return p.hasBirthDate() ? p.getBirthDateElement().asStringValue() : "";
    }
}
