package gov.cdc.izgateway.xform.sql;

import gov.cdc.izgateway.xform.sql.mapping.ResourceMapping;
import gov.cdc.izgateway.xform.sql.mapping.SqlMappingConfiguration;
import gov.cdc.izgateway.xform.sql.mapping.SqlPatientRowMapper;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SqlPatientSearchServiceTests {

    private NamedParameterJdbcTemplate jdbc;
    private SqlBackendProperties props;
    private SqlMappingConfiguration config;
    private SqlPatientRowMapper patientMapper;
    private PatientMatchScorer scorer;
    private SqlPatientSearchService service;

    @BeforeEach
    void setUp() {
        jdbc = Mockito.mock(NamedParameterJdbcTemplate.class);
        props = new SqlBackendProperties();
        props.setMatchingThreshold(0.95);

        config = buildConfig();
        patientMapper = new SqlPatientRowMapper(config);

        // Scorer that always returns 1.0 for non-empty name match
        scorer = (search, candidate) -> {
            String sf = search.hasName() ? search.getNameFirstRep().getFamily() : "";
            String cf = candidate.hasName() ? candidate.getNameFirstRep().getFamily() : "";
            return sf.equalsIgnoreCase(cf) ? 1.0 : 0.0;
        };

        service = new SqlPatientSearchService(jdbc, props, config, patientMapper, scorer);
    }

    @Test
    void findMatch_singleResult_returnsMatch() {
        Map<String, Object> row = Map.of(
            "Name - Last", "Smith",
            "Date of Birth", "1985-03-15"
        );
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
            .thenReturn(List.of(row));

        Patient search = searchPatient("Smith", "1985-03-15");
        PatientSearchResult result = service.findMatch(search, null);

        assertTrue(result.isMatch());
        assertEquals(row, result.getMatchedRow());
    }

    @Test
    void findMatch_noResults_returnsNoMatch() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
            .thenReturn(List.of());

        PatientSearchResult result = service.findMatch(searchPatient("Jones", "1990-01-01"), null);

        assertFalse(result.isMatch());
        assertFalse(result.isAmbiguous());
    }

    @Test
    void findMatch_twoAboveThreshold_returnsAmbiguous() {
        Map<String, Object> row1 = Map.of("Name - Last", "Smith", "Date of Birth", "1985-03-15");
        Map<String, Object> row2 = Map.of("Name - Last", "Smith", "Date of Birth", "1985-03-15");
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
            .thenReturn(List.of(row1, row2));

        PatientSearchResult result = service.findMatch(searchPatient("Smith", "1985-03-15"), null);

        assertTrue(result.isAmbiguous());
    }

    @Test
    void findMatch_belowThreshold_returnsNoMatch() {
        // Scorer returns 0.0 for non-matching candidate
        Map<String, Object> row = Map.of("Name - Last", "Smyth", "Date of Birth", "1985-03-15");
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
            .thenReturn(List.of(row));

        PatientSearchResult result = service.findMatch(searchPatient("Smith", "1985-03-15"), null);

        assertFalse(result.isMatch());
        assertFalse(result.isAmbiguous());
    }

    @Test
    void findMatch_sqlContainsLastNameAndDobColumns() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
            .thenReturn(List.of());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        service.findMatch(searchPatient("Smith", "1985-03-15"), null);

        verify(jdbc).queryForList(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("Name - Last"), "SQL should contain last-name column");
        assertTrue(sql.contains("Date of Birth"), "SQL should contain DOB column");
        assertTrue(sql.contains(":lastName"), "SQL should use named parameter :lastName");
        assertTrue(sql.contains(":dob"), "SQL should use named parameter :dob");
    }

    @Test
    void findMatch_withLastUpdated_appendsWhereClause() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
            .thenReturn(List.of());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        service.findMatch(searchPatient("Smith", "1985-03-15"), "ge2020-01-01");

        verify(jdbc).queryForList(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains(">="), "SQL should contain >= for ge prefix");
        assertTrue(sql.contains(":lastUpdated"), "SQL should use named parameter :lastUpdated");
    }

    @Test
    void findMatch_leLastUpdated_usesLessOrEqualOperator() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
            .thenReturn(List.of());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        service.findMatch(searchPatient("Smith", "1985-03-15"), "le2022-12-31");

        verify(jdbc).queryForList(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        assertTrue(sqlCaptor.getValue().contains("<="));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Patient searchPatient(String family, String birthdate) {
        Patient p = new Patient();
        p.getNameFirstRep().setFamily(family);
        p.setBirthDateElement(new DateType(birthdate));
        return p;
    }

    private static SqlMappingConfiguration buildConfig() {
        SqlMappingConfiguration cfg = new SqlMappingConfiguration();

        ResourceMapping lastName = new ResourceMapping();
        lastName.setResource("Patient");
        lastName.setColumn("Name - Last");
        lastName.setPath("name.family");

        ResourceMapping dob = new ResourceMapping();
        dob.setResource("Patient");
        dob.setColumn("Date of Birth");
        dob.setPath("birthDate");

        ResourceMapping luPatient = new ResourceMapping();
        luPatient.setResource("Patient");
        luPatient.setColumn("INSERT_STAMP");
        luPatient.setPath("meta.lastUpdated");
        luPatient.setLastUpdated(true);

        cfg.setMappings(List.of(lastName, dob, luPatient));
        return cfg;
    }
}
