package com.mfb.lending.module.reporting.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.config.ReportingProperties;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.domain.SecurityProfile;
import com.mfb.lending.module.auth.domain.UserRecord;
import com.mfb.lending.module.auth.domain.UserStatus;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.auth.security.TokenHasher;
import com.mfb.lending.module.reporting.api.ExportRequest;
import com.mfb.lending.module.reporting.domain.ExportFormat;
import com.mfb.lending.module.reporting.domain.ExportStatus;
import com.mfb.lending.module.reporting.domain.ReportCode;
import com.mfb.lending.module.reporting.domain.ReportingModels.ExportJob;
import com.mfb.lending.module.reporting.persistence.ReportingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportExportServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");

    private final ReportingStore store = mock(ReportingStore.class);
    private final ReportingService reports = mock(ReportingService.class);
    private final ReportingAccess access = mock(ReportingAccess.class);
    private final AuditService audit = mock(AuditService.class);

    @TempDir
    Path directory;

    @Test
    void aSmallExtractIsWrittenImmediatelyAndALargeOneStaysPending() throws Exception {
        ReportExportService service = service(500, 50_000, 2_000);
        when(store.countDetails(any(), any())).thenReturn(1L);
        when(store.activeExports(any())).thenReturn(0);
        when(store.detailHeaders(any(), any())).thenReturn(List.of("Customer"));
        when(store.streamDetails(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            Consumer<List<String>> rows = invocation.getArgument(3);
            rows.accept(List.of("=cmd"));
            return 1L;
        });

        var created = service.request(exporter(), request(ExportFormat.CSV), null);
        assertThat(created.status()).isEqualTo("READY");
        assertThat(created.token()).hasSize(64);
        ArgumentCaptor<ExportJob> inserted = ArgumentCaptor.forClass(ExportJob.class);
        verify(store).insertExport(inserted.capture());
        assertThat(inserted.getValue().status()).isEqualTo(ExportStatus.RUNNING);
        String csv = Files.readString(directory.resolve(inserted.getValue().id() + ".csv"));
        assertThat(csv).contains("\"'=cmd\"");

        when(store.countDetails(any(), any())).thenReturn(501L);
        var pending = service.request(exporter(), request(ExportFormat.CSV), null);
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.token()).isNotBlank();
        assertThat(pending.downloadPath()).isNull();
    }

    @Test
    void oversizedAndPdfExtractsAreRejectedBeforeAFileIsCreated() {
        ReportExportService service = service(500, 1_000, 10);
        when(store.countDetails(any(), any())).thenReturn(1_001L);
        assertThatThrownBy(() -> service.request(exporter(), request(ExportFormat.CSV), null)).isInstanceOf(ValidationException.class);
        when(store.countDetails(any(), any())).thenReturn(11L);
        assertThatThrownBy(() -> service.request(exporter(), request(ExportFormat.PDF), null)).isInstanceOf(ValidationException.class);
        when(store.countDetails(any(), any())).thenReturn(1L);
        when(store.activeExports(any())).thenReturn(3);
        assertThatThrownBy(() -> service.request(exporter(), request(ExportFormat.CSV), null)).isInstanceOf(ConflictException.class);
        verify(store, never()).insertExport(any());
    }

    @Test
    void downloadRequiresTheOwnerAndALiveToken() {
        ReportExportService service = service(500, 50_000, 2_000);
        UUID id = UUID.randomUUID();
        AuthenticatedUser owner = exporter();
        ExportJob foreign = job(id, UUID.randomUUID(), NOW.plus(Duration.ofHours(1)), ExportStatus.READY, "secret");
        when(store.findExport(id)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.download(owner, id, "secret", null)).isInstanceOf(ResourceNotFoundException.class);

        ExportJob mismatched = job(id, owner.getId(), NOW.plus(Duration.ofHours(1)), ExportStatus.READY, "secret");
        when(store.findExport(id)).thenReturn(Optional.of(mismatched));
        assertThatThrownBy(() -> service.download(owner, id, "other", null)).isInstanceOf(ResourceNotFoundException.class);

        ExportJob expired = job(id, owner.getId(), NOW.minusSeconds(1), ExportStatus.READY, "secret");
        when(store.findExport(id)).thenReturn(Optional.of(expired));
        assertThatThrownBy(() -> service.download(owner, id, "secret", null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("expired");
        verify(store).markExpired(id);

        var status = service.status(owner, id);
        assertThat(status.token()).isNull();
    }

    private ReportExportService service(int asyncThreshold, int maxRows, int pdfMax) {
        ReportingProperties properties = new ReportingProperties();
        properties.setDirectory(directory.toString());
        properties.setAsyncThreshold(asyncThreshold);
        properties.setMaxRows(maxRows);
        properties.setPdfMaxRows(pdfMax);
        properties.setMaxActiveExports(3);
        properties.setExportTtl(Duration.ofHours(24));
        properties.setZone("Africa/Lagos");
        return new ReportExportService(store, reports, access, properties, audit, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ExportRequest request(ExportFormat format) {
        ExportRequest request = new ExportRequest();
        request.setReport(ReportCode.DISBURSEMENTS);
        request.setFormat(format);
        return request;
    }

    private static AuthenticatedUser exporter() {
        UUID id = UUID.randomUUID();
        UserRecord record = new UserRecord(id, PrincipalType.STAFF, null, "Ada", null, "ada@mfb.test", null, "hash", UserStatus.ACTIVE,
                true, true, 0, null, Instant.EPOCH, null, false, null, null, Instant.EPOCH, Instant.EPOCH, "test", "test");
        return AuthenticatedUser.from(record, UUID.randomUUID(),
                new SecurityProfile(List.of(), List.of(PermissionCodes.REPORT_EXPORT), List.of(), false, true));
    }

    private static ExportJob job(UUID id, UUID owner, Instant expires, ExportStatus status, String token) {
        return new ExportJob(id, ReportCode.DISBURSEMENTS, ExportFormat.CSV, status, "{}", owner, "ada@mfb.test",
                "disbursements.csv", "text/csv", null, 0, TokenHasher.sha256(token), null, expires, NOW, NOW, NOW, null);
    }
}
