package gov.cdc.izgateway.xform.sql;

import gov.cdc.izgateway.security.AccessControlRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import ca.uhn.fhir.context.FhirContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Handles single-patient FHIR queries against named SQL backends.
 * Routes each {name} to the registered IQueryBackend for that name.
 * Backends are registered by SqlBackendAutoConfiguration from sql.backends config.
 */
@RestController
@Lazy(false)
@RequestMapping("/sql/fhir/{name}")
@RolesAllowed({"xform-sender", "admin"})
public class SqlFhirController {

    private static final Logger log = LoggerFactory.getLogger(SqlFhirController.class);
    private static final FhirContext FHIR_CTX = FhirContext.forR4();

    private final Map<String, IQueryBackend> backends;

    public SqlFhirController(@Autowired Map<String, IQueryBackend> backends,
                             @Autowired AccessControlRegistry registry) {
        this.backends = backends;
        registry.register(this);
        log.info("SqlFhirController registered with backends: {}", backends.keySet());
    }

    @Operation(summary = "SQL-backed FHIR patient/immunization query")
    @ApiResponse(responseCode = "200", description = "Query completed")
    @GetMapping(value = {"/{resourceType}", "/{resourceType}/_search"})
    public ResponseEntity<String> query(
        @PathVariable String name,
        @PathVariable String resourceType,
        @RequestParam(required = false) String family,
        @RequestParam(required = false) String given,
        @RequestParam(required = false) String birthdate,
        @RequestParam(required = false) String gender,
        @RequestParam(name = "_lastUpdated", required = false) String lastUpdated,
        HttpServletRequest req
    ) {
        log.debug("SQL FHIR query: backend={} resource={}", name, resourceType);

        if (!"Patient".equalsIgnoreCase(resourceType)) {
            return fhirJson(emptyBundle());
        }

        IQueryBackend backend = backends.get(name);
        if (backend == null) {
            return unavailable(name);
        }

        Patient search = buildSearchPatient(family, given, birthdate, gender);
        QueryResult result = backend.query(search, lastUpdated);

        if (result.isAmbiguous()) {
            return fhirResponse(HttpStatus.UNPROCESSABLE_ENTITY, result.getOperationOutcome());
        }
        return fhirJson(result.getBundle());
    }

    @GetMapping("/{resourceType}/{id}")
    public ResponseEntity<String> read(
        @PathVariable String name,
        @PathVariable String resourceType,
        @PathVariable String id,
        HttpServletRequest req
    ) {
        return fhirJson(emptyBundle());
    }

    @PostMapping(
        value = {"/{resourceType}/$match"},
        produces = {"application/fhir+json", "application/fhir+xml", "application/json"}
    )
    public ResponseEntity<String> patientMatch(
        @PathVariable String name,
        @PathVariable String resourceType,
        HttpServletRequest req
    ) {
        return fhirJson(emptyBundle());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Patient buildSearchPatient(String family, String given, String birthdate, String gender) {
        Patient p = new Patient();
        if (family != null && !family.isBlank()) p.getNameFirstRep().setFamily(family);
        if (given != null && !given.isBlank()) p.getNameFirstRep().addGiven(given);
        if (birthdate != null && !birthdate.isBlank()) {
            try { p.setBirthDateElement(new DateType(birthdate)); } catch (Exception ignored) {}
        }
        if (gender != null && !gender.isBlank()) {
            try { p.setGender(Enumerations.AdministrativeGender.fromCode(gender)); } catch (Exception ignored) {}
        }
        return p;
    }

    private ResponseEntity<String> unavailable(String name) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(OperationOutcome.IssueType.NOTSUPPORTED)
            .getDetails().setText("SQL backend '" + name + "' is not configured");
        return fhirResponse(HttpStatus.SERVICE_UNAVAILABLE, outcome);
    }

    private static Bundle emptyBundle() {
        Bundle b = new Bundle();
        b.setType(Bundle.BundleType.SEARCHSET);
        b.setTotal(0);
        return b;
    }

    private ResponseEntity<String> fhirJson(IBaseResource resource) {
        return fhirResponse(HttpStatus.OK, resource);
    }

    private ResponseEntity<String> fhirResponse(HttpStatus status, IBaseResource resource) {
        return ResponseEntity.status(status)
            .header("Content-Type", "application/fhir+json")
            .body(FHIR_CTX.newJsonParser().encodeResourceToString(resource));
    }
}
