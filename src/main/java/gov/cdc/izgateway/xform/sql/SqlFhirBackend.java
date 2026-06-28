package gov.cdc.izgateway.xform.sql;

import gov.cdc.izgateway.xform.sql.mapping.SqlImmunizationRowMapper;
import gov.cdc.izgateway.xform.sql.mapping.SqlMappingConfiguration;
import gov.cdc.izgateway.xform.sql.mapping.SqlPatientRowMapper;
import gov.cdc.izgateway.xform.sql.mapping.TabularFhirConverter;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full JDBC-backed single-patient query pipeline:
 * SqlPatientSearchService -> SqlImmunizationRetrievalService -> TabularFhirConverter
 */
public class SqlFhirBackend implements IQueryBackend {

    private static final Logger log = LoggerFactory.getLogger(SqlFhirBackend.class);

    private final SqlPatientSearchService patientSearch;
    private final SqlImmunizationRetrievalService immunizationRetrieval;
    private final SqlPatientRowMapper patientMapper;
    private final TabularFhirConverter converter;
    private final SqlMappingConfiguration config;

    public SqlFhirBackend(SqlPatientSearchService patientSearch,
                          SqlImmunizationRetrievalService immunizationRetrieval,
                          SqlPatientRowMapper patientMapper,
                          SqlImmunizationRowMapper immunizationMapper,
                          SqlMappingConfiguration config) {
        this.patientSearch = patientSearch;
        this.immunizationRetrieval = immunizationRetrieval;
        this.patientMapper = patientMapper;
        this.converter = new TabularFhirConverter(patientMapper, immunizationMapper);
        this.config = config;
    }

    @Override
    public QueryResult query(Patient searchPatient, String lastUpdated) {
        PatientSearchResult result = patientSearch.findMatch(searchPatient, lastUpdated);

        if (result.isAmbiguous()) {
            log.info("Ambiguous patient match -- returning OperationOutcome");
            return QueryResult.ambiguous(buildAmbiguousOutcome());
        }

        if (!result.isMatch()) {
            return QueryResult.noMatch();
        }

        Map<String, Object> matchedRow = result.getMatchedRow();
        Patient patient = patientMapper.map(matchedRow);

        String patientId = extractPatientId(matchedRow);
        List<Map<String, Object>> immRows = immunizationRetrieval.retrieve(patientId, lastUpdated);

        return QueryResult.bundle(converter.toBundle(patient, immRows));
    }

    private String extractPatientId(Map<String, Object> row) {
        String idCol = config.columnForPath("Patient", "identifier");
        if (idCol == null) return "";
        Object val = row.entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase(idCol))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
        return val != null ? val.toString() : "";
    }

    private OperationOutcome buildAmbiguousOutcome() {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(OperationOutcome.IssueType.MULTIPLEMATCHES)
            .getDetails().setText("Multiple patients matched the search criteria");
        return outcome;
    }
}
