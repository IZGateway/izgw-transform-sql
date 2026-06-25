package gov.cdc.izgateway.xform.sql.mapping;

import org.hl7.fhir.r4.model.Immunization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlImmunizationRowMapperTests {

    private SqlImmunizationRowMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        SqlMappingConfiguration config = SqlMappingConfigLoader.load(null);
        mapper = new SqlImmunizationRowMapper(config);
    }

    @Test
    void map_populatesVaccineCodeAndDate() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Vaccine Type (CVX)", "208");
        row.put("Administration Date", "2021-05-01T00:00:00");
        row.put("Lot Number", "LOT001");

        Immunization imm = mapper.map(row);

        assertFalse(imm.getVaccineCode().getCoding().isEmpty());
        assertEquals("208", imm.getVaccineCode().getCodingFirstRep().getCode());
        assertEquals("LOT001", imm.getLotNumber());
    }

    @Test
    void map_defaultsStatusToCompleted() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Vaccine Type (CVX)", "208");

        Immunization imm = mapper.map(row);

        assertEquals(Immunization.ImmunizationStatus.COMPLETED, imm.getStatus());
    }

    @Test
    void map_handlesNullColumns() {
        Map<String, Object> row = new LinkedHashMap<>();
        Immunization imm = mapper.map(row);
        assertNotNull(imm);
    }
}
