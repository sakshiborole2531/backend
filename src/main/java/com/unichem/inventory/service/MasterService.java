package com.unichem.inventory.service;

import com.unichem.inventory.domain.*;
import com.unichem.inventory.dto.Dtos.*;
import com.unichem.inventory.repo.*;
import com.unichem.inventory.web.NotFoundException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static com.unichem.inventory.domain.Constants.*;

/** Masters: business units, plants, storage locations, SAP mappings, functions, persons, action types. */
@Service
@RequiredArgsConstructor
public class MasterService {
    private final BusinessUnitRepository bus;
    private final PlantRepository plants;
    private final StorageLocationRepository slocs;
    private final MaterialTypeMapRepository mtypes;
    private final MaterialGroupMapRepository mgroups;
    private final ResponsibleFunctionRepository functions;
    private final ActionTypeRepository actionTypes;
    private final ResponsiblePersonRepository persons;
    private final CurrentUserService current;
    private final EntityManager em;
    private final AuditService audit;

    public Map<String, Object> all() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("businessUnits", bus.findAllByOrderBySortOrderAscCodeAsc());
        m.put("plants", plants.findAllByOrderBySortOrderAscNameAsc());
        m.put("storageLocations", slocs.findAllByOrderBySortOrderAscCodeAsc());
        m.put("materialTypes", mtypes.findAllByOrderBySortOrderAscSapCodeAsc());
        m.put("materialGroups", mgroups.findAll(org.springframework.data.domain.Sort.by("sapCode")));
        m.put("functions", functions.findAllByOrderBySortOrderAscNameAsc().stream().map(ResponsibleFunction::getName).toList());
        m.put("actionTypes", actionTypes.findAllByOrderBySortOrderAscNameAsc().stream().map(ActionType::getName).toList());
        m.put("persons", persons.findAll(org.springframework.data.domain.Sort.by("name")));
        m.put("categories", MAT_TYPES);
        m.put("departments", DEPTS);
        m.put("stages", STAGES);
        return m;
    }

    /**
     * Compact, active-only master data for the navigation sidebar — one call, ordered, with counts.
     * Respects active status and user organization / business-unit / plant authorization scope.
     */
    @Transactional(readOnly = true)
    public SidebarResponseDto sidebar() {
        AppUser me = current.username().isPresent() ? current.require() : null;
        boolean scopeAll = me == null || me.isScopeAll() || "admin".equalsIgnoreCase(me.getRoleCode());
        Set<String> allowedPlants = (me != null && !scopeAll) ? current.allowedPlants(me) : null;

        List<BusinessUnit> activeBus = bus.findByActiveTrueOrderByOrderAsc();
        Set<String> activeBuCodes = new HashSet<>();
        for (BusinessUnit b : activeBus) {
            activeBuCodes.add(b.getCode());
        }

        List<Plant> activePlants = plants.findByActiveTrueOrderByOrderAsc();
        Map<String, Plant> allPlantsByName = new HashMap<>();
        for (Plant p : plants.findAll()) {
            allPlantsByName.put(p.getName(), p);
        }

        Set<String> allowedBuCodes = null;
        if (!scopeAll && me != null) {
            allowedBuCodes = new HashSet<>();
            if (me.getScopeBus() != null) {
                allowedBuCodes.addAll(me.getScopeBus());
            }
            if (me.getScopeUnits() != null) {
                for (String unitName : me.getScopeUnits()) {
                    Plant p = allPlantsByName.get(unitName);
                    if (p != null && p.getBuCode() != null) {
                        allowedBuCodes.add(p.getBuCode());
                    }
                }
            }
        }

        Map<String, Integer> plantsPerBu = new HashMap<>();
        Set<String> visibleActivePlantNames = new HashSet<>();
        List<PlantSidebarDto> pl = new ArrayList<>();
        for (Plant p : activePlants) {
            if (!p.isActive()) continue;
            if (!activeBuCodes.isEmpty() && p.getBuCode() != null && !activeBuCodes.contains(p.getBuCode())) {
                // If BU exists in master and is explicitly deactivated, skip its plants
                Optional<BusinessUnit> buOpt = bus.findById(p.getBuCode());
                if (buOpt.isPresent() && !buOpt.get().isActive()) continue;
            }
            if (allowedPlants != null && !allowedPlants.contains(p.getName())) continue;
            visibleActivePlantNames.add(p.getName());
            plantsPerBu.merge(p.getBuCode(), 1, Integer::sum);
            pl.add(new PlantSidebarDto(
                    p.getId(),
                    p.getSapCode() != null ? p.getSapCode() : p.getName(),
                    p.getName(),
                    true,
                    p.getSortOrder(),
                    p.getBuCode(),
                    p.getCity()
            ));
        }

        List<BusinessUnitSidebarDto> bl = new ArrayList<>();
        int buIdx = 1;
        for (BusinessUnit b : activeBus) {
            if (!b.isActive()) continue;
            if (allowedBuCodes != null && !allowedBuCodes.contains(b.getCode())) continue;
            int order = b.getSortOrder() > 0 ? b.getSortOrder() : buIdx;
            bl.add(new BusinessUnitSidebarDto(
                    b.getCode(),
                    b.getCode(),
                    b.getName(),
                    true,
                    order,
                    b.getAlias(),
                    plantsPerBu.getOrDefault(b.getCode(), 0)
            ));
            buIdx++;
        }

        List<StorageLocationSidebarDto> sl = new ArrayList<>();
        int slIdx = 1;
        for (StorageLocation s : slocs.findByActiveTrueOrderByOrderAsc()) {
            if (!s.isActive()) continue;
            String plantCode = null;
            if (s.getPlantName() != null && !s.getPlantName().isBlank()) {
                if (!visibleActivePlantNames.contains(s.getPlantName())) continue;
                Plant p = allPlantsByName.get(s.getPlantName());
                plantCode = p != null && p.getSapCode() != null ? p.getSapCode() : s.getPlantName();
            } else if (allowedPlants != null && visibleActivePlantNames.isEmpty()) {
                continue;
            }
            int order = s.getSortOrder() > 0 ? s.getSortOrder() : slIdx;
            sl.add(new StorageLocationSidebarDto(
                    s.getId(),
                    s.getCode(),
                    s.getName(),
                    plantCode,
                    s.getPlantName(),
                    s.getStorageType(),
                    true,
                    order
            ));
            slIdx++;
        }

        List<MaterialTypeSidebarDto> ml = new ArrayList<>();
        int mtIdx = 1;
        for (MaterialTypeMap t : mtypes.findByActiveTrueOrderByOrderAsc()) {
            if (!t.isActive()) continue;
            if (allowedPlants != null && visibleActivePlantNames.isEmpty() && (allowedBuCodes == null || allowedBuCodes.isEmpty())) {
                continue;
            }
            int order = t.getSortOrder() > 0 ? t.getSortOrder() : mtIdx;
            ml.add(new MaterialTypeSidebarDto(
                    t.getSapCode(),
                    t.getSapCode(),
                    t.getDescription() != null ? t.getDescription() : t.getSapCode(),
                    true,
                    order,
                    t.getCategory(),
                    t.getBusinessUnit(),
                    t.getDepartment()
            ));
            mtIdx++;
        }

        SidebarCountsDto counts = new SidebarCountsDto(pl.size(), bl.size(), sl.size(), ml.size());
        return new SidebarResponseDto(pl, bl, sl, ml, counts);
    }

    @Transactional
    public BusinessUnit saveBu(BusinessUnit b) {
        if (b.getCode() == null || b.getCode().isBlank() || b.getName() == null || b.getName().isBlank()) throw new IllegalArgumentException("Business unit code and name are required");
        b.setCode(b.getCode().trim());
        b.setName(b.getName().trim());
        if (b.getAlias() != null && b.getAlias().isBlank()) b.setAlias(null);
        Optional<BusinessUnit> existing = bus.findById(b.getCode());
        if (existing.isPresent()) {
            BusinessUnit cur = existing.get();
            if (b.getId() == null) b.setId(cur.getId());
            if (b.getOrganizationId() == null) b.setOrganizationId(cur.getOrganizationId());
            if (b.getCreatedAt() == null) b.setCreatedAt(cur.getCreatedAt());
            if (b.getSortOrder() == 0 && cur.getSortOrder() > 0) b.setSortOrder(cur.getSortOrder());
        } else if (b.getId() == null) {
            b.setId(nextBuId());
        }
        b.setUpdatedAt(java.time.OffsetDateTime.now());
        audit.log("Master saved", "Business unit " + b.getCode() + (b.isActive() ? "" : " · inactive"));
        return bus.save(b);
    }

    private long nextBuId() {
        Long max = bus.findMaxId();
        if (max != null && max > 0) return max + 1;
        return bus.findAll().stream().map(BusinessUnit::getId).filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L) + 1;
    }

    @Transactional
    public void deleteBu(String code) {
        if (plants.findAll().stream().anyMatch(p -> code.equals(p.getBuCode()))) throw new IllegalStateException("Move or delete the plants of this business unit first");
        bus.deleteById(code);
        audit.log("Master deleted", "Business unit " + code);
    }

    @Transactional
    public Plant savePlant(Plant in) {
        if (in.getName() == null || in.getName().isBlank()) throw new IllegalArgumentException("Plant name is required");
        BusinessUnit buEntity = resolveBusinessUnit(in.getBusinessUnitId(), in.getBuCode());
        if (buEntity == null) throw new IllegalArgumentException("Unknown business unit " + (in.getBuCode() != null ? in.getBuCode() : in.getBusinessUnitId()));
        if (in.getSapCode() != null && in.getSapCode().isBlank()) in.setSapCode(null);
        Plant p = in.getId() == null ? new Plant() : plants.findById(in.getId()).orElseThrow(() -> new NotFoundException("Plant not found"));
        String oldName = p.getName(), oldBu = p.getBuCode();
        p.setSapCode(in.getSapCode()); p.setName(in.getName().trim());
        p.setBuCode(buEntity.getCode());
        p.setBusinessUnitId(buEntity.getId());
        p.setCity(in.getCity());
        p.setActive(in.isActive()); p.setSortOrder(in.getSortOrder());
        plants.save(p);
        plants.flush();
        // renaming / moving a plant updates existing inventory, actions and history (storage locations & scopes cascade via FK)
        if (oldName != null && !oldName.equals(p.getName())) {
            em.createNativeQuery("UPDATE inventory_item SET plant = :n WHERE plant = :o").setParameter("n", p.getName()).setParameter("o", oldName).executeUpdate();
            em.createNativeQuery("UPDATE action_item SET plant = :n, item_key = :n || substring(item_key from length(:o) + 1) WHERE plant = :o").setParameter("n", p.getName()).setParameter("o", oldName).executeUpdate();
            em.createNativeQuery("UPDATE snapshot_row SET plant = :n WHERE plant = :o").setParameter("n", p.getName()).setParameter("o", oldName).executeUpdate();
        }
        if (oldBu != null && !oldBu.equals(p.getBuCode())) {
            em.createNativeQuery("UPDATE inventory_item SET bu_code = :b WHERE plant = :p").setParameter("b", p.getBuCode()).setParameter("p", p.getName()).executeUpdate();
        }
        audit.log("Master saved", "Plant " + p.getName() + (oldName != null && !oldName.equals(p.getName()) ? " (renamed from " + oldName + ")" : "") + (p.isActive() ? "" : " · inactive"));
        return p;
    }

    private BusinessUnit resolveBusinessUnit(Long businessUnitId, String buCode) {
        if (businessUnitId != null) {
            return findBuById(businessUnitId).orElse(null);
        }
        if (buCode != null && !buCode.isBlank()) {
            return bus.findById(buCode.trim())
                    .or(() -> bus.findAll().stream().filter(b -> b.getCode().equalsIgnoreCase(buCode.trim())).findFirst())
                    .orElse(null);
        }
        return null;
    }

    private Optional<BusinessUnit> findBuById(Long id) {
        if (id == null) return Optional.empty();
        Optional<BusinessUnit> direct = bus.findByUnitId(id);
        if (direct.isPresent()) return direct;
        return bus.findAll().stream().filter(b -> id.equals(b.getId())).findFirst();
    }

    @Transactional
    public void deletePlant(Long id) {
        Plant p = plants.findById(id).orElseThrow(() -> new NotFoundException("Plant not found"));
        plants.delete(p);
        audit.log("Master deleted", "Plant " + p.getName() + " (inventory records are kept)");
    }

    @Transactional public StorageLocation saveSloc(StorageLocation s) {
        if (s.getCode() == null || s.getCode().isBlank() || s.getName() == null || s.getName().isBlank()) throw new IllegalArgumentException("Code and description are required");
        s.setCode(s.getCode().trim());
        s.setName(s.getName().trim());
        if (s.getPlantName() != null && s.getPlantName().isBlank()) s.setPlantName(null);
        audit.log("Master saved", "Storage location " + s.getCode() + (s.isActive() ? "" : " · inactive"));
        return slocs.save(s);
    }
    @Transactional public void deleteSloc(Long id) { slocs.deleteById(id); audit.log("Master deleted", "Storage location " + id); }

    public List<MaterialTypeMap> getAllMaterialTypes() {
        return mtypes.findAllByOrderBySortOrderAscSapCodeAsc();
    }

    public MaterialTypeMap getMaterialTypeByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) throw new IllegalArgumentException("Material type identifier is required");
        String trimmed = identifier.trim();
        // 1. Try parsing as Long ID if numeric
        try {
            Long id = Long.parseLong(trimmed);
            Optional<MaterialTypeMap> byId = mtypes.findById(id);
            if (byId.isPresent()) return byId.get();
        } catch (NumberFormatException ignored) {}

        // 2. Lookup by exact / case-insensitive SAP code
        List<MaterialTypeMap> list = mtypes.findAllBySapCodeIgnoreCase(trimmed);
        if (!list.isEmpty()) {
            return list.get(0);
        }
        return mtypes.findBySapCodeIgnoreCase(trimmed)
                .orElseThrow(() -> new NotFoundException("Material type '" + identifier + "' not found"));
    }

    @Transactional public MaterialTypeMap saveMtype(MaterialTypeMap m) {
        return saveMtype(m, false);
    }

    @Transactional public MaterialTypeMap updateMtype(String identifier, MaterialTypeMap m) {
        if (identifier != null && !identifier.isBlank()) {
            String trimmed = identifier.trim();
            try {
                Long id = Long.parseLong(trimmed);
                if (m.getId() == null) m.setId(id);
            } catch (NumberFormatException ignored) {
                if (m.getSapCode() == null || m.getSapCode().isBlank()) m.setSapCode(trimmed);
            }
        }
        return saveMtype(m, true);
    }

    @Transactional public MaterialTypeMap saveMtype(MaterialTypeMap m, boolean isUpdate) {
        if (m.getSapCode() == null || m.getSapCode().isBlank()) throw new IllegalArgumentException("Material type code is required");
        m.setSapCode(m.getSapCode().trim());
        if (!MAT_TYPES.contains(m.getCategory()) && !"Exclude".equals(m.getCategory())) throw new IllegalArgumentException("Invalid category " + m.getCategory());
        if (m.getDepartment() != null && m.getDepartment().isBlank()) m.setDepartment(null);
        if (m.getBusinessUnit() != null) {
            String trimmedBu = m.getBusinessUnit().trim();
            if (trimmedBu.isEmpty()) {
                m.setBusinessUnit(null);
            } else {
                BusinessUnit bu = bus.findById(trimmedBu)
                        .or(() -> bus.findAll().stream().filter(b -> b.getCode().equalsIgnoreCase(trimmedBu) || b.getName().equalsIgnoreCase(trimmedBu)).findFirst())
                        .orElseThrow(() -> new IllegalArgumentException("Business unit '" + trimmedBu + "' does not exist in master"));
                m.setBusinessUnit(bu.getCode());
            }
        }

        // Composite existing lookup & duplicate check
        Optional<MaterialTypeMap> existingOpt = m.getId() != null
                ? mtypes.findById(m.getId())
                : mtypes.findByCompositeKey(m.getSapCode(), m.getCategory(), m.getBusinessUnit());

        if (existingOpt.isPresent()) {
            MaterialTypeMap existing = existingOpt.get();
            if (isUpdate || (m.getId() != null && m.getId().equals(existing.getId()))) {
                // Update existing record without creating duplicate or overwriting unrelated record
                existing.setDescription(m.getDescription());
                existing.setNumberSeries(m.getNumberSeries());
                existing.setCategory(m.getCategory());
                existing.setDepartment(m.getDepartment());
                existing.setBusinessUnit(m.getBusinessUnit());
                existing.setActive(m.isActive());
                existing.setSortOrder(m.getSortOrder());
                audit.log("Master saved", "Material type " + existing.getSapCode() + " → " + existing.getCategory() + (existing.getBusinessUnit() != null ? " · BU: " + existing.getBusinessUnit() : "") + (existing.isActive() ? "" : " · inactive"));
                return mtypes.save(existing);
            } else {
                // Another record with this exact composite key already exists!
                throw new com.unichem.inventory.web.DuplicateMaterialTypeMappingException(
                        "Duplicate entry found: The combination of Material Type, Dashboard Category, and Business Unit must be unique.");
            }
        }

        validateNoDuplicateMaterialTypeMapping(m.getId(), m.getSapCode(), m.getCategory(), m.getBusinessUnit());
        audit.log("Master saved", "Material type " + m.getSapCode() + " → " + m.getCategory() + (m.getBusinessUnit() != null ? " · BU: " + m.getBusinessUnit() : "") + (m.isActive() ? "" : " · inactive"));
        return mtypes.save(m);
    }

    public void validateNoDuplicateMaterialTypeMapping(Long id, String sapCode, String category, String businessUnit) {
        if (sapCode == null || sapCode.isBlank() || category == null || category.isBlank()) {
            return;
        }
        String targetCode = sapCode.trim();
        String targetCat = category.trim();
        String targetBu = businessUnit != null ? businessUnit.trim() : "";

        for (MaterialTypeMap existing : mtypes.findAll()) {
            if (existing.getSapCode() == null || existing.getCategory() == null) continue;
            if (id != null && id.equals(existing.getId())) continue;

            if (!existing.getSapCode().trim().equalsIgnoreCase(targetCode)) continue;
            if (!existing.getCategory().trim().equalsIgnoreCase(targetCat)) continue;

            String existingBu = existing.getBusinessUnit() != null ? existing.getBusinessUnit().trim() : "";
            if (existingBu.equalsIgnoreCase(targetBu)) {
                throw new com.unichem.inventory.web.DuplicateMaterialTypeMappingException(
                        "Duplicate entry found: The combination of Material Type, Dashboard Category, and Business Unit must be unique.");
            }
        }
    }

    public void validateNoDuplicateMaterialTypeMapping(String currentSapCode, String category, String businessUnit, boolean isUpdate) {
        validateNoDuplicateMaterialTypeMapping(null, currentSapCode, category, businessUnit);
    }

    public void validateNoDuplicateMaterialTypeMapping(String currentSapCode, String category, String businessUnit) {
        validateNoDuplicateMaterialTypeMapping(null, currentSapCode, category, businessUnit);
    }

    @Transactional
    public List<MaterialTypeMap> saveAllMtypes(List<MaterialTypeMap> list) {
        if (list == null || list.isEmpty()) return List.of();

        // 1. Check in-batch duplicate composite (materialType + dashboardCategory + businessUnit) combinations
        Set<String> seenCombos = new HashSet<>();
        for (MaterialTypeMap m : list) {
            String code = m.getSapCode() != null ? m.getSapCode().trim().toLowerCase() : "";
            String cat = m.getCategory() != null ? m.getCategory().trim().toLowerCase() : "";
            String bu = m.getBusinessUnit() != null ? m.getBusinessUnit().trim().toLowerCase() : "";
            if (!code.isEmpty() && !cat.isEmpty()) {
                String key = code + "|" + cat + "|" + bu;
                if (!seenCombos.add(key)) {
                    throw new com.unichem.inventory.web.DuplicateMaterialTypeMappingException(
                            "Duplicate entry found: The combination of Material Type, Dashboard Category, and Business Unit must be unique.");
                }
            }
        }

        // 2. Save each: check by ID or composite key (material_type + category + business_unit)
        List<MaterialTypeMap> saved = new ArrayList<>();
        for (MaterialTypeMap m : list) {
            boolean isExisting = (m.getId() != null && mtypes.findById(m.getId()).isPresent())
                    || (m.getSapCode() != null && m.getCategory() != null && mtypes.findByCompositeKey(m.getSapCode().trim(), m.getCategory().trim(), m.getBusinessUnit() != null ? m.getBusinessUnit().trim() : null).isPresent());
            saved.add(saveMtype(m, isExisting));
        }
        return saved;
    }

    @Transactional
    public void deleteMtype(String identifier) {
        if (identifier == null || identifier.isBlank()) return;
        String trimmed = identifier.trim();
        try {
            Long id = Long.parseLong(trimmed);
            if (mtypes.existsById(id)) {
                mtypes.deleteById(id);
                audit.log("Master deleted", "Material type id " + id);
                return;
            }
        } catch (NumberFormatException ignored) {}

        List<MaterialTypeMap> list = mtypes.findAllBySapCodeIgnoreCase(trimmed);
        if (!list.isEmpty()) {
            mtypes.deleteAll(list);
            audit.log("Master deleted", "Material type " + trimmed);
        }
    }

    @Transactional public MaterialGroupMap saveMgroup(MaterialGroupMap m) {
        if (m.getCategory() != null && m.getCategory().isBlank()) m.setCategory(null);
        if (m.getDepartment() != null && m.getDepartment().isBlank()) m.setDepartment(null);
        audit.log("Master saved", "Material group " + m.getSapCode());
        return mgroups.save(m);
    }
    @Transactional public void deleteMgroup(String code) { mgroups.deleteById(code); audit.log("Master deleted", "Material group " + code); }

    @Transactional public void setList(String kind, List<String> values) {
        List<String> v = values.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if ("functions".equals(kind)) {
            Set<String> keep = new HashSet<>(v);
            functions.findAll().stream().filter(f -> !keep.contains(f.getName())).forEach(functions::delete);
            for (int i = 0; i < v.size(); i++) functions.save(new ResponsibleFunction(v.get(i), i));
        } else if ("actionTypes".equals(kind)) {
            Set<String> keep = new HashSet<>(v);
            actionTypes.findAll().stream().filter(f -> !keep.contains(f.getName())).forEach(actionTypes::delete);
            for (int i = 0; i < v.size(); i++) actionTypes.save(new ActionType(v.get(i), i));
        } else throw new IllegalArgumentException("Unknown list " + kind);
        audit.log("Master saved", kind + ": " + String.join(", ", v));
    }

    // =========================================================================
    // Business Units & Organization Hierarchy REST Service Methods
    // =========================================================================

    @Transactional(readOnly = true)
    public List<BusinessUnitDto> getAllBusinessUnits(Long organizationId, boolean includeInactive) {
        AppUser me = current.username().isPresent() ? current.require() : null;
        Set<String> allowedBuCodes = resolveAllowedBuCodes(me);

        List<BusinessUnit> list = includeInactive
                ? bus.findAllByOrderBySortOrderAscCodeAsc()
                : bus.findByActiveTrueOrderByOrderAsc();

        if (list.isEmpty()) {
            list = bus.findAll().stream()
                    .filter(b -> includeInactive || b.isActive())
                    .sorted(Comparator.comparingInt(BusinessUnit::getSortOrder).thenComparing(BusinessUnit::getCode))
                    .toList();
        }

        List<BusinessUnitDto> out = new ArrayList<>();
        int idx = 1;
        for (BusinessUnit b : list) {
            if (!includeInactive && !b.isActive()) continue;
            if (organizationId != null && b.getOrganizationId() != null && !organizationId.equals(b.getOrganizationId())) continue;
            if (allowedBuCodes != null && !allowedBuCodes.contains(b.getCode())) continue;
            out.add(toBusinessUnitDto(b, idx++));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<BusinessUnitDto> getAllBusinessUnits() {
        return getAllBusinessUnits(null, false);
    }

    @Transactional(readOnly = true)
    public BusinessUnitDto getBusinessUnitById(Long id) {
        if (id == null) throw new IllegalArgumentException("Business Unit ID is required");
        BusinessUnit b = findBuById(id)
                .orElseThrow(() -> new NotFoundException("Business Unit with ID " + id + " not found"));
        return toBusinessUnitDto(b, b.getSortOrder());
    }

    @Transactional(readOnly = true)
    public BusinessUnitDto getBusinessUnitByIdentifier(String idOrCode) {
        BusinessUnit b = resolveBuByIdentifier(idOrCode);
        return toBusinessUnitDto(b, b.getSortOrder());
    }

    @Transactional
    public BusinessUnitDto createBusinessUnit(BusinessUnitRequest req) {
        if (req == null) throw new IllegalArgumentException("Request body is required");
        String code = firstNonBlank(req.name(), req.code());
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Business Unit name is required");
        }
        code = code.trim();
        String description = firstNonBlank(req.description(), code).trim();
        String alias = firstNonBlank(req.alsoKnownAs(), req.alias());
        if (alias != null) {
            alias = alias.trim();
            if (alias.isEmpty()) alias = null;
        }
        Long orgId = req.organizationId() != null ? req.organizationId() : 1L;

        // Check duplicate Business Unit name (case-insensitive) within organization
        String checkCode = code;
        Optional<BusinessUnit> dup = bus.findByCodeIgnoreCase(checkCode)
                .or(() -> bus.findAll().stream()
                        .filter(existing -> (existing.getOrganizationId() == null || orgId.equals(existing.getOrganizationId()))
                                && existing.getCode() != null
                                && existing.getCode().equalsIgnoreCase(checkCode))
                        .findFirst());
        if (dup.isPresent()) {
            throw new IllegalStateException("Business Unit with name '" + code + "' already exists");
        }

        BusinessUnit b = new BusinessUnit();
        b.setId(req.id() != null ? req.id() : nextBuId());
        b.setOrganizationId(orgId);
        b.setCode(code);
        b.setName(description);
        b.setAlias(alias);
        b.setActive(req.active() == null || req.active());
        b.setSortOrder(req.sortOrder() != null ? req.sortOrder() : 0);
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        b.setCreatedAt(now);
        b.setUpdatedAt(now);

        BusinessUnit saved = bus.save(b);
        audit.log("Master saved", "Business unit " + saved.getCode() + (saved.isActive() ? "" : " · inactive"));
        return toBusinessUnitDto(saved, saved.getSortOrder());
    }

    @Transactional
    public BusinessUnitDto updateBusinessUnit(Long id, BusinessUnitRequest req) {
        if (id == null) throw new IllegalArgumentException("Business Unit ID is required");
        BusinessUnit existing = findBuById(id)
                .orElseThrow(() -> new NotFoundException("Business Unit with ID " + id + " not found"));
        return updateBusinessUnitEntity(existing, req);
    }

    @Transactional
    public BusinessUnitDto updateBusinessUnitByIdentifier(String idOrCode, BusinessUnitRequest req) {
        BusinessUnit existing = resolveBuByIdentifier(idOrCode);
        return updateBusinessUnitEntity(existing, req);
    }

    private BusinessUnitDto updateBusinessUnitEntity(BusinessUnit existing, BusinessUnitRequest req) {
        if (req == null) throw new IllegalArgumentException("Request body is required");
        String newCode = firstNonBlank(req.name(), req.code());
        if (newCode == null || newCode.isBlank()) {
            throw new IllegalArgumentException("Business Unit name is required");
        }
        newCode = newCode.trim();
        Long orgId = req.organizationId() != null ? req.organizationId() : (existing.getOrganizationId() != null ? existing.getOrganizationId() : 1L);

        String checkCode = newCode;
        Optional<BusinessUnit> dup = bus.findByCodeIgnoreCase(checkCode)
                .or(() -> bus.findAll().stream()
                        .filter(b -> (b.getOrganizationId() == null || orgId.equals(b.getOrganizationId()))
                                && b.getCode() != null
                                && b.getCode().equalsIgnoreCase(checkCode))
                        .findFirst());
        if (dup.isPresent() && !Objects.equals(dup.get().getId(), existing.getId()) && !dup.get().getCode().equalsIgnoreCase(existing.getCode())) {
            throw new IllegalStateException("Business Unit with name '" + newCode + "' already exists");
        }

        String oldCode = existing.getCode();
        String newDescription = firstNonBlank(req.description(), existing.getName(), newCode).trim();
        String newAlias = req.alsoKnownAs() != null ? req.alsoKnownAs() : (req.alias() != null ? req.alias() : existing.getAlias());
        if (newAlias != null) {
            newAlias = newAlias.trim();
            if (newAlias.isEmpty()) newAlias = null;
        }
        boolean newActive = req.active() != null ? req.active() : existing.isActive();
        int newOrder = req.sortOrder() != null ? req.sortOrder() : existing.getSortOrder();
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();

        if (!oldCode.equals(newCode)) {
            // Update primary key code via native SQL (ON UPDATE CASCADE updates plant.bu_code and user_scope_bu.bu_code)
            em.createNativeQuery("UPDATE business_unit SET code = :newCode, name = :desc, alias = :alias, active = :active, sort_order = :ord, organization_id = :orgId, updated_at = :now WHERE code = :oldCode")
                    .setParameter("newCode", newCode)
                    .setParameter("desc", newDescription)
                    .setParameter("alias", newAlias)
                    .setParameter("active", newActive)
                    .setParameter("ord", newOrder)
                    .setParameter("orgId", orgId)
                    .setParameter("now", now)
                    .setParameter("oldCode", oldCode)
                    .executeUpdate();
            em.createNativeQuery("UPDATE inventory_item SET bu_code = :newCode WHERE bu_code = :oldCode")
                    .setParameter("newCode", newCode).setParameter("oldCode", oldCode).executeUpdate();
            em.createNativeQuery("UPDATE action_item SET bu_code = :newCode WHERE bu_code = :oldCode")
                    .setParameter("newCode", newCode).setParameter("oldCode", oldCode).executeUpdate();
            em.createNativeQuery("UPDATE snapshot_row SET bu_code = :newCode WHERE bu_code = :oldCode")
                    .setParameter("newCode", newCode).setParameter("oldCode", oldCode).executeUpdate();

            // Synchronize any in-memory or managed entities
            for (Plant p : plants.findAll()) {
                if (Objects.equals(p.getBusinessUnitId(), existing.getId()) || oldCode.equals(p.getBuCode())) {
                    p.setBuCode(newCode);
                    p.setBusinessUnitId(existing.getId());
                    plants.save(p);
                }
            }
            bus.deleteById(oldCode);
            existing.setCode(newCode);
        }

        existing.setName(newDescription);
        existing.setAlias(newAlias);
        existing.setActive(newActive);
        existing.setSortOrder(newOrder);
        existing.setOrganizationId(orgId);
        existing.setUpdatedAt(now);

        BusinessUnit saved = bus.save(existing);
        audit.log("Master saved", "Business unit " + saved.getCode() + (!oldCode.equals(newCode) ? " (renamed from " + oldCode + ")" : "") + (saved.isActive() ? "" : " · inactive"));
        return toBusinessUnitDto(saved, saved.getSortOrder());
    }

    @Transactional
    public void deleteBusinessUnit(Long id) {
        if (id == null) throw new IllegalArgumentException("Business Unit ID is required");
        BusinessUnit existing = findBuById(id)
                .orElseThrow(() -> new NotFoundException("Business Unit with ID " + id + " not found"));
        deleteBusinessUnitEntity(existing);
    }

    @Transactional
    public void deleteBusinessUnitByIdentifier(String idOrCode) {
        BusinessUnit existing = resolveBuByIdentifier(idOrCode);
        deleteBusinessUnitEntity(existing);
    }

    private void deleteBusinessUnitEntity(BusinessUnit existing) {
        boolean hasPlants = plants.existsByBusinessUnitId(existing.getId())
                || plants.findAll().stream().anyMatch(p -> Objects.equals(p.getBusinessUnitId(), existing.getId()) || existing.getCode().equalsIgnoreCase(p.getBuCode()));
        if (hasPlants) {
            throw new IllegalStateException("Cannot delete Business Unit '" + existing.getCode() + "' because it has associated plants. Move or delete the plants first.");
        }
        bus.deleteById(existing.getCode());
        audit.log("Master deleted", "Business unit " + existing.getCode());
    }

    @Transactional(readOnly = true)
    public List<PlantDto> getPlantsByBusinessUnit(Long id) {
        if (id == null) throw new IllegalArgumentException("Business Unit ID is required");
        BusinessUnit bu = findBuById(id)
                .orElseThrow(() -> new NotFoundException("Business Unit with ID " + id + " not found"));
        return findPlantsForBu(bu);
    }

    @Transactional(readOnly = true)
    public List<PlantDto> getPlantsByBusinessUnitIdentifier(String idOrCode) {
        BusinessUnit bu = resolveBuByIdentifier(idOrCode);
        return findPlantsForBu(bu);
    }

    private List<PlantDto> findPlantsForBu(BusinessUnit bu) {
        List<Plant> byFk = plants.findByBusinessUnitIdOrderBySortOrderAscNameAsc(bu.getId());
        List<Plant> source = !byFk.isEmpty() ? byFk : plants.findAll().stream()
                .filter(p -> Objects.equals(p.getBusinessUnitId(), bu.getId()) || bu.getCode().equalsIgnoreCase(p.getBuCode()))
                .sorted(Comparator.comparingInt(Plant::getSortOrder).thenComparing(Plant::getName))
                .toList();
        return source.stream()
                .map(p -> new PlantDto(
                        p.getId(),
                        p.getSapCode(),
                        p.getName(),
                        p.getBusinessUnitId() != null ? p.getBusinessUnitId() : bu.getId(),
                        p.getBuCode(),
                        p.getCity(),
                        p.isActive(),
                        p.getSortOrder()))
                .toList();
    }

    @Transactional(readOnly = true)
    public OrganizationHierarchyDto getOrganizationHierarchy(Long organizationId, String companyShort) {
        Long orgId = organizationId != null ? organizationId : 1L;
        String orgName = (companyShort != null && !companyShort.isBlank()) ? formatOrgName(companyShort) : "Unichem";

        AppUser me = current.username().isPresent() ? current.require() : null;
        boolean scopeAll = me == null || me.isScopeAll() || "admin".equalsIgnoreCase(me.getRoleCode());
        Set<String> allowedPlants = (me != null && !scopeAll) ? current.allowedPlants(me) : null;
        Set<String> allowedBuCodes = resolveAllowedBuCodes(me);

        List<BusinessUnit> activeBus = bus.findByActiveTrueOrderByOrderAsc();
        if (activeBus.isEmpty()) {
            activeBus = bus.findAll().stream()
                    .filter(BusinessUnit::isActive)
                    .sorted(Comparator.comparingInt(BusinessUnit::getSortOrder).thenComparing(BusinessUnit::getCode))
                    .toList();
        }

        List<Plant> activePlants = plants.findByActiveTrueOrderByOrderAsc();
        if (activePlants.isEmpty()) {
            activePlants = plants.findAll().stream()
                    .filter(Plant::isActive)
                    .sorted(Comparator.comparingInt(Plant::getSortOrder).thenComparing(Plant::getName))
                    .toList();
        }

        List<HierarchyBusinessUnitDto> buDtos = new ArrayList<>();
        for (BusinessUnit b : activeBus) {
            if (!b.isActive()) continue;
            if (b.getOrganizationId() != null && !orgId.equals(b.getOrganizationId())) continue;
            if (allowedBuCodes != null && !allowedBuCodes.contains(b.getCode())) continue;

            List<HierarchyPlantDto> plantDtos = new ArrayList<>();
            for (Plant p : activePlants) {
                if (!p.isActive()) continue;
                boolean matchesBu = (p.getBusinessUnitId() != null && b.getId() != null && p.getBusinessUnitId().equals(b.getId()))
                        || (p.getBuCode() != null && p.getBuCode().equalsIgnoreCase(b.getCode()));
                if (!matchesBu) continue;
                if (allowedPlants != null && !allowedPlants.contains(p.getName())) continue;
                plantDtos.add(new HierarchyPlantDto(
                        p.getId(),
                        p.getName(),
                        p.getSapCode(),
                        p.getBusinessUnitId() != null ? p.getBusinessUnitId() : b.getId(),
                        p.isActive()
                ));
            }

            // IMPORTANT: Business Units with zero Plants also appear in the response
            buDtos.add(new HierarchyBusinessUnitDto(
                    b.getId(),
                    b.getCode(),
                    b.getName(),
                    b.getAlias(),
                    b.isActive(),
                    plantDtos
            ));
        }

        return new OrganizationHierarchyDto(orgId, orgName, buDtos);
    }

    @Transactional(readOnly = true)
    public OrganizationHierarchyDto getOrganizationHierarchy(Long organizationId) {
        return getOrganizationHierarchy(organizationId, "Unichem");
    }

    private Set<String> resolveAllowedBuCodes(AppUser me) {
        if (me == null) return null;
        return current.allowedBusinessUnits(me);
    }

    private BusinessUnit resolveBuByIdentifier(String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) {
            throw new IllegalArgumentException("Business Unit identifier is required");
        }
        String trimmed = idOrCode.trim();
        try {
            Long numId = Long.parseLong(trimmed);
            Optional<BusinessUnit> byId = findBuById(numId);
            if (byId.isPresent()) return byId.get();
        } catch (NumberFormatException ignored) {
        }
        return bus.findById(trimmed)
                .or(() -> bus.findByCodeIgnoreCase(trimmed))
                .or(() -> bus.findAll().stream().filter(b -> b.getCode() != null && b.getCode().equalsIgnoreCase(trimmed)).findFirst())
                .orElseThrow(() -> new NotFoundException("Business Unit '" + trimmed + "' not found"));
    }

    private BusinessUnitDto toBusinessUnitDto(BusinessUnit b, int fallbackOrder) {
        int order = b.getSortOrder() > 0 ? b.getSortOrder() : fallbackOrder;
        Long orgId = b.getOrganizationId() != null ? b.getOrganizationId() : 1L;
        return new BusinessUnitDto(
                b.getId(),
                orgId,
                b.getCode(),
                b.getCode(),
                b.getName(),
                b.getAlias(),
                b.getAlias(),
                b.isActive(),
                order,
                b.getCreatedAt(),
                b.getUpdatedAt()
        );
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String formatOrgName(String raw) {
        if (raw == null || raw.isBlank()) return "Unichem";
        String t = raw.trim();
        if ("UNICHEM".equalsIgnoreCase(t)) return "Unichem";
        return t;
    }

    @Transactional public ResponsiblePerson savePerson(ResponsiblePerson p) {
        if (p.getName() == null || p.getName().isBlank()) throw new IllegalArgumentException("Name is required");
        audit.log("Master saved", "Responsible person " + p.getName());
        return persons.save(p);
    }
    @Transactional public void deletePerson(Long id) { persons.deleteById(id); audit.log("Master deleted", "Responsible person " + id); }
}
