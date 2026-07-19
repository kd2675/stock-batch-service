package stock.batch.service.common.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

@Component
public class StockRuntimeIdentity {

    private final String buildVersion;
    private final String schemaVersion;

    public StockRuntimeIdentity(
            ObjectProvider<BuildProperties> buildPropertiesProvider,
            @Value("${BUILD_SHA:}") String environmentBuildSha,
            @Value("${STOCK_SCHEMA_VERSION:2026-07-15-eod-v1}") String configuredSchemaVersion
    ) {
        BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
        this.buildVersion = firstNonBlank(
                environmentBuildSha,
                buildProperties == null ? null : buildProperties.get("sha"),
                buildProperties == null ? null : buildProperties.getVersion(),
                "unknown"
        );
        this.schemaVersion = firstNonBlank(configuredSchemaVersion, "unapplied");
    }

    public String buildVersion() {
        return buildVersion;
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "unknown";
    }
}
