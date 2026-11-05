package com.unichem.inventory.service;

import com.unichem.inventory.domain.AutomaticImportConfiguration;
import com.unichem.inventory.domain.ImportHistory;
import com.unichem.inventory.domain.Plant;
import com.unichem.inventory.dto.Dtos.*;
import com.unichem.inventory.repo.AutomaticImportConfigurationRepository;
import com.unichem.inventory.repo.ImportHistoryRepository;
import com.unichem.inventory.repo.PlantRepository;
import com.unichem.inventory.web.NotFoundException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Automatic Daily SAP Inventory Import Service.
 * Manages persistent schedule configuration, file-system validation and lock/write-stability checks,
 * dynamic 24-hour scheduling with restart recovery, database/in-process concurrency locking,
 * and execution history tracking while delegating SAP parsing/validation/replacement to ImportService.
 */
@Service
@Slf4j
public class AutomaticImportService {
    public static final String DEFAULT_FOLDER = "C:\\Inventory_Sap";
    public static final String DEFAULT_FILE = "SAP_Inventory.xlsx";
    public static final String MODE_REPLACE_IN_SCOPE = "REPLACE_IN_SCOPE";
    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".xlsx", ".xls");

    private final AutomaticImportConfigurationRepository configRepo;
    private final ImportHistoryRepository historyRepo;
    private final PlantRepository plantRepo;
    private final ImportService importService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final TaskScheduler taskScheduler;
    private final boolean ownsScheduler;

    private final Set<Path> allowedBaseDirectories = ConcurrentHashMap.newKeySet();
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final AtomicBoolean runningGuard = new AtomicBoolean(false);

    @Autowired
    public AutomaticImportService(
            AutomaticImportConfigurationRepository configRepo,
            ImportHistoryRepository historyRepo,
            PlantRepository plantRepo,
            ImportService importService,
            CurrentUserService currentUserService,
            AuditService auditService,
            @Autowired(required = false) TaskScheduler taskScheduler,
            @Value("${app.auto-import.allowed-directories:C:\\Inventory_Sap}") String allowedDirsProperty) {
        this.configRepo = configRepo;
        this.historyRepo = historyRepo;
        this.plantRepo = plantRepo;
        this.importService = importService;
        this.currentUserService = currentUserService;
        this.auditService = auditService;

        if (taskScheduler != null) {
            this.taskScheduler = taskScheduler;
            this.ownsScheduler = false;
        } else {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(2);
            scheduler.setThreadNamePrefix("sap-auto-import-");
            scheduler.initialize();
            this.taskScheduler = scheduler;
            this.ownsScheduler = true;
        }

        registerAllowedDirectories(allowedDirsProperty);
    }

    public final void registerAllowedDirectories(String dirsCsv) {
        if (dirsCsv == null || dirsCsv.isBlank()) return;
        for (String raw : dirsCsv.split("[,;]")) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                try {
                    allowedBaseDirectories.add(Paths.get(trimmed).toAbsolutePath().normalize());
                } catch (InvalidPathException ignored) {
                }
            }
        }
    }

    public void addAllowedDirectory(Path dir) {
        if (dir != null) {
            allowedBaseDirectories.add(dir.toAbsolutePath().normalize());
        }
    }

    /**
     * Resumes enabled schedules automatically after application startup / restart.
     */
    @PostConstruct
    public void recoverSchedulesOnStartup() {
        try {
            AutomaticImportConfiguration cfg = getOrCreateConfigEntity(1L);
            // Clear stale running lock if server crashed mid-import
            if (cfg.getLockAcquiredAt() != null && cfg.getLockAcquiredAt().isBefore(OffsetDateTime.now().minusMinutes(30))) {
                cfg.setLockAcquiredAt(null);
                cfg.setLockedBy(null);
                configRepo.save(cfg);
            }
            if (cfg.isEnabled()) {
                OffsetDateTime now = OffsetDateTime.now();
                if (cfg.getNextExecutionAt() == null || !cfg.getNextExecutionAt().isAfter(now)) {
                    // Schedule missed or due during downtime -> schedule shortly after startup or +frequency
                    OffsetDateTime base = cfg.getLastExecutionAt() != null ? cfg.getLastExecutionAt() : now;
                    OffsetDateTime next = base.plusHours(Math.max(1, cfg.getImportFrequencyHours()));
                    if (!next.isAfter(now)) {
                        next = now.plusHours(Math.max(1, cfg.getImportFrequencyHours()));
                    }
                    cfg.setNextExecutionAt(next);
                    if ("DISABLED".equals(cfg.getLastImportStatus()) || "IDLE".equals(cfg.getLastImportStatus())) {
                        cfg.setLastImportStatus("WAITING_FOR_NEXT_IMPORT");
                    }
                    configRepo.save(cfg);
                }
                scheduleNextRun(cfg);
                log.info("Recovered Automatic SAP Inventory Import schedule for org {} -> nextExecutionAt={}",
                        cfg.getOrganizationId(), cfg.getNextExecutionAt());
            }
        } catch (Exception e) {
            log.warn("Could not initialize automatic import schedule on startup: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduledTasks.values().forEach(f -> f.cancel(false));
        scheduledTasks.clear();
        if (ownsScheduler && taskScheduler instanceof ThreadPoolTaskScheduler ts) {
            ts.shutdown();
        }
    }

    // =========================================================================
    // Configuration Management
    // =========================================================================

    @Transactional
    public AutomaticImportConfigDto getConfig(Long organizationId) {
        Long orgId = organizationId != null ? organizationId : 1L;
        return toConfigDto(getOrCreateConfigEntity(orgId));
    }

    @Transactional
    public AutomaticImportConfigDto saveConfig(AutomaticImportConfigRequest req) {
        if (req == null) throw new IllegalArgumentException("Configuration request body is required");
        Long orgId = req.organizationId() != null ? req.organizationId() : 1L;
        AutomaticImportConfiguration cfg = getOrCreateConfigEntity(orgId);

        String folder = req.configuredFolderPath() != null && !req.configuredFolderPath().isBlank()
                ? req.configuredFolderPath().trim()
                : (cfg.getConfiguredFolderPath() != null ? cfg.getConfiguredFolderPath() : DEFAULT_FOLDER);

        String fileName = req.configuredFileName() != null && !req.configuredFileName().isBlank()
                ? req.configuredFileName().trim()
                : null;

        if ((fileName == null || fileName.isBlank()) && req.configuredFilePath() != null && !req.configuredFilePath().isBlank()) {
            Path p = validateSyntaxPath(req.configuredFilePath().trim());
            if (p.getFileName() != null) {
                fileName = p.getFileName().toString();
            }
            if (req.configuredFolderPath() == null || req.configuredFolderPath().isBlank()) {
                if (p.getParent() != null) {
                    folder = p.getParent().toString();
                }
            }
        }
        if (fileName == null || fileName.isBlank()) {
            fileName = cfg.getConfiguredFileName() != null ? cfg.getConfiguredFileName() : DEFAULT_FILE;
        }

        ResolvedPaths resolved = validateAndResolvePaths(folder, fileName, req.configuredFilePath(), false);

        int freqHours = req.importFrequencyHours() != null ? req.importFrequencyHours() : cfg.getImportFrequencyHours();
        if (freqHours <= 0 || freqHours > 720) {
            throw new IllegalArgumentException("importFrequencyHours must be between 1 and 720 hours");
        }

        String mode = req.importMode() != null && !req.importMode().isBlank()
                ? req.importMode().trim().toUpperCase(Locale.ROOT)
                : MODE_REPLACE_IN_SCOPE;
        if (!MODE_REPLACE_IN_SCOPE.equals(mode) && !"REPLACE".equals(mode)) {
            throw new IllegalArgumentException("Unsupported importMode '" + mode + "'. Supported mode: REPLACE_IN_SCOPE");
        }

        String actor = currentUsernameOrSystem();
        cfg.setConfiguredFolderPath(resolved.folderPath().toString());
        cfg.setConfiguredFileName(resolved.fileName());
        cfg.setConfiguredFilePath(resolved.filePath().toString());
        cfg.setImportFrequencyHours(freqHours);
        cfg.setImportMode(MODE_REPLACE_IN_SCOPE);
        if (req.skipUnchangedFile() != null) {
            cfg.setSkipUnchangedFile(req.skipUnchangedFile());
        }
        if (req.scopeBuCode() != null) {
            cfg.setScopeBuCode(req.scopeBuCode().isBlank() ? null : req.scopeBuCode().trim());
        }
        if (req.scopePlantName() != null) {
            cfg.setScopePlantName(req.scopePlantName().isBlank() ? null : req.scopePlantName().trim());
        }

        boolean enableRequested = req.enabled() != null ? req.enabled() : cfg.isEnabled();
        cfg.setEnabled(enableRequested);
        cfg.setUpdatedBy(actor);
        cfg.setUpdatedAt(OffsetDateTime.now());

        if (enableRequested) {
            OffsetDateTime next = OffsetDateTime.now().plusHours(freqHours);
            cfg.setNextExecutionAt(next);
            if (cfg.getFirstExecutionAt() == null) {
                cfg.setFirstExecutionAt(next);
            }
            if (!"RUNNING".equals(cfg.getLastImportStatus())) {
                cfg.setLastImportStatus("WAITING_FOR_NEXT_IMPORT");
            }
        } else {
            cfg.setNextExecutionAt(null);
            cfg.setLastImportStatus("DISABLED");
        }

        AutomaticImportConfiguration saved = configRepo.save(cfg);
        if (saved.isEnabled()) {
            scheduleNextRun(saved);
        } else {
            cancelScheduledRun(saved.getOrganizationId());
        }

        auditService.log("Auto-import config saved",
                saved.getConfiguredFilePath() + " · every " + saved.getImportFrequencyHours() + "h · enabled=" + saved.isEnabled(),
                "auto_import", String.valueOf(saved.getId()), actor);
        return toConfigDto(saved);
    }

    @Transactional
    public AutomaticImportStatusDto enable(Long organizationId) {
        Long orgId = organizationId != null ? organizationId : 1L;
        AutomaticImportConfiguration cfg = getOrCreateConfigEntity(orgId);
        // Validate path syntax & extension before enabling
        validateAndResolvePaths(cfg.getConfiguredFolderPath(), cfg.getConfiguredFileName(), cfg.getConfiguredFilePath(), false);

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime next = now.plusHours(Math.max(1, cfg.getImportFrequencyHours()));
        cfg.setEnabled(true);
        if (cfg.getFirstExecutionAt() == null) {
            cfg.setFirstExecutionAt(next);
        }
        cfg.setNextExecutionAt(next);
        cfg.setLastImportStatus("WAITING_FOR_NEXT_IMPORT");
        cfg.setUpdatedBy(currentUsernameOrSystem());
        cfg.setUpdatedAt(now);

        AutomaticImportConfiguration saved = configRepo.save(cfg);
        scheduleNextRun(saved);
        auditService.log("Auto-import enabled",
                saved.getConfiguredFilePath() + " · next run at " + saved.getNextExecutionAt(),
                "auto_import", String.valueOf(saved.getId()), saved.getUpdatedBy());
        return toStatusDto(saved);
    }

    @Transactional
    public AutomaticImportStatusDto disable(Long organizationId) {
        Long orgId = organizationId != null ? organizationId : 1L;
        AutomaticImportConfiguration cfg = getOrCreateConfigEntity(orgId);
        cfg.setEnabled(false);
        cfg.setNextExecutionAt(null);
        cfg.setLastImportStatus("DISABLED");
        cfg.setUpdatedBy(currentUsernameOrSystem());
        cfg.setUpdatedAt(OffsetDateTime.now());

        AutomaticImportConfiguration saved = configRepo.save(cfg);
        cancelScheduledRun(orgId);
        auditService.log("Auto-import disabled",
                saved.getConfiguredFilePath(),
                "auto_import", String.valueOf(saved.getId()), saved.getUpdatedBy());
        return toStatusDto(saved);
    }

    @Transactional(readOnly = true)
    public AutomaticImportStatusDto getStatus(Long organizationId) {
        Long orgId = organizationId != null ? organizationId : 1L;
        AutomaticImportConfiguration cfg = configRepo.findByOrganizationId(orgId)
                .orElseGet(() -> defaultTransientConfig(orgId));
        return toStatusDto(cfg);
    }

    @Transactional(readOnly = true)
    public Page<ImportHistoryDto> getHistory(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(200, Math.max(1, size));
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "importStartedAt"));
        return historyRepo.findAllByOrderByImportStartedAtDesc(pageable).map(this::toHistoryDto);
    }

    @Transactional(readOnly = true)
    public ImportHistoryDto getHistoryById(Long id) {
        if (id == null) throw new IllegalArgumentException("History ID is required");
        ImportHistory h = historyRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Import history record " + id + " not found"));
        return toHistoryDto(h);
    }

    // =========================================================================
    // Manual & Scheduled Execution
    // =========================================================================

    /**
     * Triggers an immediate SAP inventory import using the configured file path (`importType = MANUAL`).
     */
    public ImportHistoryDto runNow(Long organizationId, boolean forceWhenUnchanged) {
        Long orgId = organizationId != null ? organizationId : 1L;
        String actor = currentUsernameOrSystem();
        return executeImport(orgId, "MANUAL", actor, forceWhenUnchanged);
    }

    /**
     * Triggers a scheduled SAP inventory import (`importType = AUTOMATIC`).
     */
    public ImportHistoryDto runScheduled(Long organizationId) {
        Long orgId = organizationId != null ? organizationId : 1L;
        return executeImport(orgId, "AUTOMATIC", "scheduler", false);
    }

    public ImportHistoryDto executeImport(Long organizationId, String importType, String actor, boolean forceWhenUnchanged) {
        Long orgId = organizationId != null ? organizationId : 1L;
        if (!runningGuard.compareAndSet(false, true)) {
            throw new IllegalStateException("An automatic or manual SAP inventory import is already running.");
        }

        long startMs = System.currentTimeMillis();
        OffsetDateTime startedAt = OffsetDateTime.now();
        AutomaticImportConfiguration cfg = getOrCreateConfigEntity(orgId);

        if ("AUTOMATIC".equals(importType) && !cfg.isEnabled()) {
            runningGuard.set(false);
            throw new IllegalStateException("Automatic SAP import is disabled.");
        }

        // Check database-level lock as well
        if (cfg.getLockAcquiredAt() != null && cfg.getLockAcquiredAt().isAfter(startedAt.minusMinutes(30))) {
            runningGuard.set(false);
            throw new IllegalStateException("Another import instance holds the database lock (locked by " + cfg.getLockedBy() + ").");
        }

        ImportHistory history = new ImportHistory();
        history.setConfigurationId(cfg.getId());
        history.setFileName(cfg.getConfiguredFileName());
        history.setFilePath(cfg.getConfiguredFilePath());
        history.setImportType(importType);
        history.setImportStartedAt(startedAt);
        history.setStatus("RUNNING");
        history.setExecutedBy(actor);
        history = saveHistoryNewTx(history);

        acquireDbLockNewTx(cfg.getId(), actor, startedAt);

        try {
            // 1. Validate directory & file existence, extension, permissions, and read latest file bytes safely
            ResolvedPaths paths = validateAndResolvePaths(
                    cfg.getConfiguredFolderPath(),
                    cfg.getConfiguredFileName(),
                    cfg.getConfiguredFilePath(),
                    true);

            FileSnapshot fileSnap = readLatestStableFile(paths.filePath());
            history.setFileName(paths.fileName());
            history.setFilePath(paths.filePath().toString());
            history.setFileSize(fileSnap.size());
            history.setFileModifiedAt(fileSnap.modifiedAt());

            // 2. Check if file is unchanged during scheduled run when skipUnchangedFile=true
            if ("AUTOMATIC".equals(importType) && !forceWhenUnchanged && cfg.isSkipUnchangedFile()
                    && cfg.getLastImportedFileModifiedAt() != null
                    && cfg.getLastImportedFileSize() != null
                    && cfg.getLastImportedFileSize() == fileSnap.size()
                    && cfg.getLastImportedFileModifiedAt().toInstant().equals(fileSnap.modifiedAt().toInstant())) {

                OffsetDateTime completedAt = OffsetDateTime.now();
                long durationMs = Math.max(1L, System.currentTimeMillis() - startMs);
                history.setImportCompletedAt(completedAt);
                history.setStatus("SUCCESS");
                history.setErrorMessage("Skipped replacement: file has not changed since last successful import.");
                history.setExecutionDuration(durationMs);
                history = saveHistoryNewTx(history);

                finalizeConfigAfterRunNewTx(cfg.getId(), true, completedAt, fileSnap,
                        "WAITING_FOR_NEXT_IMPORT",
                        "File unchanged since last import (" + paths.fileName() + ")");
                return toHistoryDto(history);
            }

            // 3. Determine authorized plant scope (combines user scope and configured scope)
            Set<String> allowedPlants = resolveImportScope(cfg, importType);

            // 4. Execute validated REPLACE_IN_SCOPE via existing ImportService (atomic DB transaction)
            String sourceLabel = ("AUTOMATIC".equals(importType) ? "Scheduled SAP import (" : "Manual SAP auto-import (") + paths.fileName() + ")";
            ImportResult result = importService.runAutomatedReplaceInScope(
                    fileSnap.bytes(),
                    paths.fileName(),
                    allowedPlants,
                    actor,
                    sourceLabel);

            OffsetDateTime completedAt = OffsetDateTime.now();
            long durationMs = Math.max(1L, System.currentTimeMillis() - startMs);

            history.setImportCompletedAt(completedAt);
            history.setTotalRecords(result.rowsRead());
            history.setSuccessfulRecords(result.ready());
            history.setFailedRecords(result.skipped());
            history.setAddedRecords(result.added());
            history.setUpdatedRecords(result.updated());
            history.setStatus("SUCCESS");
            history.setErrorMessage(null);
            history.setExecutionDuration(durationMs);
            history = saveHistoryNewTx(history);

            String msg = String.format("Imported %d records from %s (%d read, %d skipped)",
                    result.added(), paths.fileName(), result.rowsRead(), result.skipped());
            finalizeConfigAfterRunNewTx(cfg.getId(), true, completedAt, fileSnap,
                    cfg.isEnabled() ? "WAITING_FOR_NEXT_IMPORT" : "SUCCESS", msg);

            return toHistoryDto(history);
        } catch (Exception ex) {
            OffsetDateTime completedAt = OffsetDateTime.now();
            long durationMs = Math.max(1L, System.currentTimeMillis() - startMs);
            String errMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();

            history.setImportCompletedAt(completedAt);
            history.setStatus("FAILED");
            history.setErrorMessage(errMsg.length() > 2000 ? errMsg.substring(0, 2000) : errMsg);
            history.setExecutionDuration(durationMs);
            history = saveHistoryNewTx(history);

            finalizeConfigAfterRunNewTx(cfg.getId(), false, completedAt, null, "FAILED", errMsg);
            auditService.log("Auto-import failed", history.getFileName() + " · " + errMsg, "auto_import", String.valueOf(history.getId()), actor);
            throw (ex instanceof RuntimeException re) ? re : new IllegalArgumentException(errMsg, ex);
        } finally {
            releaseDbLockNewTx(cfg.getId());
            runningGuard.set(false);
        }
    }

    // =========================================================================
    // Dynamic 24-Hour Task Scheduling
    // =========================================================================

    private void scheduleNextRun(AutomaticImportConfiguration cfg) {
        cancelScheduledRun(cfg.getOrganizationId());
        if (!cfg.isEnabled() || cfg.getNextExecutionAt() == null) return;

        Instant runAt = cfg.getNextExecutionAt().toInstant();
        if (runAt.isBefore(Instant.now())) {
            runAt = Instant.now().plusSeconds(5);
        }
        Long orgId = cfg.getOrganizationId();
        ScheduledFuture<?> future = taskScheduler.schedule(() -> {
            try {
                runScheduled(orgId);
            } catch (Exception e) {
                log.error("Scheduled SAP inventory import failed for org {}: {}", orgId, e.getMessage());
            }
        }, runAt);
        if (future != null) {
            scheduledTasks.put(orgId, future);
        }
    }

    private void cancelScheduledRun(Long organizationId) {
        if (organizationId == null) return;
        ScheduledFuture<?> existing = scheduledTasks.remove(organizationId);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    // =========================================================================
    // File Access, Security Validation & Write-Stability Check
    // =========================================================================

    public record ResolvedPaths(Path folderPath, String fileName, Path filePath) {}
    public record FileSnapshot(byte[] bytes, long size, OffsetDateTime modifiedAt) {}

    public ResolvedPaths validateAndResolvePaths(
            String folderPathStr,
            String fileNameStr,
            String fullFilePathStr,
            boolean verifyExistsAndReadable) {

        if (folderPathStr == null || folderPathStr.isBlank()) {
            throw new IllegalArgumentException("Configured folder path is required");
        }
        if (fileNameStr == null || fileNameStr.isBlank()) {
            throw new IllegalArgumentException("Configured file name is required");
        }

        // Prevent path traversal sequences in raw inputs
        if (containsTraversal(folderPathStr) || containsTraversal(fileNameStr) || containsTraversal(fullFilePathStr)) {
            throw new IllegalArgumentException("Path traversal ('..') is not allowed in import paths");
        }

        if (fileNameStr.contains("/") || fileNameStr.contains("\\")) {
            throw new IllegalArgumentException("File name must not contain directory separators: " + fileNameStr);
        }

        String lowerFile = fileNameStr.trim().toLowerCase(Locale.ROOT);
        boolean validExt = SUPPORTED_EXTENSIONS.stream().anyMatch(lowerFile::endsWith);
        if (!validExt) {
            throw new IllegalArgumentException("Unsupported Excel file format for '" + fileNameStr + "'. Only .xlsx and .xls files are supported.");
        }

        Path folder = validateSyntaxPath(folderPathStr.trim()).toAbsolutePath().normalize();
        Path file = folder.resolve(fileNameStr.trim()).normalize();

        if (fullFilePathStr != null && !fullFilePathStr.isBlank()) {
            Path explicitFile = validateSyntaxPath(fullFilePathStr.trim()).toAbsolutePath().normalize();
            if (!explicitFile.equals(file)) {
                // Ensure explicit file is inside the configured folder and has a valid extension
                if (!explicitFile.startsWith(folder)) {
                    throw new IllegalArgumentException("Configured file path must reside inside the configured folder path");
                }
                String expName = explicitFile.getFileName() != null ? explicitFile.getFileName().toString() : "";
                String expLower = expName.toLowerCase(Locale.ROOT);
                if (SUPPORTED_EXTENSIONS.stream().noneMatch(expLower::endsWith)) {
                    throw new IllegalArgumentException("Unsupported Excel file format for '" + expName + "'. Only .xlsx and .xls files are supported.");
                }
                file = explicitFile;
                fileNameStr = expName;
            }
        }

        if (!file.startsWith(folder)) {
            throw new IllegalArgumentException("Resolved file path escapes the configured folder path");
        }

        // Enforce authorized directory whitelist
        boolean allowed = allowedBaseDirectories.stream().anyMatch(folder::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException("Directory '" + folder + "' is not an authorized import directory");
        }

        if (verifyExistsAndReadable) {
            if (!Files.exists(folder)) {
                throw new IllegalArgumentException("Configured import folder does not exist: " + folder);
            }
            if (!Files.isDirectory(folder)) {
                throw new IllegalArgumentException("Configured import folder path is not a directory: " + folder);
            }
            if (!Files.isReadable(folder)) {
                throw new IllegalArgumentException("Insufficient permissions to read folder: " + folder);
            }
            if (!Files.exists(file)) {
                throw new IllegalArgumentException("Configured SAP inventory Excel file does not exist: " + file);
            }
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Configured SAP inventory path is not a regular file: " + file);
            }
            if (!Files.isReadable(file)) {
                throw new IllegalArgumentException("Insufficient permissions to read SAP inventory file: " + file);
            }
        }

        return new ResolvedPaths(folder, fileNameStr.trim(), file);
    }

    /**
     * Reads the latest contents of the Excel file from disk, verifying that it is non-empty,
     * not locked by another process, and not actively being copied/written.
     */
    private FileSnapshot readLatestStableFile(Path filePath) {
        int maxAttempts = 3;
        Exception lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                BasicFileAttributes attrs1 = Files.readAttributes(filePath, BasicFileAttributes.class);
                long size1 = attrs1.size();
                if (size1 <= 0) {
                    throw new IllegalArgumentException("Configured Excel file is empty (0 bytes): " + filePath.getFileName());
                }

                // Short stability check to ensure file is not mid-copy
                if (attempt == 1) {
                    Thread.sleep(60);
                }
                BasicFileAttributes attrs2 = Files.readAttributes(filePath, BasicFileAttributes.class);
                if (attrs2.size() != size1 || !attrs2.lastModifiedTime().equals(attrs1.lastModifiedTime())) {
                    throw new IOException("File is currently being modified or copied: " + filePath.getFileName());
                }

                // Verify file is not exclusively locked by another process and read fresh bytes
                try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r");
                     FileChannel channel = raf.getChannel()) {
                    try (FileLock ignored = channel.tryLock(0L, Long.MAX_VALUE, true)) {
                        // Shared lock acquired
                    } catch (Exception lockEx) {
                        // On some filesystems shared lock on read-only channel may throw NonWritableChannelException;
                        // if it's OverlappingFileLockException or IOException due to OS lock, retry
                        if (lockEx instanceof IOException) {
                            throw (IOException) lockEx;
                        }
                    }
                    long length = channel.size();
                    if (length <= 0) {
                        throw new IllegalArgumentException("Configured Excel file is empty (0 bytes): " + filePath.getFileName());
                    }
                    byte[] bytes = Files.readAllBytes(filePath);
                    OffsetDateTime modifiedAt = OffsetDateTime.ofInstant(attrs2.lastModifiedTime().toInstant(), ZoneId.systemDefault());
                    return new FileSnapshot(bytes, bytes.length, modifiedAt);
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(150L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new IllegalStateException("File '" + filePath.getFileName() + "' is locked or being written by another process: "
                + (lastError != null ? lastError.getMessage() : "unreadable"));
    }

    private Set<String> resolveImportScope(AutomaticImportConfiguration cfg, String importType) {
        Set<String> userScope = null;
        if ("MANUAL".equals(importType) && currentUserService.username().isPresent()) {
            userScope = currentUserService.allowedPlants();
        }

        Set<String> configScope = null;
        if (cfg.getScopePlantName() != null && !cfg.getScopePlantName().isBlank() && !"All".equalsIgnoreCase(cfg.getScopePlantName())) {
            configScope = new HashSet<>(Set.of(cfg.getScopePlantName().trim()));
        } else if (cfg.getScopeBuCode() != null && !cfg.getScopeBuCode().isBlank() && !"All".equalsIgnoreCase(cfg.getScopeBuCode())) {
            configScope = new HashSet<>();
            for (Plant p : plantRepo.findAll()) {
                if (cfg.getScopeBuCode().equalsIgnoreCase(p.getBuCode())) {
                    configScope.add(p.getName());
                }
            }
        }

        if (userScope == null && configScope == null) {
            return null; // Full organization scope
        }
        if (userScope != null && configScope != null) {
            Set<String> intersection = new HashSet<>(userScope);
            intersection.retainAll(configScope);
            return intersection;
        }
        return userScope != null ? userScope : configScope;
    }

    // =========================================================================
    // Persistence Helpers
    // =========================================================================

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImportHistory saveHistoryNewTx(ImportHistory h) {
        return historyRepo.save(h);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void acquireDbLockNewTx(Long configId, String actor, OffsetDateTime now) {
        configRepo.findById(configId).ifPresent(c -> {
            c.setLockAcquiredAt(now);
            c.setLockedBy(actor);
            c.setLastExecutionAt(now);
            c.setLastImportStatus("RUNNING");
            configRepo.save(c);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeConfigAfterRunNewTx(
            Long configId,
            boolean success,
            OffsetDateTime completedAt,
            FileSnapshot fileSnap,
            String status,
            String message) {
        configRepo.findById(configId).ifPresent(c -> {
            c.setLastExecutionAt(completedAt);
            if (c.getFirstExecutionAt() == null) {
                c.setFirstExecutionAt(completedAt);
            }
            if (success) {
                c.setLastSuccessfulImportAt(completedAt);
                if (fileSnap != null) {
                    c.setLastImportedFileModifiedAt(fileSnap.modifiedAt());
                    c.setLastImportedFileSize(fileSnap.size());
                }
            }
            if (c.isEnabled()) {
                OffsetDateTime next = completedAt.plusHours(Math.max(1, c.getImportFrequencyHours()));
                c.setNextExecutionAt(next);
            } else {
                c.setNextExecutionAt(null);
            }
            c.setLastImportStatus(status);
            c.setLastImportMessage(message != null && message.length() > 2000 ? message.substring(0, 2000) : message);
            c.setUpdatedAt(completedAt);
            AutomaticImportConfiguration saved = configRepo.save(c);
            if (saved.isEnabled()) {
                scheduleNextRun(saved);
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseDbLockNewTx(Long configId) {
        configRepo.findById(configId).ifPresent(c -> {
            c.setLockAcquiredAt(null);
            c.setLockedBy(null);
            configRepo.save(c);
        });
    }

    private AutomaticImportConfiguration getOrCreateConfigEntity(Long organizationId) {
        Long orgId = organizationId != null ? organizationId : 1L;
        return configRepo.findByOrganizationId(orgId).orElseGet(() -> {
            AutomaticImportConfiguration c = defaultTransientConfig(orgId);
            return configRepo.save(c);
        });
    }

    private AutomaticImportConfiguration defaultTransientConfig(Long organizationId) {
        AutomaticImportConfiguration c = new AutomaticImportConfiguration();
        c.setOrganizationId(organizationId != null ? organizationId : 1L);
        c.setEnabled(false);
        c.setConfiguredFolderPath(DEFAULT_FOLDER);
        c.setConfiguredFileName(DEFAULT_FILE);
        c.setConfiguredFilePath(DEFAULT_FOLDER + "\\" + DEFAULT_FILE);
        c.setImportFrequencyHours(24);
        c.setImportMode(MODE_REPLACE_IN_SCOPE);
        c.setSkipUnchangedFile(true);
        c.setLastImportStatus("DISABLED");
        c.setCreatedBy("system");
        return c;
    }

    private String currentUsernameOrSystem() {
        try {
            return currentUserService.username().orElse("system");
        } catch (Exception e) {
            return "system";
        }
    }

    private static boolean containsTraversal(String path) {
        if (path == null) return false;
        String normalized = path.replace('/', '\\');
        return normalized.contains("..\\") || normalized.contains("\\..") || normalized.equals("..");
    }

    private static Path validateSyntaxPath(String rawPath) {
        try {
            return Paths.get(rawPath);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid file system path: " + rawPath);
        }
    }

    private AutomaticImportConfigDto toConfigDto(AutomaticImportConfiguration c) {
        return new AutomaticImportConfigDto(
                c.getId(),
                c.getOrganizationId(),
                c.isEnabled(),
                c.getConfiguredFolderPath(),
                c.getConfiguredFileName(),
                c.getConfiguredFilePath(),
                c.getImportFrequencyHours(),
                c.getImportMode(),
                c.isSkipUnchangedFile(),
                c.getScopeBuCode(),
                c.getScopePlantName(),
                c.getFirstExecutionAt(),
                c.getLastExecutionAt(),
                c.getLastSuccessfulImportAt(),
                c.getNextExecutionAt(),
                c.getLastImportStatus(),
                c.getLastImportMessage(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                c.getCreatedBy(),
                c.getUpdatedBy()
        );
    }

    private AutomaticImportStatusDto toStatusDto(AutomaticImportConfiguration c) {
        String status = c.getLastImportStatus();
        if (c.isEnabled() && ("DISABLED".equals(status) || "IDLE".equals(status) || status == null)) {
            status = "WAITING_FOR_NEXT_IMPORT";
        } else if (!c.isEnabled() && "WAITING_FOR_NEXT_IMPORT".equals(status)) {
            status = "DISABLED";
        }
        return new AutomaticImportStatusDto(
                c.isEnabled(),
                status,
                c.getConfiguredFolderPath(),
                c.getConfiguredFileName(),
                c.getConfiguredFilePath(),
                c.getImportFrequencyHours(),
                c.getImportMode(),
                c.getFirstExecutionAt(),
                c.getLastExecutionAt(),
                c.getLastSuccessfulImportAt(),
                c.getNextExecutionAt(),
                c.getLastImportMessage()
        );
    }

    private ImportHistoryDto toHistoryDto(ImportHistory h) {
        return new ImportHistoryDto(
                h.getId(),
                h.getConfigurationId(),
                h.getFileName(),
                h.getFilePath(),
                h.getFileSize(),
                h.getFileModifiedAt(),
                h.getImportType(),
                h.getImportStartedAt(),
                h.getImportCompletedAt(),
                h.getTotalRecords(),
                h.getSuccessfulRecords(),
                h.getFailedRecords(),
                h.getAddedRecords(),
                h.getUpdatedRecords(),
                h.getStatus(),
                h.getErrorMessage(),
                h.getExecutedBy(),
                h.getExecutionDuration()
        );
    }
}
