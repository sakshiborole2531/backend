package com.unichem.inventory.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class BusinessUnitControllerTest {

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
    @Mock private SettingsService settings;
    @Mock private EntityManager em;
    @Mock private AuditService audit;

    private CurrentUserService currentUserService;
    private MasterService masterService;
    private BusinessUnitController controller;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private final Map<String, BusinessUnit> buStore = new LinkedHashMap<>();
    private final Map<Long, Plant> plantStore = new LinkedHashMap<>();
    private final Map<String, AppUser> userStore = new LinkedHashMap<>();
    private long nextBuId = 1L;
    private long nextPlantId = 101L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        buStore.clear();
        plantStore.clear();
        userStore.clear();
        nextBuId = 1L;
        nextPlantId = 101L;

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        currentUserService = new CurrentUserService(users, roles, plants);
        masterService = new MasterService(bus, plants, slocs, mtypes, mgroups, functions, actionTypes, persons, currentUserService, em, audit);
        controller = new BusinessUnitController(masterService, settings);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        // Repository mocks backed by in-memory store
        lenient().when(bus.findAll()).thenAnswer(inv -> new ArrayList<>(buStore.values()));
        lenient().when(bus.findAllByOrderBySortOrderAscCodeAsc()).thenAnswer(inv ->
                buStore.values().stream()
                        .sorted(Comparator.comparingInt(BusinessUnit::getSortOrder).thenComparing(BusinessUnit::getCode))
                        .toList());
        lenient().when(bus.findByActiveTrueOrderByOrderAsc()).thenAnswer(inv ->
                buStore.values().stream()
                        .filter(BusinessUnit::isActive)
                        .sorted(Comparator.comparingInt(BusinessUnit::getSortOrder).thenComparing(BusinessUnit::getCode))
                        .toList());
        lenient().when(bus.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(buStore.get(inv.<String>getArgument(0))));
        lenient().when(bus.existsById(anyString())).thenAnswer(inv -> buStore.containsKey(inv.<String>getArgument(0)));
        lenient().when(bus.findByUnitId(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return buStore.values().stream().filter(b -> Objects.equals(b.getId(), id)).findFirst();
        });
        lenient().when(bus.findByCodeIgnoreCase(anyString())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            return buStore.values().stream().filter(b -> b.getCode() != null && b.getCode().equalsIgnoreCase(code)).findFirst();
        });
        lenient().when(bus.findMaxId()).thenAnswer(inv ->
                buStore.values().stream().map(BusinessUnit::getId).filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L));
        lenient().when(bus.save(any(BusinessUnit.class))).thenAnswer(inv -> {
            BusinessUnit b = inv.getArgument(0);
            if (b.getId() == null) b.setId(nextBuId++);
            else if (b.getId() >= nextBuId) nextBuId = b.getId() + 1;
            buStore.put(b.getCode(), b);
            return b;
        });
        lenient().doAnswer(inv -> {
            String code = inv.getArgument(0);
            buStore.remove(code);
            return null;
        }).when(bus).deleteById(anyString());

        lenient().when(plants.findAll()).thenAnswer(inv -> new ArrayList<>(plantStore.values()));
        lenient().when(plants.findByActiveTrueOrderByOrderAsc()).thenAnswer(inv ->
                plantStore.values().stream()
                        .filter(Plant::isActive)
                        .sorted(Comparator.comparingInt(Plant::getSortOrder).thenComparing(Plant::getName))
                        .toList());
        lenient().when(plants.findByBusinessUnitIdOrderBySortOrderAscNameAsc(anyLong())).thenAnswer(inv -> {
            Long buId = inv.getArgument(0);
            return plantStore.values().stream()
                    .filter(p -> Objects.equals(p.getBusinessUnitId(), buId))
                    .sorted(Comparator.comparingInt(Plant::getSortOrder).thenComparing(Plant::getName))
                    .toList();
        });
        lenient().when(plants.existsByBusinessUnitId(anyLong())).thenAnswer(inv -> {
            Long buId = inv.getArgument(0);
            return plantStore.values().stream().anyMatch(p -> Objects.equals(p.getBusinessUnitId(), buId));
        });
        lenient().when(plants.save(any(Plant.class))).thenAnswer(inv -> {
            Plant p = inv.getArgument(0);
            if (p.getId() == null) p.setId(nextPlantId++);
            plantStore.put(p.getId(), p);
            return p;
        });

        lenient().when(settings.get(eq("companyShort"), anyString())).thenReturn("Unichem");
        lenient().when(users.findByUsernameIgnoreCase(anyString())).thenAnswer(inv ->
                Optional.ofNullable(userStore.get(inv.<String>getArgument(0).toLowerCase())));

        Query mockQuery = mock(Query.class);
        lenient().when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
        lenient().when(mockQuery.executeUpdate()).thenReturn(1);
        lenient().when(em.createNativeQuery(anyString())).thenReturn(mockQuery);

        authenticateAsAdmin();
        seedExistingApiAndFormulation();
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

    private void seedExistingApiAndFormulation() {
        BusinessUnit api = new BusinessUnit(1L, "API", "Active Pharmaceutical Ingredients", "API", true, 1);
        BusinessUnit formulation = new BusinessUnit(2L, "Formulation", "Formulation", "OSD", true, 2);
        bus.save(api);
        bus.save(formulation);

        addPlant(101L, "1710", "PITHAMPUR-API", "API", 1L, "Pithampur", true, 1);
        addPlant(102L, "1720", "KOLHAPUR-API", "API", 1L, "Kolhapur", true, 2);
        addPlant(103L, "1100", "Baddi-1", "Formulation", 2L, "Baddi", true, 3);
    }

    private Plant addPlant(Long id, String sapCode, String name, String buCode, Long buId, String city, boolean active, int order) {
        Plant p = new Plant(id, sapCode, name, buCode, buId, city, active, order);
        return plants.save(p);
    }

    // 1. Retrieve all Business Units
    @Test
    @DisplayName("1. Retrieve all Business Units via GET /api/business-units")
    void retrieveAllBusinessUnits() throws Exception {
        mockMvc.perform(get("/api/business-units"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("API"))
                .andExpect(jsonPath("$.data[0].description").value("Active Pharmaceutical Ingredients"))
                .andExpect(jsonPath("$.data[0].alsoKnownAs").value("API"))
                .andExpect(jsonPath("$.data[0].active").value(true))
                .andExpect(jsonPath("$.data[1].id").value(2))
                .andExpect(jsonPath("$.data[1].name").value("Formulation"))
                .andExpect(jsonPath("$.data[1].description").value("Formulation"))
                .andExpect(jsonPath("$.data[1].alsoKnownAs").value("OSD"))
                .andExpect(jsonPath("$.data[1].active").value(true));
    }

    // 2. Create a new Business Unit
    @Test
    @DisplayName("2. Create a new Business Unit via POST /api/business-units returns 201 and generated ID")
    void createNewBusinessUnit() throws Exception {
        String body = """
                {
                  "name": "Quality Control",
                  "description": "Quality Control Department",
                  "alsoKnownAs": "QC",
                  "active": true
                }
                """;

        mockMvc.perform(post("/api/business-units")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.name").value("Quality Control"))
                .andExpect(jsonPath("$.description").value("Quality Control Department"))
                .andExpect(jsonPath("$.alsoKnownAs").value("QC"))
                .andExpect(jsonPath("$.active").value(true));

        assertThat(masterService.getAllBusinessUnits()).hasSize(3);
    }

    // 3. Reject duplicate Business Unit names
    @Test
    @DisplayName("3. Reject duplicate Business Unit names with HTTP 409 Conflict")
    void rejectDuplicateBusinessUnitNames() throws Exception {
        String duplicateBody = """
                {
                  "name": "API",
                  "description": "Duplicate API Division",
                  "alsoKnownAs": "API2",
                  "active": true
                }
                """;

        mockMvc.perform(post("/api/business-units")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Business Unit with name 'API' already exists"));
    }

    // 4. Update an existing Business Unit
    @Test
    @DisplayName("4. Update an existing Business Unit via PUT /api/business-units/{id} preserves ID and plants")
    void updateExistingBusinessUnit() throws Exception {
        BusinessUnitDto created = masterService.createBusinessUnit(
                new BusinessUnitRequest(null, 1L, "Quality Control", null, "Quality Control Department", "QC", null, true, 3));
        addPlant(104L, "1900", "QC-LAB-1", "Quality Control", created.id(), "Goa", true, 4);

        String updateBody = """
                {
                  "name": "Quality Assurance",
                  "description": "Quality Assurance Department",
                  "alsoKnownAs": "QA",
                  "active": true
                }
                """;

        mockMvc.perform(put("/api/business-units/" + created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.id()))
                .andExpect(jsonPath("$.name").value("Quality Assurance"))
                .andExpect(jsonPath("$.description").value("Quality Assurance Department"))
                .andExpect(jsonPath("$.alsoKnownAs").value("QA"))
                .andExpect(jsonPath("$.active").value(true));

        List<PlantDto> associatedPlants = masterService.getPlantsByBusinessUnit(created.id());
        assertThat(associatedPlants).hasSize(1);
        assertThat(associatedPlants.get(0).name()).isEqualTo("QC-LAB-1");
        assertThat(associatedPlants.get(0).businessUnitId()).isEqualTo(created.id());
        assertThat(associatedPlants.get(0).buCode()).isEqualTo("Quality Assurance");
    }

    // 5. Retrieve Business Units with associated Plants
    @Test
    @DisplayName("5. Retrieve Business Unit by ID and its associated Plants via GET /api/business-units/{id}/plants")
    void retrieveBusinessUnitWithAssociatedPlants() throws Exception {
        mockMvc.perform(get("/api/business-units/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("API"));

        mockMvc.perform(get("/api/business-units/1/plants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(101))
                .andExpect(jsonPath("$[0].name").value("PITHAMPUR-API"))
                .andExpect(jsonPath("$[0].businessUnitId").value(1))
                .andExpect(jsonPath("$[1].id").value(102))
                .andExpect(jsonPath("$[1].name").value("KOLHAPUR-API"))
                .andExpect(jsonPath("$[1].businessUnitId").value(1));
    }

    // 6. Retrieve Business Units with zero Plants
    @Test
    @DisplayName("6. Retrieve Business Unit with zero Plants in both /plants and /hierarchy endpoints")
    void retrieveBusinessUnitWithZeroPlants() throws Exception {
        BusinessUnitDto emptyBu = masterService.createBusinessUnit(
                new BusinessUnitRequest(null, 1L, "Biologics", null, "Biologics Division", "BIO", null, true, 3));

        mockMvc.perform(get("/api/business-units/" + emptyBu.id() + "/plants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/organizations/1/hierarchy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessUnits.length()").value(3))
                .andExpect(jsonPath("$.businessUnits[2].id").value(emptyBu.id()))
                .andExpect(jsonPath("$.businessUnits[2].name").value("Biologics"))
                .andExpect(jsonPath("$.businessUnits[2].plants").isArray())
                .andExpect(jsonPath("$.businessUnits[2].plants").isEmpty());
    }

    // 7. Delete a Business Unit without Plants
    @Test
    @DisplayName("7. Delete a Business Unit without Plants succeeds with HTTP 200")
    void deleteBusinessUnitWithoutPlants() throws Exception {
        BusinessUnitDto emptyBu = masterService.createBusinessUnit(
                new BusinessUnitRequest(null, 1L, "TempBU", null, "Temporary Business Unit", "TMP", null, true, 4));

        mockMvc.perform(delete("/api/business-units/" + emptyBu.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThatThrownBy(() -> masterService.getBusinessUnitById(emptyBu.id()))
                .isInstanceOf(NotFoundException.class);
    }

    // 8. Block deletion when Plants exist
    @Test
    @DisplayName("8. Block deletion of Business Unit with associated Plants returning HTTP 409 Conflict")
    void blockDeletionWhenPlantsExist() throws Exception {
        mockMvc.perform(delete("/api/business-units/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Cannot delete Business Unit 'API' because it has associated plants. Move or delete the plants first."));

        // Verify neither the Business Unit nor its Plants were deleted
        assertThat(masterService.getBusinessUnitById(1L)).isNotNull();
        assertThat(masterService.getPlantsByBusinessUnit(1L)).hasSize(2);
    }

    // 9. Verify existing API and Formulation records remain intact
    @Test
    @DisplayName("9. Verify existing API and Formulation records and their Plant relationships remain intact")
    void verifyExistingApiAndFormulationRecordsRemainIntact() {
        List<BusinessUnitDto> all = masterService.getAllBusinessUnits();
        assertThat(all).extracting(BusinessUnitDto::name).contains("API", "Formulation");

        BusinessUnitDto api = masterService.getBusinessUnitById(1L);
        assertThat(api.name()).isEqualTo("API");
        assertThat(api.description()).isEqualTo("Active Pharmaceutical Ingredients");
        assertThat(api.active()).isTrue();

        BusinessUnitDto formulation = masterService.getBusinessUnitById(2L);
        assertThat(formulation.name()).isEqualTo("Formulation");
        assertThat(formulation.alsoKnownAs()).isEqualTo("OSD");
        assertThat(formulation.active()).isTrue();

        List<PlantDto> apiPlants = masterService.getPlantsByBusinessUnit(1L);
        assertThat(apiPlants).extracting(PlantDto::name).containsExactly("PITHAMPUR-API", "KOLHAPUR-API");
        assertThat(apiPlants).allMatch(p -> Objects.equals(p.businessUnitId(), 1L));

        List<PlantDto> formPlants = masterService.getPlantsByBusinessUnit(2L);
        assertThat(formPlants).extracting(PlantDto::name).containsExactly("Baddi-1");
        assertThat(formPlants).allMatch(p -> Objects.equals(p.businessUnitId(), 2L));
    }

    // 10. Retrieve the complete Organization hierarchy
    @Test
    @DisplayName("10. Retrieve the complete Organization hierarchy via GET /api/organizations/{organizationId}/hierarchy")
    void retrieveCompleteOrganizationHierarchy() throws Exception {
        // Add a Business Unit with 0 plants to verify it is also included in the hierarchy
        masterService.createBusinessUnit(
                new BusinessUnitRequest(null, 1L, "R&D", null, "Research & Development", "RND", null, true, 3));

        mockMvc.perform(get("/api/organizations/1/hierarchy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(1))
                .andExpect(jsonPath("$.organizationName").value("Unichem"))
                .andExpect(jsonPath("$.businessUnits.length()").value(3))
                .andExpect(jsonPath("$.businessUnits[0].id").value(1))
                .andExpect(jsonPath("$.businessUnits[0].name").value("API"))
                .andExpect(jsonPath("$.businessUnits[0].plants.length()").value(2))
                .andExpect(jsonPath("$.businessUnits[0].plants[0].id").value(101))
                .andExpect(jsonPath("$.businessUnits[0].plants[0].name").value("PITHAMPUR-API"))
                .andExpect(jsonPath("$.businessUnits[0].plants[1].id").value(102))
                .andExpect(jsonPath("$.businessUnits[0].plants[1].name").value("KOLHAPUR-API"))
                .andExpect(jsonPath("$.businessUnits[1].id").value(2))
                .andExpect(jsonPath("$.businessUnits[1].name").value("Formulation"))
                .andExpect(jsonPath("$.businessUnits[1].plants.length()").value(1))
                .andExpect(jsonPath("$.businessUnits[1].plants[0].id").value(103))
                .andExpect(jsonPath("$.businessUnits[1].plants[0].name").value("Baddi-1"))
                .andExpect(jsonPath("$.businessUnits[2].id").value(3))
                .andExpect(jsonPath("$.businessUnits[2].name").value("R&D"))
                .andExpect(jsonPath("$.businessUnits[2].plants").isEmpty());
    }
}
