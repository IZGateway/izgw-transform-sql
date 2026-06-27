package gov.cdc.izgateway.xform.sql;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sql")
public class SqlBackendProperties {

    private double matchingThreshold = 0.95;
    private Tables tables = new Tables();
    private String mappingConfigPath;
    private Dev dev = new Dev();

    public double getMatchingThreshold() { return matchingThreshold; }
    public void setMatchingThreshold(double matchingThreshold) { this.matchingThreshold = matchingThreshold; }
    public Tables getTables() { return tables; }
    public void setTables(Tables tables) { this.tables = tables; }
    public String getMappingConfigPath() { return mappingConfigPath; }
    public void setMappingConfigPath(String mappingConfigPath) { this.mappingConfigPath = mappingConfigPath; }
    public Dev getDev() { return dev; }
    public void setDev(Dev dev) { this.dev = dev; }

    public static class Tables {
        private String patient = "patient_view";
        private String immunization = "immunization_view";
        public String getPatient() { return patient; }
        public void setPatient(String patient) { this.patient = patient; }
        public String getImmunization() { return immunization; }
        public void setImmunization(String immunization) { this.immunization = immunization; }
    }

    public static class Dev {
        private String patientsPath = "classpath:sql-dev/patients.csv";
        private String immunizationsPath = "classpath:sql-dev/immunizations.csv";
        public String getPatientsPath() { return patientsPath; }
        public void setPatientsPath(String patientsPath) { this.patientsPath = patientsPath; }
        public String getImmunizationsPath() { return immunizationsPath; }
        public void setImmunizationsPath(String immunizationsPath) { this.immunizationsPath = immunizationsPath; }
    }
}
