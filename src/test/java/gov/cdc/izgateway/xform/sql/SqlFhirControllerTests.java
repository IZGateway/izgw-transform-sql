package gov.cdc.izgateway.xform.sql;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class SqlFhirControllerTests {

    private SqlFhirController controller;

    @BeforeEach
    void setUp() {
        controller = new SqlFhirController();
    }

    @Test
    void query_returnsEmptySearchsetBundle() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/sql/fhir/dev/Patient");
        ResponseEntity<?> response = controller.query("dev", "Patient", req);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void query_forImmunization_returnsOk() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/sql/fhir/waiis/Immunization");
        ResponseEntity<?> response = controller.query("waiis", "Immunization", req);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void read_returnsOk() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/sql/fhir/dev/Patient/123");
        ResponseEntity<?> response = controller.read("dev", "Patient", "123", req);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void patientMatch_returnsOk() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/sql/fhir/dev/Patient/$match");
        ResponseEntity<?> response = controller.patientMatch("dev", "Patient", req);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
