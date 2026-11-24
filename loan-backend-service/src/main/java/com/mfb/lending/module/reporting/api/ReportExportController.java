package com.mfb.lending.module.reporting.api;

import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.reporting.application.ReportExportService;
import com.mfb.lending.module.reporting.application.ReportExportService.Download;
import com.mfb.lending.module.reporting.dto.ReportResponses.ExportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports/exports")
@Tag(name = "Report exports")
@SecurityRequirement(name = "bearerAuth")
public class ReportExportController {

    private final ReportExportService exports;

    public ReportExportController(ReportExportService exports) {
        this.exports = exports;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Request a CSV, Excel, or PDF export. Large extracts are generated asynchronously.")
    public ResponseEntity<ApiResponse<ExportResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody ExportRequest request,
            HttpServletRequest http) {
        ExportResponse response = exports.request(actor, request, ClientContexts.from(http));
        HttpStatus status = "PENDING".equals(response.status()) ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Export status for the user who requested it")
    public ApiResponse<ExportResponse> status(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(exports.status(actor, id));
    }

    @GetMapping("/{id}/download")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Download a ready export with the one-time token")
    public ResponseEntity<Resource> download(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @RequestParam String token,
            HttpServletRequest http) {
        Download download = exports.download(actor, id, token, ClientContexts.from(http));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.fileName() + "\"")
                .contentType(MediaType.parseMediaType(download.contentType()))
                .body(new FileSystemResource(download.path()));
    }
}
