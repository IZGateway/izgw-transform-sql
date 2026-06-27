package gov.cdc.izgateway.xform.sql.mapping;

import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlPatientRowMapperTests {

    private SqlPatientRowMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        SqlMappingConfiguration config = SqlMappingConfigLoader.load(null);
        mapper = new SqlPatientRowMapper(config);
    }

    @Test
    void map_populatesNameAndDob() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Name - Last", "Smith");
        row.put("Name - First", "John");
        row.put("Date of Birth", "1985-03-15");
        row.put("Gender", "M");

        Patient patient = mapper.map(row);

        assertEquals("Smith", patient.getNameFirstRep().getFamily());
        assertFalse(patient.getNameFirstRep().getGiven().isEmpty());
        assertEquals("1985-03-15", patient.getBirthDateElement().getValueAsString());
        assertEquals("male", patient.getGender().toCode());
    }

    @Test
    void map_handlesNullColumn() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Name - Last", "Doe");
        row.put("Date of Birth", "1990-01-01");

        Patient patient = mapper.map(row);

        assertEquals("Doe", patient.getNameFirstRep().getFamily());
        assertNotNull(patient);
    }

    @Test
    void map_populatesAddress() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Address: Street", "123 Main St");
        row.put("Address: City", "Springfield");
        row.put("Address: State", "IL");
        row.put("Address: Zip", "62701");

        Patient patient = mapper.map(row);

        assertEquals("Springfield", patient.getAddressFirstRep().getCity());
        assertEquals("IL", patient.getAddressFirstRep().getState());
    }
}
