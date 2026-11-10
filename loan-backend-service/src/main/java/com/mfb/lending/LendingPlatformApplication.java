package com.mfb.lending;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LendingPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(LendingPlatformApplication.class, args);
    }
}
