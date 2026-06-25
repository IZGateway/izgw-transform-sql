package gov.cdc.izgateway.xform.sql;

import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stub controller activated when the SQL module is not configured.
 * Returns 503 for all /sql/** and /bulk/sql/** paths so callers
 * receive a meaningful error rather than a 404.
 */
@RestController
@ConditionalOnMissingBean(SqlBackendAutoConfiguration.class)
@Configuration
public class SqlUnavailableController {

    @RequestMapping({"/sql/**", "/bulk/sql/**"})
    public ResponseEntity<OperationOutcome> unavailable(HttpServletRequest req) {
        OperationOutcome oo = new OperationOutcome();
        OperationOutcome.OperationOutcomeIssueComponent issue = oo.addIssue();
        issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
        issue.setCode(OperationOutcome.IssueType.NOTSUPPORTED);
        issue.setDiagnostics(
            "SQL backend is not configured. Deploy the izgw-transform-sql module " +
            "and configure a datasource to enable this endpoint.");
        return new ResponseEntity<>(oo, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
