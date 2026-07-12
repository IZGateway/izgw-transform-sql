package gov.cdc.izgateway.xform.sql;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * Automatically activates the "sql" Spring profile when izgw-transform-sql is on the classpath.
 * This causes application-sql.yml to load, which registers the configured SQL backends
 * (including the "test" backend used by the local newman test collection).
 *
 * @author Audacious Inquiry
 */
public class SqlProfileActivator implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("sql"))) {
            environment.addActiveProfile("sql");
        }
    }
}
