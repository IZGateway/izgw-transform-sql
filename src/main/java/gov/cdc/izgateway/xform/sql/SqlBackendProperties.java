package gov.cdc.izgateway.xform.sql;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "sql")
public class SqlBackendProperties {

    private double matchingThreshold = 0.95;
    private Tables tables = new Tables();

    /**
     * Named backend configurations keyed by the {name} path variable.
     * The "dev" backend is always available with built-in defaults.
     */
    private Map<String, SqlBackendConfig> backends = new LinkedHashMap<>();

    public double getMatchingThreshold() { return matchingThreshold; }
    public void setMatchingThreshold(double v) { this.matchingThreshold = v; }
    public Tables getTables() { return tables; }
    public void setTables(Tables tables) { this.tables = tables; }
    public Map<String, SqlBackendConfig> getBackends() { return backends; }
    public void setBackends(Map<String, SqlBackendConfig> backends) { this.backends = backends; }

    public static class Tables {
        private String patient = "patient_view";
        private String immunization = "immunization_view";
        private String patientIdColumn;
        public String getPatient() { return patient; }
        public void setPatient(String patient) { this.patient = patient; }
        public String getImmunization() { return immunization; }
        public void setImmunization(String immunization) { this.immunization = immunization; }
        public String getPatientIdColumn() { return patientIdColumn; }
        public void setPatientIdColumn(String col) { this.patientIdColumn = col; }
    }
}
