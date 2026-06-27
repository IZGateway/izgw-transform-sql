package gov.cdc.izgateway.xform.sql;

import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.xform.sql.mapping.SqlMappingConfigLoader;
import gov.cdc.izgateway.xform.sql.mapping.SqlMappingConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestMethod;

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
    public SqlDevBackend sqlDevBackend(SqlBackendProperties props, SqlMappingConfiguration config) {
        return new SqlDevBackend(props, config);
    }

    /**
     * Explicitly register /sql/** and /bulk/sql/** paths with the AccessControlRegistry
     * after all beans are initialized. This runs as SmartInitializingSingleton so it fires
     * after the full application context is ready, ensuring the registry is populated
     * regardless of bean creation order.
     */
    @Bean
    public SmartInitializingSingleton sqlAccessControlRegistrar(@Autowired AccessControlRegistry registry) {
        return () -> {
            // Role strings match izgw-transform Roles constants: "xform-sender", "admin"
            String[] queryRoles = {"xform-sender", "admin"};
            RequestMethod[] queryMethods = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST};
            RequestMethod[] getMethods   = {RequestMethod.GET, RequestMethod.HEAD};

            // SqlFhirController paths — /sql/fhir/{name}/**
            registry.register(queryMethods, "/sql/fhir/*/Patient",                   queryRoles);
            registry.register(queryMethods, "/sql/fhir/*/Immunization",               queryRoles);
            registry.register(queryMethods, "/sql/fhir/*/ImmunizationRecommendation", queryRoles);
            registry.register(getMethods,   "/sql/fhir/*/Patient/*",                  queryRoles);
            registry.register(getMethods,   "/sql/fhir/*/Immunization/*",             queryRoles);
            registry.register(getMethods,   "/sql/fhir/*/ImmunizationRecommendation/*", queryRoles);
            registry.register(new RequestMethod[]{RequestMethod.POST},
                              "/sql/fhir/*/Patient/$match",                           queryRoles);

            // BulkExportController paths — /bulk/sql/fhir/**
            registry.register(new RequestMethod[]{RequestMethod.POST},
                              "/bulk/sql/fhir/$export",                               queryRoles);
            registry.register(getMethods,   "/bulk/sql/fhir/$export-status/*",        queryRoles);
            registry.register(new RequestMethod[]{RequestMethod.DELETE},
                              "/bulk/sql/fhir/$export-status/*",                      queryRoles);
            registry.register(getMethods,   "/bulk/sql/fhir/$export-files/*/*",       queryRoles);

            log.info("SQL backend paths registered with AccessControlRegistry");
        };
    }
}
