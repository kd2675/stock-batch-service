package stock.batch.service.common.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

@Component
public class StockRuntimeIdentity {

    private final String buildVersion;
    private final String schemaVersion;
    private final String eodContractVersion;

    public StockRuntimeIdentity(
            ObjectProvider<BuildProperties> buildPropertiesProvider,
            @Value("${BUILD_SHA:}") String environmentBuildSha,
            @Value("${STOCK_SCHEMA_VERSION:2026-07-23-auto-profile-v2-direct}") String configuredSchemaVersion,
            @Value("${STOCK_EOD_CONTRACT_VERSION:EOD_V1}") String configuredEodContractVersion
    ) {
        BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
        this.buildVersion = firstNonBlank(
                environmentBuildSha,
                buildProperties == null ? null : buildProperties.get("sha"),
                buildProperties == null ? null : buildProperties.getVersion(),
                "unknown"
        );
        this.schemaVersion = firstNonBlank(configuredSchemaVersion, "unapplied");
        this.eodContractVersion = firstNonBlank(configuredEodContractVersion, "unapplied");
    }

    public String buildVersion() {
        return buildVersion;
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public String eodContractVersion() {
        return eodContractVersion;
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
