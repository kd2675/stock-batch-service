package stock.batch.service.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDirectConfigurationTest {

    @Test
    void localDirectProfile_disablesDiscovery() throws IOException {
        PropertySource<?> properties = loadLocalDirectProperties();

        assertThat(properties.getProperty("spring.cloud.discovery.enabled")).isEqualTo(false);
        assertThat(properties.getProperty("spring.cloud.service-registry.auto-registration.enabled")).isEqualTo(false);
        assertThat(properties.getProperty("eureka.client.enabled")).isEqualTo(false);
        assertThat(properties.getProperty("eureka.client.registerWithEureka")).isEqualTo(false);
        assertThat(properties.getProperty("eureka.client.fetchRegistry")).isEqualTo(false);
    }

    private PropertySource<?> loadLocalDirectProperties() throws IOException {
        return new YamlPropertySourceLoader()
                .load("local-direct", new ClassPathResource("application-local-direct.yml"))
                .get(0);
    }
}
