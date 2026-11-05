package it.infocube.ianus.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import it.infocube.ianus.plugin.core.collector.Collectors;
import it.infocube.ianus.plugin.core.source.SourceAdapter;
import it.infocube.ianus.plugin.sources.prometheus.PrometheusProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.config.ConfigDataNotFoundException;
import org.springframework.boot.test.context.SpringBootTest;

/** Il file esterno e' obbligatorio e il template distribuito deve essere valido. */
@SpringBootTest(properties = {
        "spring.config.import=file:config/ianus-template.yml",
        "ianus.api-key=test-key",
        "ianus.sources.opennms.enabled=true",
        "ianus.sources.prometheus.enabled=true",
        "ianus.sources.icinga.enabled=true",
        "ianus.sources.icinga.poll-interval=PT1H",
        "ianus.sources.zabbix.enabled=true"
})
class ConfigurationFileTest {

    @Autowired
    List<SourceAdapter> adapters;

    @Autowired
    PrometheusProperties prometheus;

    @Autowired
    Collectors collectors;

    @Test
    void templateBindsAllSources() {
        assertThat(adapters).hasSize(4);
        assertThat(prometheus.metrics()).extracting(PrometheusProperties.PromMetric::name).contains("cpu_usage");
        assertThat(collectors.store("opennms").interval()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void startupFailsWithoutExternalFile() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(IanusPluginApplication.class)
                .web(WebApplicationType.NONE);
        assertThatThrownBy(() -> app.run("--spring.config.location=file:src/main/resources/application.yml",
                "--ianus.config=target/file-inesistente.yml"))
                .isInstanceOf(ConfigDataNotFoundException.class);
    }
}
