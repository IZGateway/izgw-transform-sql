package gov.cdc.izgateway.xform.sql;

import gov.cdc.izgateway.xform.sql.mapping.SqlMappingConfigLoader;
import gov.cdc.izgateway.xform.sql.mapping.SqlMappingConfiguration;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlDevBackendTests {

    private SqlDevBackend backend;

    @BeforeEach
    void setUp() throws Exception {
        SqlMappingConfiguration config = SqlMappingConfigLoader.load(null);
        SqlBackendProperties props = new SqlBackendProperties();
        props.getDev().setPatientsPath("classpath:sql-dev/patients.csv");
        props.getDev().setImmunizationsPath("classpath:sql-dev/immunizations.csv");
        backend = new SqlDevBackend(props, config);
    }

    @Test
    void query_matchingPatient_returnsBundle() {
        Patient search = new Patient();
        search.getNameFirstRep().setFamily("Smith");
        search.setBirthDateElement(new org.hl7.fhir.r4.model.DateType("1985-03-15"));

        Bundle bundle = backend.query(search, null);

        assertEquals(Bundle.BundleType.SEARCHSET, bundle.getType());
        assertTrue(bundle.getTotal() > 0);
    }

    @Test
    void query_noMatch_returnsEmptyBundle() {
        Patient search = new Patient();
        search.getNameFirstRep().setFamily("Nobody");
        search.setBirthDateElement(new org.hl7.fhir.r4.model.DateType("1900-01-01"));

        Bundle bundle = backend.query(search, null);

        assertEquals(0, bundle.getTotal());
    }

    @Test
    void query_patientWithImmunizations_includesImmunizations() {
        Patient search = new Patient();
        search.getNameFirstRep().setFamily("Smith");
        search.setBirthDateElement(new org.hl7.fhir.r4.model.DateType("1985-03-15"));

        Bundle bundle = backend.query(search, null);

        // Patient + 2 immunizations = 3 entries
        assertEquals(3, bundle.getEntry().size());
    }
}
