package gov.cdc.izgateway.xform.sql;

import org.hl7.fhir.r4.model.Patient;

/**
 * Common contract for all named SQL FHIR query backends (CSV fixtures and JDBC).
 */
public interface IQueryBackend {
    QueryResult query(Patient searchPatient, String lastUpdated);
}
