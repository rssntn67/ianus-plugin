package it.infocube.ianus.plugin.config;

import java.time.Clock;

import it.infocube.ianus.plugin.core.service.ResultCache;
import it.infocube.ianus.plugin.core.window.WindowResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CoreConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public WindowResolver windowResolver(IanusProperties properties, Clock clock) {
        return new WindowResolver(properties.windows(), clock);
    }

    @Bean
    public ResultCache resultCache(IanusProperties properties, Clock clock) {
        return new ResultCache(properties.cacheTtl(), properties.cacheMaxEntries(), clock);
    }
}
