package gov.cdc.izgateway.xform.sql;

import gov.cdc.izgateway.xform.sql.mapping.ResourceMapping;
import gov.cdc.izgateway.xform.sql.mapping.SqlMappingConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Retrieves immunization rows for a matched patient from SQL.
 * All values are bound as named parameters. Column names come from sql-mapping.yml.
 */
public class SqlImmunizationRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(SqlImmunizationRetrievalService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final SqlBackendProperties props;
    private final SqlMappingConfiguration config;

    public SqlImmunizationRetrievalService(NamedParameterJdbcTemplate jdbc,
                                           SqlBackendProperties props,
                                           SqlMappingConfiguration config) {
        this.jdbc = jdbc;
        this.props = props;
        this.config = config;
    }

    /**
     * Returns all immunization rows for the given patient ID, optionally filtered by _lastUpdated.
     *
     * @param patientId   value of the patient linkage column in the immunization table
     * @param lastUpdated optional _lastUpdated prefix string, e.g. "ge2020-01-01"
     * @return list of column-name to value maps, preserving column names from the result set
     */
    public List<Map<String, Object>> retrieve(String patientId, String lastUpdated) {
        String table = props.getTables().getImmunization();
        String patientIdCol = props.getTables().getPatientIdColumn();

        if (patientIdCol == null || patientIdCol.isBlank()) {
            patientIdCol = config.columnForPath("Patient", "identifier");
        }
        if (patientIdCol == null) {
            log.warn("No patient ID column configured or mapped -- cannot retrieve immunizations");
            return Collections.emptyList();
        }

        StringBuilder sql = new StringBuilder("SELECT * FROM ");
        sql.append(table);
        sql.append(" WHERE ").append(patientIdCol).append(" = :patientId");

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("patientId", patientId);

        appendLastUpdated(sql, params, lastUpdated);

        String occCol = config.columnForPath("Immunization", "occurrenceDateTime");
        if (occCol != null) {
            sql.append(" ORDER BY ").append(occCol).append(" DESC");
        }

        log.debug("Immunization retrieval SQL: {}", sql);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params);
        log.debug("Immunization retrieval returned {} rows", rows.size());
        return rows;
    }

    private void appendLastUpdated(StringBuilder sql, MapSqlParameterSource params, String lastUpdated) {
        if (lastUpdated == null || lastUpdated.isBlank()) return;
        ResourceMapping lu = config.lastUpdatedColumn("Immunization");
        if (lu == null) {
            log.debug("No is_last_updated column for Immunization -- ignoring _lastUpdated");
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
}
