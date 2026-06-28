package gov.cdc.izgateway.xform.sql;

import gov.cdc.izgateway.xform.sql.mapping.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;

@Configuration
@ComponentScan(basePackages = "gov.cdc.izgateway.xform.sql")
@EnableConfigurationProperties(SqlBackendProperties.class)
public class SqlBackendAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SqlBackendAutoConfiguration.class);

    @Bean
    public SqlMappingConfiguration sqlMappingConfiguration(SqlBackendProperties props) {
        try {
            return SqlMappingConfigLoader.load(props.getMappingConfigPath());
        } catch (IOException e) {
            log.error("Failed to load SQL mapping configuration: {}", e.getMessage());
            return new SqlMappingConfiguration();
        }
    }

    @Bean
    public SqlPatientRowMapper sqlPatientRowMapper(SqlMappingConfiguration config) {
        return new SqlPatientRowMapper(config);
    }

    @Bean
    public SqlImmunizationRowMapper sqlImmunizationRowMapper(SqlMappingConfiguration config) {
        return new SqlImmunizationRowMapper(config);
    }

    @Bean
    public SqlDevBackend sqlDevBackend(SqlBackendProperties props, SqlMappingConfiguration config) {
        return new SqlDevBackend(props, config);
    }

    /**
     * Default scorer: exact case-insensitive last-name + DOB match returns 1.0; anything else 0.0.
     * Override by declaring a @Primary PatientMatchScorer bean in the hosting application.
     */
    @Bean
    public PatientMatchScorer defaultPatientMatchScorer() {
        return (search, candidate) -> {
            String searchFamily = search.hasName() && search.getNameFirstRep().getFamily() != null
                ? search.getNameFirstRep().getFamily() : "";
            String candidateFamily = candidate.hasName() && candidate.getNameFirstRep().getFamily() != null
                ? candidate.getNameFirstRep().getFamily() : "";
            String searchDob = search.hasBirthDate() ? search.getBirthDateElement().asStringValue() : "";
            String candidateDob = candidate.hasBirthDate() ? candidate.getBirthDateElement().asStringValue() : "";
            boolean nameMatch = !searchFamily.isBlank() && searchFamily.equalsIgnoreCase(candidateFamily);
            boolean dobMatch = !searchDob.isBlank() && searchDob.equals(candidateDob);
            return (nameMatch && dobMatch) ? 1.0 : 0.0;
        };
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    public SqlPatientSearchService sqlPatientSearchService(NamedParameterJdbcTemplate jdbc,
                                                          SqlBackendProperties props,
                                                          SqlMappingConfiguration config,
                                                          SqlPatientRowMapper patientMapper,
                                                          PatientMatchScorer scorer) {
        log.info("Registering SqlPatientSearchService (DataSource present)");
        return new SqlPatientSearchService(jdbc, props, config, patientMapper, scorer);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    public SqlImmunizationRetrievalService sqlImmunizationRetrievalService(NamedParameterJdbcTemplate jdbc,
                                                                           SqlBackendProperties props,
                                                                           SqlMappingConfiguration config) {
        log.info("Registering SqlImmunizationRetrievalService (DataSource present)");
        return new SqlImmunizationRetrievalService(jdbc, props, config);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    public SqlFhirBackend sqlFhirBackend(SqlPatientSearchService patientSearch,
                                         SqlImmunizationRetrievalService immunizationRetrieval,
                                         SqlPatientRowMapper patientMapper,
                                         SqlImmunizationRowMapper immunizationMapper,
                                         SqlMappingConfiguration config) {
        log.info("Registering SqlFhirBackend (DataSource present)");
        return new SqlFhirBackend(patientSearch, immunizationRetrieval, patientMapper, immunizationMapper, config);
    }
}
