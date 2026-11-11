package com.mfb.lending.module.credit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class ScenarioCreditBureauConfiguration {

    @Bean
    @Primary
    ScenarioCreditBureau scenarioCreditBureau() {
        return new ScenarioCreditBureau();
    }
}
