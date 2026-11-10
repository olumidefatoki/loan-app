package com.mfb.lending.module.system;

import java.util.Properties;

import com.mfb.lending.common.correlation.CorrelationIdFilter;
import com.mfb.lending.common.correlation.CorrelationIds;
import com.mfb.lending.module.system.api.SystemController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SystemController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(SystemControllerTest.BuildInfo.class)
class SystemControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Test
    void versionIsPublicAndEchoesTheCorrelationId() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter())
                .build();

        mockMvc.perform(get("/api/v1/system/version").header(CorrelationIds.HEADER, "version-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIds.HEADER, "version-123"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.correlationId").value("version-123"))
                .andExpect(jsonPath("$.data.name").value("MFB Digital Lending Platform"))
                .andExpect(jsonPath("$.data.version").value("0.1.0"))
                .andExpect(jsonPath("$.data.javaVersion").isNotEmpty())
                .andExpect(jsonPath("$.data.springBootVersion").isNotEmpty());
    }

    @TestConfiguration
    static class BuildInfo {

        @Bean
        BuildProperties buildProperties() {
            Properties properties = new Properties();
            properties.setProperty("name", "MFB Digital Lending Platform");
            properties.setProperty("version", "0.1.0");
            return new BuildProperties(properties);
        }
    }
}
