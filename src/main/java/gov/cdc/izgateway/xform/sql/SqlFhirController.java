package gov.cdc.izgateway.xform.sql;

import gov.cdc.izgateway.security.AccessControlRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Handles single-patient FHIR queries against named SQL backends.
 * Owns all paths under /sql/fhir/{name}/**, entirely distinct from
 * FhirController at /fhir/**.
 */
@RestController
@RequestMapping("/sql/fhir/{name}")
@RolesAllowed({"xform-sender", "admin"})
public class SqlFhirController {

    private static final Logger log = LoggerFactory.getLogger(SqlFhirController.class);

    public SqlFhirController(@Autowired AccessControlRegistry registry) {
        registry.register(this);
    }

    @Operation(summary = "SQL-backed FHIR patient/immunization query")
    @ApiResponse(responseCode = "200", description = "Query completed")
    @GetMapping(
        value = {"/{resourceType}", "/{resourceType}/_search"},
        produces = {"application/fhir+json", "application/fhir+xml", "application/json", "application/xml"}
    )
    public ResponseEntity<Bundle> query(
        @PathVariable String name,
        @PathVariable String resourceType,
        HttpServletRequest req
    ) {
        log.debug("SQL FHIR query: backend={} resource={}", name, resourceType);
        Bundle empty = new Bundle();
        empty.setType(Bundle.BundleType.SEARCHSET);
        empty.setTotal(0);
        return new ResponseEntity<>(empty, HttpStatus.OK);
    }

    @GetMapping("/{resourceType}/{id}")
    public ResponseEntity<Bundle> read(
        @PathVariable String name,
        @PathVariable String resourceType,
        @PathVariable String id,
        HttpServletRequest req
    ) {
        Bundle empty = new Bundle();
        empty.setType(Bundle.BundleType.SEARCHSET);
        return new ResponseEntity<>(empty, HttpStatus.OK);
    }

    @PostMapping(
        value = {"/{resourceType}/$match"},
        produces = {"application/fhir+json", "application/fhir+xml", "application/json"}
    )
    public ResponseEntity<Bundle> patientMatch(
        @PathVariable String name,
        @PathVariable String resourceType,
        HttpServletRequest req
    ) {
        Bundle empty = new Bundle();
        empty.setType(Bundle.BundleType.SEARCHSET);
        return new ResponseEntity<>(empty, HttpStatus.OK);
    }
}
