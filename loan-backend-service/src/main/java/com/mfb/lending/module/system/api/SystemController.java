package com.mfb.lending.module.system.api;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.system.dto.VersionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System", description = "Platform foundation endpoints")
public class SystemController {

    private final ObjectProvider<BuildProperties> buildProperties;

    public SystemController(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    @GetMapping("/version")
    @Operation(summary = "Get the running application version")
    public ApiResponse<VersionResponse> version() {
        BuildProperties build = buildProperties.getIfAvailable();
        String name = build == null ? "loan-backend-service" : build.getName();
        String version = build == null ? "dev" : build.getVersion();
        return ApiResponse.success(new VersionResponse(
                name,
                version,
                System.getProperty("java.version"),
                SpringBootVersion.getVersion()));
    }
}
