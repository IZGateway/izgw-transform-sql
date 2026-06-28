package gov.cdc.izgateway.xform.sql;

import gov.cdc.izgateway.xform.sql.mapping.ResourceMapping;
import gov.cdc.izgateway.xform.sql.mapping.SqlMappingConfiguration;
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

class SqlImmunizationRetrievalServiceTests {

    private NamedParameterJdbcTemplate jdbc;
    private SqlBackendProperties props;
    private SqlMappingConfiguration config;
    private SqlImmunizationRetrievalService service;

    @BeforeEach
    void setUp() {
        jdbc = Mockito.mock(NamedParameterJdbcTemplate.class);
        props = new SqlBackendProperties();
        props.getTables().setPatientIdColumn("IIS Patient ID");

        config = buildConfig();
        service = new SqlImmunizationRetrievalService(jdbc, props, config);
    }

    @Test
    void retrieve_returnsRows() {
        Map<String, Object> row = Map.of("IIS Patient ID", "12345", "CVX Code", "20");
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
            .thenReturn(List.of(row));

        List<Map<String, Object>> result = service.retrieve("12345", null);

        assertEquals(1, result.size());
        assertEquals("12345", result.get(0).get("IIS Patient ID"));
    }

    @Test
    void retrieve_emptyResult_returnsEmptyList() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
            .thenReturn(List.of());

        List<Map<String, Object>> result = service.retrieve("99999", null);

        assertTrue(result.isEmpty());
    }

    @Test
    void retrieve_sqlContainsPatientIdColumn() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
            .thenReturn(List.of());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        service.retrieve("12345", null);

        verify(jdbc).queryForList(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("IIS Patient ID"), "SQL should contain patient ID column");
        assertTrue(sql.contains(":patientId"), "SQL should use named parameter :patientId");
    }

    @Test
    void retrieve_withLastUpdated_appendsWhereClause() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
            .thenReturn(List.of());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        service.retrieve("12345", "ge2021-01-01");

        verify(jdbc).queryForList(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains(">="), "SQL should contain >= for ge prefix");
        assertTrue(sql.contains(":lastUpdated"), "SQL should bind lastUpdated parameter");
        assertTrue(sql.contains("IMMU_STAMP"), "SQL should include immunization last-updated column");
    }

    @Test
    void retrieve_withoutLastUpdated_noTimestampClause() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
            .thenReturn(List.of());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        service.retrieve("12345", null);

        verify(jdbc).queryForList(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        assertFalse(sqlCaptor.getValue().contains(":lastUpdated"));
    }

    @Test
    void retrieve_gtOperator_usesStrictGreaterThan() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
            .thenReturn(List.of());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        service.retrieve("12345", "gt2021-06-01");

        verify(jdbc).queryForList(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        assertTrue(sqlCaptor.getValue().contains(" > "));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static SqlMappingConfiguration buildConfig() {
        SqlMappingConfiguration cfg = new SqlMappingConfiguration();

        ResourceMapping lu = new ResourceMapping();
        lu.setResource("Immunization");
        lu.setColumn("IMMU_STAMP");
        lu.setPath("meta.lastUpdated");
        lu.setLastUpdated(true);

        cfg.setMappings(List.of(lu));
        return cfg;
    }
}
