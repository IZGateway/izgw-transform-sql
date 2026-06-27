package gov.cdc.izgateway.xform.sql.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;

public class SqlMappingConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(SqlMappingConfigLoader.class);
    private static final String DEFAULT_CLASSPATH = "sql-mapping.yml";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public static SqlMappingConfiguration load(String externalPath) throws IOException {
        Resource resource = resolveResource(externalPath);
        log.info("Loading SQL mapping config from: {}", resource.getDescription());
        try (InputStream in = resource.getInputStream()) {
            return YAML.readValue(in, SqlMappingConfiguration.class);
        }
    }

    private static Resource resolveResource(String externalPath) {
        if (externalPath != null && !externalPath.isBlank()) {
            Resource r = new FileSystemResource(externalPath);
            if (r.exists()) return r;
            log.warn("External mapping config not found at {}, falling back to classpath default", externalPath);
        }
        return new ClassPathResource(DEFAULT_CLASSPATH);
    }
}
