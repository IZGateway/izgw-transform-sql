package gov.cdc.izgateway.xform.sql;

import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.xform.sql.mapping.SqlMappingConfiguration;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SqlFhirControllerTests {

    private SqlFhirController controller;
    private SqlDevBackend devBackend;

    @BeforeEach
    void setUp() {
        devBackend = Mockito.mock(SqlDevBackend.class);
        Bundle emptyBundle = new Bundle();
        emptyBundle.setType(Bundle.BundleType.SEARCHSET);
        emptyBundle.setTotal(0);
        when(devBackend.query(any(), any())).thenReturn(emptyBundle);

        controller = new SqlFhirController(
            devBackend,
            null,  // no JDBC backend
            Mockito.mock(AccessControlRegistry.class)
        );
    }

    @Test
    void query_patientAgainstDev_returnsOk() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/sql/fhir/dev/Patient");
        ResponseEntity<String> response = controller.query(
            "dev", "Patient", "Smith", null, "1985-03-15", null, null, req);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(devBackend).query(any(), isNull());
    }

    @Test
    void query_nonPatientResource_returnsOkEmptyBundle() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/sql/fhir/dev/Immunization");
        ResponseEntity<String> response = controller.query(
            "dev", "Immunization", null, null, null, null, null, req);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("searchset"));
        // DevBackend should not be called for non-Patient resource types
        verify(devBackend, never()).query(any(), any());
    }

    @Test
    void query_unknownBackendWithoutJdbc_returnsServiceUnavailable() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/sql/fhir/wa-doh/Patient");
        ResponseEntity<String> response = controller.query(
            "wa-doh", "Patient", "Smith", null, "1985-03-15", null, null, req);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    void query_withLastUpdated_passedToBackend() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/sql/fhir/dev/Patient");
        controller.query("dev", "Patient", "Smith", null, "1985-03-15", null, "ge2020-01-01", req);
        verify(devBackend).query(any(), eq("ge2020-01-01"));
    }

    @Test
    void read_returnsOk() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/sql/fhir/dev/Patient/123");
        ResponseEntity<String> response = controller.read("dev", "Patient", "123", req);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void patientMatch_returnsOk() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/sql/fhir/dev/Patient/$match");
        ResponseEntity<String> response = controller.patientMatch("dev", "Patient", req);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
