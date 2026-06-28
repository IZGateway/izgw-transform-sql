package gov.cdc.izgateway.xform.sql;

import org.hl7.fhir.r4.model.Patient;

/**
 * Scores a candidate Patient against a search Patient. Returns a value in [0.0, 1.0].
 * The default implementation in this module performs exact last-name and DOB matching.
 * The hosting application may register a @Primary bean that delegates to IDIMatch.
 */
@FunctionalInterface
public interface PatientMatchScorer {
    double score(Patient search, Patient candidate);
}
