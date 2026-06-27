package gov.cdc.izgateway.xform.sql.bulk;

import gov.cdc.izgateway.security.AccessControlRegistry;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * HL7 Bulk FHIR $export endpoints at /bulk/sql/fhir/$export.
 * Follows the HL7 Bulk Data Access specification.
 */
@RestController
@Lazy(false)
@RequestMapping("/bulk/sql/fhir")
@RolesAllowed({"xform-sender", "admin"})
public class BulkExportController {

    private static final Logger log = LoggerFactory.getLogger(BulkExportController.class);

    private final BulkExportJobStore jobStore;
    private final BulkExportOutputStore outputStore;

    public BulkExportController(
        @Autowired BulkExportJobStore jobStore,
        @Autowired BulkExportOutputStore outputStore,
        @Autowired AccessControlRegistry registry
    ) {
        this.jobStore = jobStore;
        this.outputStore = outputStore;
        registry.register(this);
    }

    @Operation(summary = "Kick off a Bulk FHIR export job")
    @PostMapping("/$export")
    public ResponseEntity<Void> kickoff(
        @RequestHeader(value = "Accept", required = false) String accept,
        @RequestHeader(value = "Prefer", required = false) String prefer,
        @RequestParam(value = "_since", required = false) String since,
        @RequestParam(value = "_type", required = false) String type,
        @RequestParam(value = "_typeFilter", required = false) String typeFilter,
        HttpServletRequest req
    ) {
        if (!"respond-async".equals(prefer)) {
            return ResponseEntity.badRequest().build();
        }

        if (typeFilter != null && !isSupportedTypeFilter(typeFilter)) {
            return ResponseEntity.badRequest().build();
        }

        BulkExportJob job = new BulkExportJob();
        job.setSinceParam(since);
        job.setTypeParam(type);
        job.setTypeFilter(typeFilter);
        jobStore.create(job);

        URI statusUrl = URI.create(req.getRequestURL().toString()
            .replace("/$export", "/$export-status/" + job.getId()));
        log.info("Bulk export job created: {}", job.getId());

        return ResponseEntity.accepted()
            .location(statusUrl)
            .build();
    }

    @Operation(summary = "Poll bulk export job status")
    @GetMapping("/$export-status/{jobId}")
    public ResponseEntity<?> status(@PathVariable UUID jobId) {
        BulkExportJob job = jobStore.get(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        return switch (job.getStatus()) {
            case PENDING, RUNNING -> ResponseEntity.accepted()
                .header("X-Progress", job.getStatus().name())
                .build();
            case COMPLETE -> ResponseEntity.ok(buildManifest(job));
            case FAILED -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", job.getErrorMessage()));
        };
    }

    @Operation(summary = "Signal completion and schedule cleanup")
    @DeleteMapping("/$export-status/{jobId}")
    public ResponseEntity<Void> complete(@PathVariable UUID jobId) {
        BulkExportJob job = jobStore.get(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            outputStore.delete(jobId);
        } catch (Exception e) {
            log.warn("Failed to delete output files for job {}: {}", jobId, e.getMessage());
        }
        jobStore.delete(jobId);
        return ResponseEntity.accepted().build();
    }

    private boolean isSupportedTypeFilter(String typeFilter) {
        return typeFilter.startsWith("Immunization?") || typeFilter.startsWith("Patient?");
    }

    private Map<String, Object> buildManifest(BulkExportJob job) {
        return Map.of(
            "transactionTime", job.getTransactionTime() != null ? job.getTransactionTime().toString() : "",
            "requiresAccessToken", true,
            "output", job.getOutputFiles(),
            "error", java.util.List.of()
        );
    }
}
