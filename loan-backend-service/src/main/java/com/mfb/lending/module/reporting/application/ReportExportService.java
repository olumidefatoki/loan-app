package com.mfb.lending.module.reporting.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.config.ReportingProperties;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.auth.security.TokenHasher;
import com.mfb.lending.module.reporting.api.ExportRequest;
import com.mfb.lending.module.reporting.calculation.ReportTables;
import com.mfb.lending.module.reporting.domain.ExportFormat;
import com.mfb.lending.module.reporting.domain.ExportStatus;
import com.mfb.lending.module.reporting.domain.ReportCode;
import com.mfb.lending.module.reporting.domain.ReportingModels.ExportJob;
import com.mfb.lending.module.reporting.dto.ReportResponses.ExportResponse;
import com.mfb.lending.module.reporting.persistence.ReportingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ReportExportService {

    private static final Logger log = LoggerFactory.getLogger(ReportExportService.class);
    private static final String FAILURE = "The report could not be generated";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ReportingStore store;
    private final ReportingService reports;
    private final ReportingAccess access;
    private final ReportingProperties properties;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final java.time.Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ReportExportService(ReportingStore store, ReportingService reports, ReportingAccess access, ReportingProperties properties,
            AuditService audit, ObjectMapper objectMapper, java.time.Clock clock) {
        this.store = store;
        this.reports = reports;
        this.access = access;
        this.properties = properties;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public ExportResponse request(AuthenticatedUser actor, ExportRequest request, ClientContext client) {
        access.export(actor);
        ReportingScope scope = ReportingScope.parse(request.toQuery(), actor, zone(), clock, properties.getMaxRangeDays());
        long rows = count(request.getReport(), scope);
        if (rows > properties.getMaxRows()) {
            throw ValidationException.field("report", "The report is too large to export");
        }
        if (request.getFormat() == ExportFormat.PDF && rows > properties.getPdfMaxRows()) {
            throw ValidationException.field("format", "A PDF export is limited to " + properties.getPdfMaxRows() + " rows. Use CSV or Excel for a larger extract.");
        }
        if (store.activeExports(actor.getId()) >= properties.getMaxActiveExports()) {
            throw new ConflictException("A report export is already running");
        }
        boolean async = rows > properties.getAsyncThreshold();
        String token = token();
        Instant now = clock.instant();
        ExportJob job = new ExportJob(UUID.randomUUID(), request.getReport(), request.getFormat(), async ? ExportStatus.PENDING : ExportStatus.RUNNING,
                writeFilters(scope), actor.getId(), name(actor), null, null, null, null, TokenHasher.sha256(token), null,
                now.plus(properties.getExportTtl()), now, async ? null : now, null, null);
        store.insertExport(job);
        audit.record("REPORT_REQUESTED", "report_export", job.id().toString(), actor.getUsername(), client, Map.of(
                "report", job.reportCode().name(), "format", job.format().name(), "rows", rows, "asynchronous", async));
        if (async) {
            return view(job, token);
        }
        try {
            Written written = write(job, scope);
            ExportJob ready = ready(job, written);
            store.markReady(job.id(), written.rows(), written.path().toString(), written.fileName(), job.format().contentType(), clock.instant());
            return view(ready, token);
        } catch (RuntimeException | IOException ex) {
            log.warn("Report export {} failed", job.id(), ex);
            store.markFailed(job.id(), FAILURE, clock.instant());
            throw new ConflictException(FAILURE);
        }
    }

    public ExportResponse status(AuthenticatedUser actor, UUID id) {
        access.export(actor);
        return view(owned(actor, id), null);
    }

    public Download download(AuthenticatedUser actor, UUID id, String token, ClientContext client) {
        access.export(actor);
        ExportJob job = owned(actor, id);
        if (token == null || token.isBlank() || job.tokenHash() == null || !TokenHasher.matches(token, job.tokenHash())) {
            throw new ResourceNotFoundException("Report export");
        }
        if (job.expiresAt().isBefore(clock.instant()) || job.status() == ExportStatus.EXPIRED) {
            store.markExpired(id);
            delete(job.storagePath());
            throw new ConflictException("The report export has expired");
        }
        if (job.status() != ExportStatus.READY || job.storagePath() == null) {
            throw new ConflictException("The report export is not ready");
        }
        Path file = safe(job.storagePath());
        if (!Files.exists(file)) {
            throw new ResourceNotFoundException("Report export");
        }
        store.touchDownload(id, clock.instant());
        audit.record("REPORT_DOWNLOADED", "report_export", id.toString(), actor.getUsername(), client, Map.of(
                "report", job.reportCode().name(), "format", job.format().name()));
        return new Download(file, job.contentType(), job.fileName());
    }

    public void processPending() {
        for (String path : store.expireReady(clock.instant())) {
            delete(path);
        }
        for (int i = 0; i < 5; i++) {
            ExportJob job = store.lockNextPending(clock.instant()).orElse(null);
            if (job == null) {
                return;
            }
            try {
                ReportingScope scope = ReportingScope.restore(objectMapper.readValue(job.filters(), MAP));
                Written written = write(job, scope);
                store.markReady(job.id(), written.rows(), written.path().toString(), written.fileName(), job.format().contentType(), clock.instant());
            } catch (RuntimeException | IOException ex) {
                log.warn("Report export {} failed", job.id(), ex);
                store.markFailed(job.id(), FAILURE, clock.instant());
            }
        }
    }

    private Written write(ExportJob job, ReportingScope scope) throws IOException {
        Path root = directory();
        Files.createDirectories(root);
        String fileName = job.reportCode().name().toLowerCase(java.util.Locale.ROOT) + "." + job.format().extension();
        Path file = root.resolve(job.id() + "." + job.format().extension()).normalize();
        if (!file.startsWith(root)) {
            throw new IOException("Export path is outside the report directory");
        }
        int cap = properties.getMaxRows() + 1;
        try {
            long rows = ReportWriters.write(job.format(), file, headers(job.reportCode(), scope), sink -> produce(job.reportCode(), scope, cap, sink));
            if (rows > properties.getMaxRows()) {
                throw new IOException("The report is too large to export");
            }
            return new Written(file, fileName, Math.toIntExact(rows));
        } catch (UncheckedIOException ex) {
            delete(file.toString());
            throw ex.getCause();
        } catch (RuntimeException | IOException ex) {
            delete(file.toString());
            throw ex;
        }
    }

    private void produce(ReportCode code, ReportingScope scope, int cap, Consumer<List<String>> rows) {
        switch (code) {
            case RECONCILIATION -> reports.exportReconciliation(scope).forEach(rows);
            case BRANCH_PERFORMANCE, PRODUCT_PERFORMANCE, INTEREST_INCOME, FEE_INCOME ->
                    reports.exportGroups(code, scope).stream().map(PortfolioGroups::cells).forEach(rows);
            default -> store.streamDetails(code, scope, cap, rows);
        }
    }

    private List<String> headers(ReportCode code, ReportingScope scope) {
        return switch (code) {
            case RECONCILIATION -> ReportTables.RECONCILIATION_HEADERS;
            case BRANCH_PERFORMANCE, PRODUCT_PERFORMANCE, INTEREST_INCOME, FEE_INCOME -> PortfolioGroups.HEADERS;
            default -> store.detailHeaders(code, scope);
        };
    }

    private long count(ReportCode code, ReportingScope scope) {
        return switch (code) {
            case RECONCILIATION -> reports.exportReconciliation(scope).size();
            case BRANCH_PERFORMANCE, PRODUCT_PERFORMANCE, INTEREST_INCOME, FEE_INCOME -> reports.exportGroups(code, scope).size();
            default -> store.countDetails(code, scope);
        };
    }

    private ExportJob owned(AuthenticatedUser actor, UUID id) {
        ExportJob job = store.findExport(id).orElseThrow(() -> new ResourceNotFoundException("Report export"));
        if (!job.requestedBy().equals(actor.getId())) {
            throw new ResourceNotFoundException("Report export");
        }
        return job;
    }

    private ExportResponse view(ExportJob job, String token) {
        ExportStatus status = job.status();
        if (status == ExportStatus.READY && job.expiresAt().isBefore(clock.instant())) {
            status = ExportStatus.EXPIRED;
        }
        String path = status == ExportStatus.READY ? "/api/v1/reports/exports/" + job.id() + "/download" : null;
        return new ExportResponse(job.id(), job.reportCode().name(), job.format().name(), status.name(), job.expiresAt(),
                job.rowCount(), job.fileName(), path, token);
    }

    private ExportJob ready(ExportJob job, Written written) {
        return new ExportJob(job.id(), job.reportCode(), job.format(), ExportStatus.READY, job.filters(), job.requestedBy(),
                job.requestedByName(), written.fileName(), job.format().contentType(), written.path().toString(), written.rows(),
                job.tokenHash(), null, job.expiresAt(), job.createdAt(), job.startedAt(), clock.instant(), null);
    }

    private String writeFilters(ReportingScope scope) {
        try {
            return objectMapper.writeValueAsString(scope.snapshot());
        } catch (IOException ex) {
            throw new IllegalStateException("Report filters could not be stored", ex);
        }
    }

    private Path directory() {
        return Path.of(properties.getDirectory()).toAbsolutePath().normalize();
    }

    private Path safe(String stored) {
        Path root = directory();
        Path file = Path.of(stored).toAbsolutePath().normalize();
        if (!file.startsWith(root)) {
            throw new ResourceNotFoundException("Report export");
        }
        return file;
    }

    private void delete(String stored) {
        if (stored == null || stored.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(stored));
        } catch (IOException ex) {
            log.warn("Report file could not be removed");
        }
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String name(AuthenticatedUser actor) {
        String username = actor.getUsername() == null ? "" : actor.getUsername();
        return username.length() <= 128 ? username : username.substring(0, 128);
    }

    private java.time.ZoneId zone() {
        return java.time.ZoneId.of(properties.getZone());
    }

    public record Download(Path path, String contentType, String fileName) {
    }

    private record Written(Path path, String fileName, int rows) {
    }
}
