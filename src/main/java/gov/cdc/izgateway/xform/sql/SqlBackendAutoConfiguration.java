package gov.cdc.izgateway.xform.sql;

import gov.cdc.izgateway.xform.sql.mapping.SqlMappingConfigLoader;
import gov.cdc.izgateway.xform.sql.mapping.SqlMappingConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

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
}
