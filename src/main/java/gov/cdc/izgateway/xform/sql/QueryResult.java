package gov.cdc.izgateway.xform.sql;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;

/**
 * Result of a single-patient FHIR query. Either a searchset Bundle (match or
 * no-match) or an ambiguous OperationOutcome when multiple candidates exceed
 * the match threshold.
 */
public final class QueryResult {

    private final Bundle bundle;
    private final OperationOutcome operationOutcome;

    private QueryResult(Bundle bundle, OperationOutcome operationOutcome) {
        this.bundle = bundle;
        this.operationOutcome = operationOutcome;
    }

    public static QueryResult bundle(Bundle b) {
        return new QueryResult(b, null);
    }

    public static QueryResult ambiguous(OperationOutcome o) {
        return new QueryResult(null, o);
    }

    public static QueryResult noMatch() {
        Bundle b = new Bundle();
        b.setType(Bundle.BundleType.SEARCHSET);
        b.setTotal(0);
        return new QueryResult(b, null);
    }

    public boolean isAmbiguous() { return operationOutcome != null; }
    public Bundle getBundle() { return bundle; }
    public OperationOutcome getOperationOutcome() { return operationOutcome; }
}
