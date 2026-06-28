package gov.cdc.izgateway.xform.sql;

/**
 * Configuration for a single named SQL FHIR backend.
 * Bound from sql.backends.{name} in application properties.
 */
public class SqlBackendConfig {

    public enum Type { DEV_CSV, CSV, JDBC }

    /** Backend type: DEV_CSV (two-file hub fixture), CSV (one-table WA DOH), or JDBC. */
    private Type type = Type.JDBC;

    /** Path to sql-mapping.yml for this backend. */
    private String mappingConfigPath;

    // -- CSV (one-table) settings --
    /** Path to the all_vax_event CSV file. Defaults to /data/all_vax_event.csv. */
    private String dataPath = "/data/all_vax_event.csv";

    // -- DEV_CSV (two-table) settings --
    private String patientsPath = "classpath:sql-dev/patients.csv";
    private String immunizationsPath = "classpath:sql-dev/immunizations.csv";

    // -- JDBC settings --
    private String patientTable = "patient_view";
    private String immunizationTable = "immunization_view";
    private String patientIdColumn;

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public String getMappingConfigPath() { return mappingConfigPath; }
    public void setMappingConfigPath(String mappingConfigPath) { this.mappingConfigPath = mappingConfigPath; }
    public String getDataPath() { return dataPath; }
    public void setDataPath(String dataPath) { this.dataPath = dataPath; }
    public String getPatientsPath() { return patientsPath; }
    public void setPatientsPath(String patientsPath) { this.patientsPath = patientsPath; }
    public String getImmunizationsPath() { return immunizationsPath; }
    public void setImmunizationsPath(String immunizationsPath) { this.immunizationsPath = immunizationsPath; }
    public String getPatientTable() { return patientTable; }
    public void setPatientTable(String patientTable) { this.patientTable = patientTable; }
    public String getImmunizationTable() { return immunizationTable; }
    public void setImmunizationTable(String immunizationTable) { this.immunizationTable = immunizationTable; }
    public String getPatientIdColumn() { return patientIdColumn; }
    public void setPatientIdColumn(String patientIdColumn) { this.patientIdColumn = patientIdColumn; }
}
