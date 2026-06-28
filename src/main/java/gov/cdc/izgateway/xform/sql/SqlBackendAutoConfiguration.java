package gov.cdc.izgateway.xform.sql;

import gov.cdc.izgateway.xform.sql.mapping.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@ComponentScan(basePackages = "gov.cdc.izgateway.xform.sql")
@EnableConfigurationProperties(SqlBackendProperties.class)
public class SqlBackendAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SqlBackendAutoConfiguration.class);

    /**
     * Builds the backend registry from sql.backends configuration.
     * Always includes a "dev" backend with built-in classpath CSV fixtures.
     * Additional backends are created per their declared type.
     */
    @Bean
    public Map<String, IQueryBackend> sqlBackendRegistry(
            SqlBackendProperties props,
            @Autowired(required = false) NamedParameterJdbcTemplate jdbc) {

        Map<String, IQueryBackend> registry = new LinkedHashMap<>();

        // Always register the built-in dev backend
        SqlBackendConfig devConfig = props.getBackends().getOrDefault("dev", defaultDevConfig());
        SqlMappingConfiguration devMapping = loadMapping(devConfig.getMappingConfigPath(),
            "classpath:sql-mapping.yml");
        registry.put("dev", new SqlDevBackend(devConfig, devMapping, props.getMatchingThreshold()));

        // Register additional configured backends
        for (Map.Entry<String, SqlBackendConfig> entry : props.getBackends().entrySet()) {
            String name = entry.getKey();
            if ("dev".equals(name)) continue; // already registered above

            SqlBackendConfig config = entry.getValue();
            SqlMappingConfiguration mapping = loadMapping(config.getMappingConfigPath(), null);
            if (mapping == null) {
                log.warn("Backend '{}' has no mapping-config-path -- skipping", name);
                continue;
            }

            switch (config.getType()) {
                case CSV -> {
                    registry.put(name, new SqlTestBackend(config, mapping, props.getMatchingThreshold()));
                    log.info("Registered CSV backend '{}'", name);
                }
                case JDBC -> {
                    if (jdbc == null) {
                        log.warn("Backend '{}' requires JDBC but no DataSource is configured -- skipping", name);
                        continue;
                    }
                    SqlPatientRowMapper patientMapper = new SqlPatientRowMapper(mapping);
                    SqlImmunizationRowMapper immunizationMapper = new SqlImmunizationRowMapper(mapping);
                    PatientMatchScorer scorer = defaultScorer();
                    SqlPatientSearchService search = new SqlPatientSearchService(
                        jdbc, toTableProps(config), mapping, patientMapper, scorer);
                    SqlImmunizationRetrievalService retrieval = new SqlImmunizationRetrievalService(
                        jdbc, toTableProps(config), mapping);
                    registry.put(name, new SqlFhirBackend(search, retrieval, patientMapper, immunizationMapper, mapping));
                    log.info("Registered JDBC backend '{}'", name);
                }
                default -> log.warn("Unknown backend type '{}' for backend '{}' -- skipping",
                    config.getType(), name);
            }
        }

        log.info("SQL backend registry: {}", registry.keySet());
        return registry;
    }

    private SqlBackendConfig defaultDevConfig() {
        SqlBackendConfig c = new SqlBackendConfig();
        c.setType(SqlBackendConfig.Type.DEV_CSV);
        c.setPatientsPath("classpath:sql-dev/patients.csv");
        c.setImmunizationsPath("classpath:sql-dev/immunizations.csv");
        c.setMappingConfigPath("classpath:sql-mapping.yml");
        return c;
    }

    private SqlMappingConfiguration loadMapping(String path, String fallback) {
        String target = (path != null && !path.isBlank()) ? path : fallback;
        if (target == null) return null;
        try {
            return SqlMappingConfigLoader.load(target);
        } catch (IOException e) {
            log.error("Failed to load mapping from {}: {}", target, e.getMessage());
            return new SqlMappingConfiguration();
        }
    }

    /** Adapts a SqlBackendConfig's table settings into the shape SqlPatientSearchService expects. */
    private SqlBackendProperties toTableProps(SqlBackendConfig config) {
        SqlBackendProperties props = new SqlBackendProperties();
        SqlBackendProperties.Tables tables = new SqlBackendProperties.Tables();
        tables.setPatient(config.getPatientTable());
        tables.setImmunization(config.getImmunizationTable());
        tables.setPatientIdColumn(config.getPatientIdColumn());
        props.setTables(tables);
        return props;
    }

    private PatientMatchScorer defaultScorer() {
        return (search, candidate) -> {
            String sf = search.hasName() && search.getNameFirstRep().getFamily() != null
                ? search.getNameFirstRep().getFamily() : "";
            String cf = candidate.hasName() && candidate.getNameFirstRep().getFamily() != null
                ? candidate.getNameFirstRep().getFamily() : "";
            String sd = search.hasBirthDate() ? search.getBirthDateElement().asStringValue() : "";
            String cd = candidate.hasBirthDate() ? candidate.getBirthDateElement().asStringValue() : "";
            return (!sf.isBlank() && sf.equalsIgnoreCase(cf) && !sd.isBlank() && sd.equals(cd)) ? 1.0 : 0.0;
        };
    }
}
