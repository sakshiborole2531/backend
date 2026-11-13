package com.unichem.inventory.web;

import com.unichem.inventory.domain.*;
import com.unichem.inventory.dto.Dtos.*;
import com.unichem.inventory.repo.*;
import com.unichem.inventory.service.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class MasterSidebarTest {

    @Mock private BusinessUnitRepository bus;
    @Mock private PlantRepository plants;
    @Mock private StorageLocationRepository slocs;
    @Mock private MaterialTypeMapRepository mtypes;
    @Mock private MaterialGroupMapRepository mgroups;
    @Mock private ResponsibleFunctionRepository functions;
    @Mock private ActionTypeRepository actionTypes;
    @Mock private ResponsiblePersonRepository persons;
    @Mock private AppUserRepository users;
    @Mock private AppRoleRepository roles;
    @Mock private SyncLogRepository syncLog;
    @Mock private SettingsService settings;
    @Mock private EntityManager em;
    @Mock private AuditService audit;

    private CurrentUserService currentUserService;
    private MasterService masterService;
    private MasterController masterController;
    private MockMvc mockMvc;

    // In-memory store backing the mocked repositories to simulate dynamic database commits
    private final Map<Long, Plant> plantStore = new LinkedHashMap<>();
    private final Map<String, BusinessUnit> buStore = new LinkedHashMap<>();
    private final Map<Long, StorageLocation> slocStore = new LinkedHashMap<>();
    private final Map<Long, MaterialTypeMap> mtypeStore = new LinkedHashMap<>();
    private final Map<String, AppUser> userStore = new LinkedHashMap<>();
    private long nextPlantId = 1L;
    private long nextSlocId = 1L;
    private long nextMtypeId = 1L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        plantStore.clear();
        buStore.clear();
        slocStore.clear();
        mtypeStore.clear();
        userStore.clear();
        nextPlantId = 1L;
        nextSlocId = 1L;
        nextMtypeId = 1L;

        currentUserService = new CurrentUserService(users, roles, plants);
        masterService = new MasterService(bus, plants, slocs, mtypes, mgroups, functions, actionTypes, persons, currentUserService, em, audit);
        masterController = new MasterController(masterService, settings, currentUserService, syncLog);
        mockMvc = MockMvcBuilders.standaloneSetup(masterController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        // Default repository behavior backed by in-memory maps
        lenient().when(plants.findAll()).thenAnswer(inv -> new ArrayList<>(plantStore.values()));
        lenient().when(plants.findAllByOrderBySortOrderAscNameAsc()).thenAnswer(inv ->
                plantStore.values().stream()
                        .sorted(Comparator.comparingInt(Plant::getSortOrder).thenComparing(Plant::getName))
                        .toList());
        lenient().when(plants.findByActiveTrueOrderByOrderAsc()).thenAnswer(inv ->
                plantStore.values().stream()
                        .filter(Plant::isActive)
                        .sorted(Comparator.comparingInt(Plant::getSortOrder).thenComparing(Plant::getName))
                        .toList());
        lenient().when(plants.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(plantStore.get(inv.<Long>getArgument(0))));
        lenient().when(plants.save(any(Plant.class))).thenAnswer(inv -> {
            Plant p = inv.getArgument(0);
            if (p.getId() == null) p.setId(nextPlantId++);
            plantStore.put(p.getId(), p);
            return p;
        });

        lenient().when(bus.findAll()).thenAnswer(inv -> new ArrayList<>(buStore.values()));
        lenient().when(bus.existsById(anyString())).thenAnswer(inv -> buStore.containsKey(inv.<String>getArgument(0)));
        lenient().when(bus.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(buStore.get(inv.<String>getArgument(0))));
        lenient().when(bus.findByActiveTrueOrderByOrderAsc()).thenAnswer(inv ->
                buStore.values().stream()
                        .filter(BusinessUnit::isActive)
                        .sorted(Comparator.comparingInt(BusinessUnit::getSortOrder).thenComparing(BusinessUnit::getCode))
                        .toList());
        lenient().when(bus.save(any(BusinessUnit.class))).thenAnswer(inv -> {
            BusinessUnit b = inv.getArgument(0);
            buStore.put(b.getCode(), b);
            return b;
        });

        lenient().when(slocs.findByActiveTrueOrderByOrderAsc()).thenAnswer(inv ->
                slocStore.values().stream()
                        .filter(StorageLocation::isActive)
                        .sorted(Comparator.comparingInt(StorageLocation::getSortOrder).thenComparing(StorageLocation::getCode))
                        .toList());
        lenient().when(slocs.save(any(StorageLocation.class))).thenAnswer(inv -> {
            StorageLocation s = inv.getArgument(0);
            if (s.getId() == null) s.setId(nextSlocId++);
            slocStore.put(s.getId(), s);
            return s;
        });

        lenient().when(mtypes.findAll()).thenAnswer(inv -> new ArrayList<>(mtypeStore.values()));
        lenient().when(mtypes.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(mtypeStore.get(inv.<Long>getArgument(0))));
        lenient().when(mtypes.findBySapCodeIgnoreCase(anyString())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            return mtypeStore.values().stream().filter(m -> m.getSapCode().equalsIgnoreCase(code)).findFirst();
        });
        lenient().when(mtypes.findAllBySapCodeIgnoreCase(anyString())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            return mtypeStore.values().stream().filter(m -> m.getSapCode().equalsIgnoreCase(code)).toList();
        });
        lenient().when(mtypes.findByCompositeKey(anyString(), anyString(), any())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            String cat = inv.getArgument(1);
            String bu = inv.getArgument(2);
            return mtypeStore.values().stream()
                    .filter(m -> m.getSapCode().equalsIgnoreCase(code)
                            && m.getCategory().equalsIgnoreCase(cat)
                            && ((bu == null && (m.getBusinessUnit() == null || m.getBusinessUnit().isBlank()))
                                || (bu != null && m.getBusinessUnit() != null && m.getBusinessUnit().equalsIgnoreCase(bu))))
                    .findFirst();
        });
        lenient().when(mtypes.findByActiveTrueOrderByOrderAsc()).thenAnswer(inv ->
                mtypeStore.values().stream()
                        .filter(MaterialTypeMap::isActive)
                        .sorted(Comparator.comparingInt(MaterialTypeMap::getSortOrder).thenComparing(MaterialTypeMap::getSapCode))
                        .toList());
        lenient().when(mtypes.save(any(MaterialTypeMap.class))).thenAnswer(inv -> {
            MaterialTypeMap m = inv.getArgument(0);
            if (m.getId() == null) m.setId(nextMtypeId++);
            mtypeStore.put(m.getId(), m);
            return m;
        });

        lenient().when(users.findByUsernameIgnoreCase(anyString())).thenAnswer(inv -> {
            String u = inv.getArgument(0);
            return Optional.ofNullable(userStore.get(u.toLowerCase()));
        });

        Query mockQuery = mock(Query.class);
        lenient().when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
        lenient().when(mockQuery.executeUpdate()).thenReturn(1);
        lenient().when(em.createNativeQuery(anyString())).thenReturn(mockQuery);

        // Sign in as admin by default
        authenticateAsAdmin();
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
                new UsernamePasswordAuthenticationToken("admin", null, List.of(new SimpleGrantedAuthority("masters.edit"))));
    }

    private Plant createPlant(String sapCode, String name, String buCode, boolean active, int order) {
        Plant p = new Plant();
        p.setSapCode(sapCode);
        p.setName(name);
        p.setBuCode(buCode);
        p.setCity("TestCity");
        p.setActive(active);
        p.setSortOrder(order);
        return p;
    }

    // 1. Active Plant is returned.
    @Test
    @DisplayName("1. Active Plant is returned in /api/masters/sidebar")
    void activePlantIsReturned() throws Exception {
        buStore.put("FORMULATION", new BusinessUnit("FORMULATION", "Formulation", "OSD", true, 1));
        Plant p = createPlant("1100", "Baddi-1", "FORMULATION", true, 3);
        plants.save(p);

        mockMvc.perform(get("/api/masters/sidebar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plants.length()").value(1))
                .andExpect(jsonPath("$.plants[0].id").value(p.getId()))
                .andExpect(jsonPath("$.plants[0].code").value("1100"))
                .andExpect(jsonPath("$.plants[0].name").value("Baddi-1"))
                .andExpect(jsonPath("$.plants[0].active").value(true))
                .andExpect(jsonPath("$.plants[0].order").value(3))
                .andExpect(jsonPath("$.plants[0].buCode").value("FORMULATION"));
    }

    // 2. Inactive Plant is not returned.
    @Test
    @DisplayName("2. Inactive Plant is not returned in /api/masters/sidebar")
    void inactivePlantIsNotReturned() throws Exception {
        buStore.put("FORMULATION", new BusinessUnit("FORMULATION", "Formulation", "OSD", true, 1));
        buStore.put("CORPORATE", new BusinessUnit("CORPORATE", "Corporate", "HO", true, 2));
        plants.save(createPlant("1100", "Baddi-1", "FORMULATION", true, 1));
        plants.save(createPlant("1000", "Corporate", "CORPORATE", false, 2));

        SidebarResponseDto res = masterService.sidebar();
        assertThat(res.plants()).hasSize(1);
        assertThat(res.plants().get(0).name()).isEqualTo("Baddi-1");
        assertThat(res.plants()).noneMatch(p -> "Corporate".equals(p.name()) || !p.active());

        // Deactivating an active plant removes it from the sidebar without deleting it from plantStore
        Plant baddi = plantStore.get(1L);
        baddi.setActive(false);
        masterService.savePlant(baddi);

        SidebarResponseDto afterDeactivate = masterService.sidebar();
        assertThat(afterDeactivate.plants()).isEmpty();
        assertThat(plantStore).containsKey(1L); // historical record remains in DB
    }

    // 3. Newly added Plant appears.
    @Test
    @DisplayName("3. Newly added Plant appears immediately in sidebar after save")
    void newlyAddedPlantAppears() {
        buStore.put("API", new BusinessUnit("API", "Active Pharmaceutical Ingredients", null, true, 1));
        assertThat(masterService.sidebar().plants()).isEmpty();

        Plant newPlant = createPlant("1700", "ROHA-API", "API", true, 0);
        masterService.savePlant(newPlant);

        SidebarResponseDto res = masterService.sidebar();
        assertThat(res.plants()).hasSize(1);
        assertThat(res.plants().get(0).code()).isEqualTo("1700");
        assertThat(res.plants().get(0).name()).isEqualTo("ROHA-API");
        assertThat(res.plants().get(0).active()).isTrue();
    }

    // 4. Renamed Plant returns the new name.
    @Test
    @DisplayName("4. Renamed Plant returns the new name immediately in sidebar")
    void renamedPlantReturnsNewName() {
        buStore.put("FORMULATION", new BusinessUnit("FORMULATION", "Formulation", "OSD", true, 1));
        Plant saved = masterService.savePlant(createPlant("1100", "Baddi-1", "FORMULATION", true, 3));
        assertThat(masterService.sidebar().plants().get(0).name()).isEqualTo("Baddi-1");

        Plant updateReq = createPlant("1100", "Baddi-Unit-1", "FORMULATION", true, 3);
        updateReq.setId(saved.getId());
        masterService.savePlant(updateReq);

        SidebarResponseDto res = masterService.sidebar();
        assertThat(res.plants()).hasSize(1);
        assertThat(res.plants().get(0).id()).isEqualTo(saved.getId());
        assertThat(res.plants().get(0).name()).isEqualTo("Baddi-Unit-1");
    }

    // 5. Active Business Unit is returned (and inactive is excluded).
    @Test
    @DisplayName("5. Active Business Unit is returned and inactive is excluded")
    void activeBusinessUnitIsReturned() throws Exception {
        masterService.saveBu(new BusinessUnit("FORMULATION", "Formulation", "OSD", true, 1));
        masterService.saveBu(new BusinessUnit("INACTIVE_BU", "Legacy Division", null, false, 2));

        mockMvc.perform(get("/api/masters/sidebar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessUnits.length()").value(1))
                .andExpect(jsonPath("$.businessUnits[0].id").value("FORMULATION"))
                .andExpect(jsonPath("$.businessUnits[0].code").value("FORMULATION"))
                .andExpect(jsonPath("$.businessUnits[0].name").value("Formulation"))
                .andExpect(jsonPath("$.businessUnits[0].active").value(true))
                .andExpect(jsonPath("$.businessUnits[0].order").value(1));
    }

    // 6. Active Storage Location is returned (and inactive / inactive-plant locations are excluded).
    @Test
    @DisplayName("6. Active Storage Location is returned with plant relationship and inactive is excluded")
    void activeStorageLocationIsReturned() throws Exception {
        buStore.put("FORMULATION", new BusinessUnit("FORMULATION", "Formulation", "OSD", true, 1));
        plants.save(createPlant("1100", "Baddi-1", "FORMULATION", true, 1));
        plants.save(createPlant("1000", "Corporate", "FORMULATION", false, 2));

        StorageLocation activeSloc = new StorageLocation();
        activeSloc.setCode("0001");
        activeSloc.setName("Main Store");
        activeSloc.setPlantName("Baddi-1");
        activeSloc.setStorageType("General");
        activeSloc.setActive(true);
        activeSloc.setSortOrder(1);
        masterService.saveSloc(activeSloc);

        StorageLocation inactiveSloc = new StorageLocation();
        inactiveSloc.setCode("9999");
        inactiveSloc.setName("Closed Store");
        inactiveSloc.setPlantName("Baddi-1");
        inactiveSloc.setActive(false);
        inactiveSloc.setSortOrder(2);
        masterService.saveSloc(inactiveSloc);

        StorageLocation inactivePlantSloc = new StorageLocation();
        inactivePlantSloc.setCode("0002");
        inactivePlantSloc.setName("Corp Store");
        inactivePlantSloc.setPlantName("Corporate");
        inactivePlantSloc.setActive(true);
        inactivePlantSloc.setSortOrder(3);
        masterService.saveSloc(inactivePlantSloc);

        mockMvc.perform(get("/api/masters/sidebar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storageLocations.length()").value(1))
                .andExpect(jsonPath("$.storageLocations[0].code").value("0001"))
                .andExpect(jsonPath("$.storageLocations[0].name").value("Main Store"))
                .andExpect(jsonPath("$.storageLocations[0].plantCode").value("1100"))
                .andExpect(jsonPath("$.storageLocations[0].plantName").value("Baddi-1"))
                .andExpect(jsonPath("$.storageLocations[0].active").value(true))
                .andExpect(jsonPath("$.storageLocations[0].order").value(1));
    }

    // 7. Active Material Type is returned (and inactive is excluded).
    @Test
    @DisplayName("7. Active Material Type is returned and inactive is excluded")
    void activeMaterialTypeIsReturned() throws Exception {
        MaterialTypeMap roh = new MaterialTypeMap();
        roh.setSapCode("ROH");
        roh.setDescription("Raw Material");
        roh.setCategory("Raw Material");
        roh.setActive(true);
        roh.setSortOrder(1);
        masterService.saveMtype(roh);

        MaterialTypeMap inactiveMt = new MaterialTypeMap();
        inactiveMt.setSapCode("OLD");
        inactiveMt.setDescription("Obsolete Material");
        inactiveMt.setCategory("Exclude");
        inactiveMt.setActive(false);
        inactiveMt.setSortOrder(2);
        masterService.saveMtype(inactiveMt);

        mockMvc.perform(get("/api/masters/sidebar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materialTypes.length()").value(1))
                .andExpect(jsonPath("$.materialTypes[0].id").value("ROH"))
                .andExpect(jsonPath("$.materialTypes[0].code").value("ROH"))
                .andExpect(jsonPath("$.materialTypes[0].name").value("Raw Material"))
                .andExpect(jsonPath("$.materialTypes[0].active").value(true))
                .andExpect(jsonPath("$.materialTypes[0].order").value(1));
    }

    // 8. Empty master table returns [] instead of null.
    @Test
    @DisplayName("8. Empty master tables return HTTP 200 with empty arrays [] instead of null")
    void emptyMasterTablesReturnEmptyArrays() throws Exception {
        mockMvc.perform(get("/api/masters/sidebar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plants").isArray())
                .andExpect(jsonPath("$.plants").isEmpty())
                .andExpect(jsonPath("$.businessUnits").isArray())
                .andExpect(jsonPath("$.businessUnits").isEmpty())
                .andExpect(jsonPath("$.storageLocations").isArray())
                .andExpect(jsonPath("$.storageLocations").isEmpty())
                .andExpect(jsonPath("$.materialTypes").isArray())
                .andExpect(jsonPath("$.materialTypes").isEmpty());
    }

    // 9. Unauthorized user cannot access unauthorized master data.
    @Test
    @DisplayName("9. Scoped/unauthorized user only sees authorized master data and unauthenticated user is rejected")
    void unauthorizedUserCannotAccessUnauthorizedMasterData() {
        buStore.put("API", new BusinessUnit("API", "Active Pharmaceutical Ingredients", null, true, 1));
        buStore.put("FORMULATION", new BusinessUnit("FORMULATION", "Formulation", "OSD", true, 2));

        plants.save(createPlant("1700", "ROHA-API", "API", true, 1));
        plants.save(createPlant("1100", "Baddi-1", "FORMULATION", true, 2));
        plants.save(createPlant("1200", "GOA-1", "FORMULATION", true, 3));

        StorageLocation rohaSloc = new StorageLocation();
        rohaSloc.setCode("RM01"); rohaSloc.setName("Roha RM Store"); rohaSloc.setPlantName("ROHA-API"); rohaSloc.setActive(true); rohaSloc.setSortOrder(1);
        slocs.save(rohaSloc);

        StorageLocation baddiSloc = new StorageLocation();
        baddiSloc.setCode("FG01"); baddiSloc.setName("Baddi FG Store"); baddiSloc.setPlantName("Baddi-1"); baddiSloc.setActive(true); baddiSloc.setSortOrder(2);
        slocs.save(baddiSloc);

        // User restricted to Baddi-1 only
        AppUser plantUser = new AppUser();
        plantUser.setId(2L);
        plantUser.setUsername("baddi.user");
        plantUser.setName("Baddi Store User");
        plantUser.setRoleCode("store");
        plantUser.setScopeAll(false);
        plantUser.setScopeUnits(Set.of("Baddi-1"));
        plantUser.setActive(true);
        userStore.put("baddi.user", plantUser);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("baddi.user", null, List.of(new SimpleGrantedAuthority("view.inventory"))));

        SidebarResponseDto scopedRes = masterService.sidebar();
        // Should only see Baddi-1 plant, FORMULATION BU, and Baddi-1 storage location
        assertThat(scopedRes.plants()).extracting(PlantSidebarDto::name).containsExactly("Baddi-1");
        assertThat(scopedRes.businessUnits()).extracting(BusinessUnitSidebarDto::code).containsExactly("FORMULATION");
        assertThat(scopedRes.storageLocations()).extracting(StorageLocationSidebarDto::code).containsExactly("FG01");

        // User with a username not in DB / deactivated account is rejected with AccessDeniedException when requiring auth
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("unknown.user", null, List.of()));
        assertThatThrownBy(() -> masterService.sidebar()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("10. Allow same Material Type code with different Dashboard Category / Business Unit")
    void allowSameMaterialTypeCodeWithDifferentCategoryOrBu() {
        buStore.put("FORMULATION", new BusinessUnit("FORMULATION", "Formulation", null, true, 1));
        buStore.put("API", new BusinessUnit("API", "API BU", null, true, 2));

        MaterialTypeMap mt1 = new MaterialTypeMap();
        mt1.setSapCode("ZAPI");
        mt1.setDescription("API in Formulation");
        mt1.setCategory("Raw Material");
        mt1.setBusinessUnit("FORMULATION");
        mt1.setActive(true);
        masterService.saveMtype(mt1);

        // Same material type code ZAPI, but different BU "API" - allowed!
        MaterialTypeMap mt2 = new MaterialTypeMap();
        mt2.setSapCode("ZAPI");
        mt2.setDescription("API in API BU");
        mt2.setCategory("Raw Material");
        mt2.setBusinessUnit("API");
        mt2.setActive(true);

        MaterialTypeMap saved = masterService.saveMtype(mt2);
        assertThat(saved).isNotNull();
        assertThat(saved.getSapCode()).isEqualTo("ZAPI");
        assertThat(saved.getBusinessUnit()).isEqualTo("API");
    }

    @Test
    @DisplayName("11. Allow updating existing Material Type without self-duplicate error")
    void allowUpdatingExistingMaterialType() {
        MaterialTypeMap mt1 = new MaterialTypeMap();
        mt1.setSapCode("ROH");
        mt1.setDescription("Raw Material");
        mt1.setCategory("Raw Material");
        mt1.setActive(true);
        masterService.saveMtype(mt1);

        mt1.setDescription("Updated Description");
        MaterialTypeMap updated = masterService.updateMtype("ROH", mt1);
        assertThat(updated.getDescription()).isEqualTo("Updated Description");
    }

    @Test
    @DisplayName("12. Reject duplicate composite (Material Type + Category + BU) in batch payload")
    void rejectDuplicateCompositeInBatch() {
        buStore.put("API", new BusinessUnit("API", "API BU", null, true, 1));

        MaterialTypeMap m1 = new MaterialTypeMap();
        m1.setSapCode("FERT");
        m1.setCategory("Finished Goods");
        m1.setBusinessUnit("API");
        m1.setActive(true);

        MaterialTypeMap m2 = new MaterialTypeMap();
        m2.setSapCode("FERT");
        m2.setCategory("Finished Goods");
        m2.setBusinessUnit("API");
        m2.setActive(true);

        assertThatThrownBy(() -> masterService.saveAllMtypes(List.of(m1, m2)))
                .isInstanceOf(DuplicateMaterialTypeMappingException.class)
                .hasMessage("Duplicate entry found: The combination of Material Type, Dashboard Category, and Business Unit must be unique.");
    }

    @Test
    @DisplayName("13. Reject duplicate Material Type + Dashboard Category + Business Unit combination against DB")
    void rejectDuplicateMaterialTypeCategoryAndBusinessUnitCombination() {
        buStore.put("API", new BusinessUnit("API", "API BU", null, true, 1));

        MaterialTypeMap mt1 = new MaterialTypeMap();
        mt1.setSapCode("ZAPI");
        mt1.setCategory("Raw Material");
        mt1.setBusinessUnit("API");
        mt1.setActive(true);
        masterService.saveMtype(mt1);

        MaterialTypeMap mt2 = new MaterialTypeMap();
        mt2.setSapCode("ZAPI");
        mt2.setCategory("Raw Material");
        mt2.setBusinessUnit("API");
        mt2.setActive(true);

        assertThatThrownBy(() -> masterService.saveMtype(mt2))
                .isInstanceOf(DuplicateMaterialTypeMappingException.class)
                .hasMessage("Duplicate entry found: The combination of Material Type, Dashboard Category, and Business Unit must be unique.");
    }
}
