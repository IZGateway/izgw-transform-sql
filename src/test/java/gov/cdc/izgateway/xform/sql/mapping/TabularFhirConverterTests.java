package gov.cdc.izgateway.xform.sql.mapping;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TabularFhirConverterTests {

    private TabularFhirConverter converter;

    @BeforeEach
    void setUp() throws Exception {
        SqlMappingConfiguration config = SqlMappingConfigLoader.load(null);
        SqlPatientRowMapper patientMapper = new SqlPatientRowMapper(config);
        SqlImmunizationRowMapper immunizationMapper = new SqlImmunizationRowMapper(config);
        converter = new TabularFhirConverter(patientMapper, immunizationMapper);
    }

    @Test
    void toBundle_containsPatientAndImmunizations() {
        Patient patient = new Patient();
        patient.setId("1001");
        patient.getNameFirstRep().setFamily("Smith");

        Map<String, Object> immRow = new LinkedHashMap<>();
        immRow.put("Vaccine Type (CVX)", "208");
        immRow.put("Lot Number", "LOT001");

        Bundle bundle = converter.toBundle(patient, List.of(immRow));

        assertEquals(Bundle.BundleType.SEARCHSET, bundle.getType());
        assertEquals(2, bundle.getEntry().size());
        assertEquals(2, bundle.getTotal());
    }

    @Test
    void toBundle_emptyImmunizations_containsOnlyPatient() {
        Patient patient = new Patient();
        patient.setId("1002");

        Bundle bundle = converter.toBundle(patient, List.of());

        assertEquals(1, bundle.getEntry().size());
    }
}
