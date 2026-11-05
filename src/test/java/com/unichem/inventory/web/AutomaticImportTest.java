package com.unichem.inventory.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unichem.inventory.domain.*;
import com.unichem.inventory.dto.Dtos.*;
import com.unichem.inventory. dto.Thresholds;
import com.unichem.inventory.repo.*;
import com.unichem.inventory.service.*;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AutomaticImportTest {

    @TempDir
    Path tempDir;

    @Mock private AutomaticImportConfigurationRepository configRepo;
    @Mock private ImportHistoryRepository historyRepo;
    @Mock private InventoryItemRepository inventoryRepo;
    @Mock private ActionItemRepository actionRepo;
    @Mock private InventorySnapshotRepository snapshotRepo;
    @Mock private SyncLogRepository syncLogRepo;
    @Mock private PlantRepository plantRepo;
    @Mock private BusinessUnitRepository buRepo;
    @Mock private MaterialTypeMapRepository mtypeRepo;
    @Mock private MaterialGroupMapRepository mgroupRepo;
    @Mock private StorageLocationRepository slocRepo;
    @Mock private AppUserRepository userRepo;
    @Mock private AppRoleRepository roleRepo;
    @Mock private SettingsService settingsService;
    @Mock private SnapshotService snapshotService;
    @Mock private AuditService auditService;

    private CurrentUserService currentUserService;
    private MasterLookup masterLookup;
    private CalcService calcService;
    private ImportService importService;
    private AutomaticImportService automaticImportService;
    private AutomaticImportController controller;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private final Map<Long, AutomaticImportConfiguration> configStore = new LinkedHashMap<>();
    private final Map<Long, ImportHistory> historyStore = new LinkedHashMap<>();
    private final Map<Long, InventoryItem> inventoryStore = new LinkedHashMap<>();
    private final Map<String, ActionItem> actionStore = new LinkedHashMap<>();
    private final Map<Long, InventorySnapshot> snapshotStore = new LinkedHashMap<>();
    private final Map<Long, Plant> plantStore = new LinkedHashMap<>();
    private final Map<String, BusinessUnit> buStore = new LinkedHashMap<>();
    private final Map<String, MaterialTypeMap> mtypeStore = new LinkedHashMap<>();
    private final Map<String, AppUser> userStore = new LinkedHashMap<>();

    private long nextConfigId = 1L;
    private long nextHistoryId = 1L;
    private long nextInventoryId = 1L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        configStore.clear();
        historyStore.clear();
        inventoryStore.clear();
        actionStore.clear();
        snapshotStore.clear();
        plantStore.clear();
        buStore.clear();
        mtypeStore.clear();
        userStore.clear();
        nextConfigId = 1L;
        nextHistoryId = 1L;
        nextInventoryId = 1L;

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        currentUserService = new CurrentUserService(userRepo, roleRepo, plantRepo);
        masterLookup = new MasterLookup(plantRepo, buRepo, mtypeRepo, mgroupRepo, slocRepo);
        calcService = new CalcService();
        importService = new ImportService(inventoryRepo, actionRepo, syncLogRepo, masterLookup,
                currentUserService, calcService, settingsService, snapshotService, auditService);
        automaticImportService = new AutomaticImportService(
                configRepo, historyRepo, plantRepo, importService, currentUserService, auditService,
                null, tempDir.toString() + ";C:\\Inventory_Sap");
        controller = new AutomaticImportController(automaticImportService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        setupRepositoryMocks();
        seedMastersAndExistingRecords();
        authenticateAsAdmin();
    }

    @AfterEach
    void tearDown() {
        if (automaticImportService != null) {
            automaticImportService.shutdown();
        }
        SecurityContextHolder.clearContext();
    }

    private void setupRepositoryMocks() {
        // AutomaticImportConfigurationRepository
        lenient().when(configRepo.findByOrganizationId(anyLong())).thenAnswer(inv -> {
            Long orgId = inv.getArgument(0);
            return configStore.values().stream()
                    .filter(c -> Objects.equals(c.getOrganizationId(), orgId))
                    .findFirst()
                    .map(this::cloneConfig);
        });
        lenient().when(configRepo.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return Optional.ofNullable(configStore.get(id)).map(this::cloneConfig);
        });
        lenient().when(configRepo.save(any(AutomaticImportConfiguration.class))).thenAnswer(inv -> {
            AutomaticImportConfiguration c = inv.getArgument(0);
            if (c.getId() == null) c.setId(nextConfigId++);
            AutomaticImportConfiguration copy = cloneConfig(c);
            configStore.put(copy.getId(), copy);
            return cloneConfig(copy);
        });

        // ImportHistoryRepository
        lenient().when(historyRepo.save(any(ImportHistory.class))).thenAnswer(inv -> {
            ImportHistory h = inv.getArgument(0);
            if (h.getId() == null) h.setId(nextHistoryId++);
            historyStore.put(h.getId(), h);
            return h;
        });
        lenient().when(historyRepo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(historyStore.get(inv.<Long>getArgument(0))));
        lenient().when(historyRepo.findAllByOrderByImportStartedAtDesc(any(Pageable.class))).thenAnswer(inv -> {
            Pageable p = inv.getArgument(0);
            List<ImportHistory> sorted = historyStore.values().stream()
                    .sorted(Comparator.comparing(ImportHistory::getImportStartedAt).reversed())
                    .toList();
            return new PageImpl<>(sorted, p, sorted.size());
        });

        // InventoryItemRepository (with transactional snapshot simulation on failure)
        lenient().when(inventoryRepo.findAll()).thenAnswer(inv -> new ArrayList<>(inventoryStore.values()));
        lenient().doAnswer(inv -> {
            inventoryStore.clear();
            return null;
        }).when(inventoryRepo).deleteAllInBatch();
        lenient().when(inventoryRepo.deleteByPlantIn(anyCollection())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Collection<String> plantsToRemove = inv.getArgument(0);
            int before = inventoryStore.size();
            inventoryStore.values().removeIf(i -> plantsToRemove.contains(i.getPlant()));
            return before - inventoryStore.size();
        });
        lenient().when(inventoryRepo.saveAll(anyIterable())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Iterable<InventoryItem> items = inv.getArgument(0);
            List<InventoryItem> saved = new ArrayList<>();
            for (InventoryItem item : items) {
                if (item.getId() == null) item.setId(nextInventoryId++);
                inventoryStore.put(item.getId(), item);
                saved.add(item);
            }
            return saved;
        });

        // Masters & Settings
        lenient().when(plantRepo.findAll()).thenAnswer(inv -> new ArrayList<>(plantStore.values()));
        lenient().when(buRepo.findAll()).thenAnswer(inv -> new ArrayList<>(buStore.values()));
        lenient().when(mtypeRepo.findAll()).thenAnswer(inv -> new ArrayList<>(mtypeStore.values()));
        lenient().when(mgroupRepo.findAll()).thenReturn(List.of());
        lenient().when(slocRepo.findAll()).thenReturn(List.of());
        lenient().when(settingsService.thresholds()).thenReturn(
                new Thresholds(180, 180, 365, 180, 90, 90, 30, 500000.0, 4));
        lenient().when(userRepo.findByUsernameIgnoreCase(anyString())).thenAnswer(inv ->
                Optional.ofNullable(userStore.get(inv.<String>getArgument(0).toLowerCase())));
    }

    private void seedMastersAndExistingRecords() {
        buStore.put("API", new BusinessUnit(1L, "API", "Active Pharmaceutical Ingredients", "API", true, 1));
        buStore.put("Formulation", new BusinessUnit(2L, "Formulation", "Formulation", "OSD", true, 2));

        plantStore.put(1L, new Plant(1L, "1710", "PITHAMPUR-API", "API", 1L, "Pithampur", true, 1));
        plantStore.put(2L, new Plant(2L, "1720", "KOLHAPUR-API", "API", 1L, "Kolhapur", true, 2));
        plantStore.put(3L, new Plant(3L, "1100", "Baddi-1", "Formulation", 2L, "Baddi", true, 3));

        MaterialTypeMap zraw = new MaterialTypeMap();
        zraw.setSapCode("ZRAW"); zraw.setDescription("ULL Raw Materials"); zraw.setCategory("Raw Material"); zraw.setActive(true);
        mtypeStore.put("ZRAW", zraw);

        MaterialTypeMap zfin = new MaterialTypeMap();
        zfin.setSapCode("ZFIN"); zfin.setDescription("ULL Finished Products"); zfin.setCategory("Finished Goods"); zfin.setActive(true);
        mtypeStore.put("ZFIN", zfin);

        // Seed existing ActionItem and InventorySnapshot to verify they are preserved across imports
        ActionItem act = new ActionItem();
        act.setId("ACT-0001");
        act.setItemKey("PITHAMPUR-API|RM-100|B01");
        act.setBuCode("API");
        act.setPlant("PITHAMPUR-API");
        act.setActionType("Consumption planning");
        act.setPlan("Consume before retest");
        act.setPriority("P1");
        act.setFunctionName("Production");
        act.setStage("Owned");
        actionStore.put(act.getId(), act);

        InventorySnapshot snap = new InventorySnapshot();
        snap.setId(1L);
        snap.setPeriod("2026-10");
        snap.setSnapDate(LocalDate.of(2026, 10, 31));
        snap.setFy("2026-27");
        snap.setSource("manual");
        snapshotStore.put(snap.getId(), snap);
    }

    private void authenticateAsAdmin() {
        AppUser admin = new AppUser();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setName("Admin User");
        admin.setRoleCode("admin");
        admin.setScopeAll(true);
        admin.setActive(true);
        userStore.put("admin", admin);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null,
                        List.of(new SimpleGrantedAuthority("sap.config"),
                                new SimpleGrantedAuthority("masters.edit"),
                                new SimpleGrantedAuthority("import.data"),
                                new SimpleGrantedAuthority("audit.view"))));
    }

    private AutomaticImportConfiguration cloneConfig(AutomaticImportConfiguration s) {
        AutomaticImportConfiguration c = new AutomaticImportConfiguration();
        c.setId(s.getId());
        c.setOrganizationId(s.getOrganizationId());
        c.setEnabled(s.isEnabled());
        c.setConfiguredFolderPath(s.getConfiguredFolderPath());
        c.setConfiguredFileName(s.getConfiguredFileName());
        c.setConfiguredFilePath(s.getConfiguredFilePath());
        c.setImportFrequencyHours(s.getImportFrequencyHours());
        c.setImportMode(s.getImportMode());
        c.setSkipUnchangedFile(s.isSkipUnchangedFile());
        c.setScopeBuCode(s.getScopeBuCode());
        c.setScopePlantName(s.getScopePlantName());
        c.setFirstExecutionAt(s.getFirstExecutionAt());
        c.setLastExecutionAt(s.getLastExecutionAt());
        c.setLastSuccessfulImportAt(s.getLastSuccessfulImportAt());
        c.setLastImportedFileModifiedAt(s.getLastImportedFileModifiedAt());
        c.setLastImportedFileSize(s.getLastImportedFileSize());
        c.setNextExecutionAt(s.getNextExecutionAt());
        c.setLastImportStatus(s.getLastImportStatus());
        c.setLastImportMessage(s.getLastImportMessage());
        c.setLockAcquiredAt(s.getLockAcquiredAt());
        c.setLockedBy(s.getLockedBy());
        c.setCreatedAt(s.getCreatedAt());
        c.setUpdatedAt(s.getUpdatedAt());
        c.setCreatedBy(s.getCreatedBy());
        c.setUpdatedBy(s.getUpdatedBy());
        c.setVersion(s.getVersion());
        return c;
    }

    private Path writeSapExcelFile(String fileName, List<List<String>> dataRows) throws Exception {
        Path file = tempDir.resolve(fileName);
        String[] headers = ImportService.SAP_COLUMNS.toArray(new String[0]);
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("SAP Stock");
            Row hr = sh.createRow(0);
            for (int c = 0; c < headers.length; c++) {
                hr.createCell(c).setCellValue(headers[c]);
            }
            int rIdx = 1;
            for (List<String> rowVals : dataRows) {
                Row row = sh.createRow(rIdx++);
                for (int c = 0; c < rowVals.size(); c++) {
                    row.createCell(c).setCellValue(rowVals.get(c) != null ? rowVals.get(c) : "");
                }
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        }
        return file;
    }

    private List<String> sapRow(String code, String desc, String plant, String sloc, String matType, String batch, String qty, String val, String grDate, String expDate) {
        return List.of(
                code, desc, plant, sloc, matType, "APIS",
                "KG", batch, qty, val, "0",
                "0", "0", "0", "0",
                "0", "0", "0", "2026-01-10", expDate,
                grDate, "2026-10-01"
        );
    }

    // 1. Save automatic import configuration
    @Test
    @DisplayName("1. Save and retrieve automatic import configuration via PUT & GET /api/automatic-import/config")
    void saveAutomaticImportConfiguration() throws Exception {
        String folder = tempDir.toString();
        String filePath = tempDir.resolve("SAP_Inventory.xlsx").toString();

        AutomaticImportConfigRequest req = new AutomaticImportConfigRequest(
                1L, folder, "SAP_Inventory.xlsx", filePath, 24, "REPLACE_IN_SCOPE", false, true, null, null);

        mockMvc.perform(put("/api/automatic-import/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuredFolderPath").value(folder))
                .andExpect(jsonPath("$.configuredFileName").value("SAP_Inventory.xlsx"))
                .andExpect(jsonPath("$.importFrequencyHours").value(24))
                .andExpect(jsonPath("$.importMode").value("REPLACE_IN_SCOPE"))
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(get("/api/automatic-import/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuredFileName").value("SAP_Inventory.xlsx"));
    }

    // 2. Enable and disable automation & verify 24-hour scheduling
    @Test
    @DisplayName("2. Enable and disable automation and verify 24-hour nextExecutionAt calculation")
    void enableAndDisableAutomation() throws Exception {
        OffsetDateTime before = OffsetDateTime.now();

        mockMvc.perform(post("/api/automatic-import/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_NEXT_IMPORT"))
                .andExpect(jsonPath("$.importFrequencyHours").value(24))
                .andExpect(jsonPath("$.nextExecutionAt").isNotEmpty());

        AutomaticImportStatusDto enabledStatus = automaticImportService.getStatus(1L);
        assertThat(enabledStatus.nextExecutionAt()).isAfter(before.plusHours(23).plusMinutes(55));
        assertThat(enabledStatus.nextExecutionAt()).isBefore(before.plusHours(24).plusMinutes(5));

        mockMvc.perform(post("/api/automatic-import/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.nextExecutionAt").doesNotExist());
    }

    // 3. Validate configured directory and Excel file format/path security
    @Test
    @DisplayName("3. Validate configured directory, reject path traversal and unsupported file extensions")
    void validateDirectoryAndFileFormat() {
        // Path traversal rejected
        assertThatThrownBy(() -> automaticImportService.validateAndResolvePaths(
                tempDir.toString() + "\\..\\secret", "SAP_Inventory.xlsx", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path traversal");

        // Unauthorized directory rejected
        assertThatThrownBy(() -> automaticImportService.validateAndResolvePaths(
                "C:\\Unauthorized_Folder", "SAP_Inventory.xlsx", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an authorized import directory");

        // Unsupported file extension (.csv / .txt) rejected
        assertThatThrownBy(() -> automaticImportService.validateAndResolvePaths(
                tempDir.toString(), "SAP_Inventory.txt", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only .xlsx and .xls files are supported");
    }

    // 4. Import the first inventory file, then replace file contents on Day 2 and import updated data
    @Test
    @DisplayName("4. Import Day 1 Excel file and replace with updated Day 2 Excel file contents")
    void importFirstFileAndReplaceWithUpdatedFile() throws Exception {
        // Day 1 file: 2 batches
        Path file = writeSapExcelFile("SAP_Inventory.xlsx", List.of(
                sapRow("RM-101", "Lactose IP", "1710", "RM01", "ZRAW", "B001", "100", "25000", "2026-06-01", "2027-06-01"),
                sapRow("FG-201", "Losartan 50mg", "1100", "FG01", "ZFIN", "B002", "500", "150000", "2026-07-01", "2027-07-01")
        ));

        automaticImportService.saveConfig(new AutomaticImportConfigRequest(
                1L, tempDir.toString(), "SAP_Inventory.xlsx", file.toString(), 24, "REPLACE_IN_SCOPE", true, true, null, null));

        // Execute Day 1 manual import
        ImportHistoryDto day1 = automaticImportService.runNow(1L, true);
        assertThat(day1.status()).isEqualTo("SUCCESS");
        assertThat(day1.addedRecords()).isEqualTo(2);
        assertThat(inventoryStore.values()).extracting(InventoryItem::getCode).containsExactlyInAnyOrder("RM-101", "FG-201");

        // Day 2: Replace Excel file at the same path with new contents (3 different/updated batches)
        writeSapExcelFile("SAP_Inventory.xlsx", List.of(
                sapRow("RM-101", "Lactose IP", "1710", "RM01", "ZRAW", "B001-NEW", "250", "62500", "2026-08-01", "2027-08-01"),
                sapRow("RM-303", "Methanol", "1720", "RM01", "ZRAW", "B003", "1000", "42000", "2026-08-15", "2027-08-15"),
                sapRow("FG-404", "Amlodipine", "1100", "FG01", "ZFIN", "B004", "300", "90000", "2026-09-01", "2027-09-01")
        ));
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(120)));

        // Execute Day 2 scheduled import
        ImportHistoryDto day2 = automaticImportService.runScheduled(1L);
        assertThat(day2.status()).isEqualTo("SUCCESS");
        assertThat(day2.importType()).isEqualTo("AUTOMATIC");
        assertThat(day2.addedRecords()).isEqualTo(3);

        // Verify Day 1 records were replaced by Day 2 records
        assertThat(inventoryStore.values()).hasSize(3);
        assertThat(inventoryStore.values()).extracting(InventoryItem::getCode)
                .containsExactlyInAnyOrder("RM-101", "RM-303", "FG-404");
        assertThat(inventoryStore.values()).extracting(InventoryItem::getBatch)
                .containsExactlyInAnyOrder("B001-NEW", "B003", "B004");
    }

    // 5. Restart Spring Boot and verify schedule recovery
    @Test
    @DisplayName("5. Verify schedule recovery after Spring Boot restart")
    void verifyScheduleRecoveryAfterRestart() {
        AutomaticImportConfiguration persisted = new AutomaticImportConfiguration();
        persisted.setId(1L);
        persisted.setOrganizationId(1L);
        persisted.setEnabled(true);
        persisted.setConfiguredFolderPath(tempDir.toString());
        persisted.setConfiguredFileName("SAP_Inventory.xlsx");
        persisted.setConfiguredFilePath(tempDir.resolve("SAP_Inventory.xlsx").toString());
        persisted.setImportFrequencyHours(24);
        persisted.setImportMode("REPLACE_IN_SCOPE");
        persisted.setLastExecutionAt(OffsetDateTime.now().minusHours(25));
        persisted.setNextExecutionAt(OffsetDateTime.now().minusHours(1)); // past due during server downtime
        persisted.setLastImportStatus("WAITING_FOR_NEXT_IMPORT");
        configStore.put(1L, persisted);

        // Simulate application restart
        automaticImportService.recoverSchedulesOnStartup();

        AutomaticImportStatusDto status = automaticImportService.getStatus(1L);
        assertThat(status.enabled()).isTrue();
        assertThat(status.nextExecutionAt()).isAfter(OffsetDateTime.now());
    }

    // 6. Prevent overlapping imports
    @Test
    @DisplayName("6. Prevent overlapping concurrent imports with lock")
    void preventOverlappingImports() throws Exception {
        Path file = writeSapExcelFile("SAP_Inventory.xlsx", List.of(
                sapRow("RM-101", "Lactose IP", "1710", "RM01", "ZRAW", "B001", "100", "25000", "2026-06-01", "2027-06-01")
        ));
        automaticImportService.saveConfig(new AutomaticImportConfigRequest(
                1L, tempDir.toString(), "SAP_Inventory.xlsx", file.toString(), 24, "REPLACE_IN_SCOPE", true, false, null, null));

        // Simulate an active DB lock held by another running job
        AutomaticImportConfiguration lockedCfg = configStore.get(1L);
        lockedCfg.setLockAcquiredAt(OffsetDateTime.now());
        lockedCfg.setLockedBy("other-instance");
        configStore.put(1L, lockedCfg);

        assertThatThrownBy(() -> automaticImportService.runNow(1L, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lock");
    }

    // 7. Reject invalid Excel files & verify failed imports preserve previous inventory, actions, and snapshots
    @Test
    @DisplayName("7. Reject invalid Excel data and preserve existing inventory, action records, and historical snapshots")
    void rejectInvalidExcelAndPreserveExistingInventoryActionsAndSnapshots() throws Exception {
        // First load valid initial inventory
        Path file = writeSapExcelFile("SAP_Inventory.xlsx", List.of(
                sapRow("RM-100", "Initial API Material", "1710", "RM01", "ZRAW", "B01", "100", "50000", "2026-05-01", "2027-05-01")
        ));
        automaticImportService.saveConfig(new AutomaticImportConfigRequest(
                1L, tempDir.toString(), "SAP_Inventory.xlsx", file.toString(), 24, "REPLACE_IN_SCOPE", true, false, null, null));
        automaticImportService.runNow(1L, true);
        assertThat(inventoryStore.values()).hasSize(1);

        // Now write an invalid Excel file (unknown Plant '9999-UNKNOWN' and missing Material Code)
        writeSapExcelFile("SAP_Inventory.xlsx", List.of(
                sapRow("", "Missing Code Row", "9999-UNKNOWN", "RM01", "ZRAW", "B99", "100", "10000", "2026-05-01", "2027-05-01")
        ));

        assertThatThrownBy(() -> automaticImportService.runNow(1L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Validation failed — existing inventory preserved");

        // Verify previous inventory, action items, and snapshots remain completely intact
        assertThat(inventoryStore.values()).hasSize(1);
        assertThat(inventoryStore.values().iterator().next().getCode()).isEqualTo("RM-100");
        assertThat(actionStore).containsKey("ACT-0001");
        assertThat(snapshotStore).containsKey(1L);

        // Verify failure was recorded in ImportHistory and status updated to FAILED
        AutomaticImportStatusDto status = automaticImportService.getStatus(1L);
        assertThat(status.status()).isEqualTo("FAILED");
        assertThat(status.lastImportMessage()).contains("Validation failed");
        assertThat(historyStore.values()).anyMatch(h -> "FAILED".equals(h.getStatus()));
    }

    // 8. Verify authorized scope restrictions (REPLACE_IN_SCOPE only replaces records within scope)
    @Test
    @DisplayName("8. Verify REPLACE_IN_SCOPE only replaces inventory within the authorized plant scope and preserves other plants")
    void verifyAuthorizedScopeRestrictions() throws Exception {
        // Seed initial inventory across two plants: PITHAMPUR-API and Baddi-1
        InventoryItem pithampurItem = new InventoryItem();
        pithampurItem.setId(10L);
        pithampurItem.setBuCode("API");
        pithampurItem.setPlant("PITHAMPUR-API");
        pithampurItem.setMatType("Raw Material");
        pithampurItem.setCode("RM-OLD-API");
        pithampurItem.setName("Old API Material");
        pithampurItem.setBatch("B-OLD-1");
        pithampurItem.setUnrestrictedQty(BigDecimal.valueOf(50));
        pithampurItem.setUnitCost(BigDecimal.valueOf(100));
        inventoryStore.put(10L, pithampurItem);

        InventoryItem baddiItem = new InventoryItem();
        baddiItem.setId(11L);
        baddiItem.setBuCode("Formulation");
        baddiItem.setPlant("Baddi-1");
        baddiItem.setMatType("Finished Goods");
        baddiItem.setCode("FG-BADDI-KEEP");
        baddiItem.setName("Baddi Finished Good");
        baddiItem.setBatch("B-KEEP-1");
        baddiItem.setUnrestrictedQty(BigDecimal.valueOf(200));
        baddiItem.setUnitCost(BigDecimal.valueOf(300));
        inventoryStore.put(11L, baddiItem);

        // Configure automatic import scoped ONLY to PITHAMPUR-API
        Path file = writeSapExcelFile("SAP_Inventory.xlsx", List.of(
                sapRow("RM-NEW-API", "New Pithampur Material", "1710", "RM01", "ZRAW", "B-NEW-1", "120", "36000", "2026-08-01", "2027-08-01")
        ));
        automaticImportService.saveConfig(new AutomaticImportConfigRequest(
                1L, tempDir.toString(), "SAP_Inventory.xlsx", file.toString(), 24, "REPLACE_IN_SCOPE", true, false, "API", "PITHAMPUR-API"));

        ImportHistoryDto res = automaticImportService.runNow(1L, true);
        assertThat(res.status()).isEqualTo("SUCCESS");

        // PITHAMPUR-API old item was replaced by RM-NEW-API, while Baddi-1 item (FG-BADDI-KEEP) was preserved!
        assertThat(inventoryStore.values()).extracting(InventoryItem::getCode)
                .containsExactlyInAnyOrder("FG-BADDI-KEEP", "RM-NEW-API");
    }

    // 9. Verify REST endpoints for run-now, status, history list, and history details
    @Test
    @DisplayName("9. Verify /run-now, /status, /history, and /history/{id} REST API endpoints")
    void verifyRestEndpointsForRunNowStatusAndHistory() throws Exception {
        Path file = writeSapExcelFile("SAP_Inventory.xlsx", List.of(
                sapRow("RM-501", "API Material 501", "1710", "RM01", "ZRAW", "B501", "80", "16000", "2026-09-01", "2027-09-01")
        ));
        automaticImportService.saveConfig(new AutomaticImportConfigRequest(
                1L, tempDir.toString(), "SAP_Inventory.xlsx", file.toString(), 24, "REPLACE_IN_SCOPE", true, false, null, null));

        mockMvc.perform(post("/api/automatic-import/run-now"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.importType").value("MANUAL"))
                .andExpect(jsonPath("$.addedRecords").value(1));

        mockMvc.perform(get("/api/automatic-import/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_NEXT_IMPORT"))
                .andExpect(jsonPath("$.configuredFileName").value("SAP_Inventory.xlsx"))
                .andExpect(jsonPath("$.lastSuccessfulImportAt").isNotEmpty());

        mockMvc.perform(get("/api/automatic-import/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].fileName").value("SAP_Inventory.xlsx"));

        Long historyId = historyStore.keySet().iterator().next();
        mockMvc.perform(get("/api/automatic-import/history/" + historyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(historyId))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.successfulRecords").value(1));
    }
}
