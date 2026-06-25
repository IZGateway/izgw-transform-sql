package gov.cdc.izgateway.xform.sql.bulk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class BulkExportControllerTests {

    private BulkExportController controller;
    private InMemoryBulkExportJobStore jobStore;
    private TempFileBulkExportOutputStore outputStore;

    @BeforeEach
    void setUp() {
        jobStore = new InMemoryBulkExportJobStore();
        outputStore = new TempFileBulkExportOutputStore();
        controller = new BulkExportController(jobStore, outputStore);
    }

    @Test
    void kickoff_missingPreferHeader_returns400() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/bulk/sql/fhir/$export");
        req.setServerName("localhost");
        req.setServerPort(443);
        req.setScheme("https");
        ResponseEntity<Void> response = controller.kickoff(
            "application/fhir+json", null, null, null, null, req);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void kickoff_validRequest_returns202WithContentLocation() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/bulk/sql/fhir/$export");
        req.setServerName("localhost");
        req.setServerPort(443);
        req.setScheme("https");
        ResponseEntity<Void> response = controller.kickoff(
            "application/fhir+json", "respond-async", null, null, null, req);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getHeaders().getLocation());
    }

    @Test
    void kickoff_unsupportedTypeFilter_returns400() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/bulk/sql/fhir/$export");
        req.setServerName("localhost");
        req.setServerPort(443);
        req.setScheme("https");
        ResponseEntity<Void> response = controller.kickoff(
            "application/fhir+json", "respond-async", null, null, "Observation?status=final", req);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void status_inProgress_returns202() {
        BulkExportJob job = jobStore.create(new BulkExportJob());
        ResponseEntity<?> status = controller.status(job.getId());
        assertEquals(HttpStatus.ACCEPTED, status.getStatusCode());
    }

    @Test
    void delete_returns202() {
        BulkExportJob job = jobStore.create(new BulkExportJob());
        ResponseEntity<Void> response = controller.complete(job.getId());
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
    }

    @Test
    void delete_unknownJob_returns404() {
        ResponseEntity<Void> response = controller.complete(java.util.UUID.randomUUID());
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
