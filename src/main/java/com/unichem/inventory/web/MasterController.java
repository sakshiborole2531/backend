package com.unichem.inventory.web;

import com.unichem.inventory.domain.*;
import com.unichem.inventory.dto.Dtos;
import com.unichem.inventory.dto.Dtos.ActionTypeDto;
import com.unichem.inventory.dto.Dtos.BusinessUnitDto;
import com.unichem.inventory.dto.Dtos.ResponsibleFunctionDto;
import com.unichem.inventory.dto.Dtos.SidebarResponseDto;
import com.unichem.inventory.repo.SyncLogRepository;
import com.unichem.inventory.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/masters")
@RequiredArgsConstructor
public class MasterController {
    private final MasterService masters;
    private final SettingsService settings;
    private final CurrentUserService current;
    private final SyncLogRepository syncLog;

    /** Read access for every signed-in user (dropdowns, filters, labels). */
    @GetMapping public Map<String, Object> all(@RequestParam(defaultValue = "false") boolean activeOnly) { return masters.all(activeOnly); }
    @GetMapping("/active") public Map<String, Object> activeOnly() { return masters.all(true); }
    @GetMapping("/sidebar") public SidebarResponseDto sidebar() { return masters.sidebar(); }
    @GetMapping("/settings") public Map<String, String> settings() { return settings.all(); }
    @GetMapping("/sync-log") public List<SyncLog> sync() { return syncLog.findTop30ByOrderByAtDesc(); }

    @PutMapping("/settings") @PreAuthorize("hasAuthority('masters.edit')")
    public Map<String, String> saveSettings(@RequestBody Map<String, String> v) { return settings.update(v, current.require().getUsername()); }

    @GetMapping("/business-units") public List<BusinessUnit> getAllBus(@RequestParam(defaultValue = "false") boolean activeOnly) { return masters.getBusinessUnitsList(activeOnly); }
    @PostMapping("/business-units") @PreAuthorize("hasAuthority('masters.edit')") public BusinessUnit bu(@RequestBody BusinessUnit b) { return masters.saveBu(b); }
    @PostMapping("/business-units/batch") @PreAuthorize("hasAuthority('masters.edit')") public List<BusinessUnit> saveAllBus(@RequestBody List<BusinessUnit> list) { return masters.saveAllBus(list); }
    @PutMapping("/business-units/{code}") @PreAuthorize("hasAuthority('masters.edit')") public BusinessUnitDto updateBu(@PathVariable String code, @RequestBody Dtos.BusinessUnitRequest r) { return masters.updateBusinessUnitByIdentifier(code, r); }
    @DeleteMapping("/business-units/{code}") @PreAuthorize("hasAuthority('masters.edit')") public void delBu(@PathVariable String code) { masters.deleteBusinessUnitByIdentifier(code); }

    @PostMapping("/plants") @PreAuthorize("hasAuthority('masters.edit')") public Plant plant(@RequestBody Plant p) { return masters.savePlant(p); }
    @DeleteMapping("/plants/{id}") @PreAuthorize("hasAuthority('masters.edit')") public void delPlant(@PathVariable Long id) { masters.deletePlant(id); }

    @GetMapping("/storage-locations") public List<StorageLocation> getAllSlocs(@RequestParam(defaultValue = "false") boolean activeOnly) { return masters.getAllStorageLocations(activeOnly); }
    @PostMapping("/storage-locations") @PreAuthorize("hasAuthority('masters.edit')") public StorageLocation sloc(@RequestBody StorageLocation s) { return masters.saveSloc(s); }
    @PostMapping("/storage-locations/batch") @PreAuthorize("hasAuthority('masters.edit')") public List<StorageLocation> saveAllSlocs(@RequestBody List<StorageLocation> list) { return masters.saveAllSlocs(list); }
    @PutMapping("/storage-locations/{id}") @PreAuthorize("hasAuthority('masters.edit')") public StorageLocation updateSloc(@PathVariable Long id, @RequestBody StorageLocation s) { return masters.updateSloc(id, s); }
    @DeleteMapping("/storage-locations/{id}") @PreAuthorize("hasAuthority('masters.edit')") public void delSloc(@PathVariable Long id) { masters.deleteSloc(id); }

    @GetMapping("/material-types") public List<MaterialTypeMap> getAllMaterialTypes(@RequestParam(defaultValue = "false") boolean activeOnly) { return masters.getAllMaterialTypes(activeOnly); }
    @GetMapping("/material-types/{code}") public MaterialTypeMap getMaterialType(@PathVariable String code) { return masters.getMaterialTypeByIdentifier(code); }
    @PostMapping("/material-types") @PreAuthorize("hasAuthority('masters.edit')") public MaterialTypeMap mt(@RequestBody MaterialTypeMap m) { return masters.saveMtype(m); }
    @PostMapping("/material-types/batch") @PreAuthorize("hasAuthority('masters.edit')") public List<MaterialTypeMap> saveAllMtypes(@RequestBody List<MaterialTypeMap> list) { return masters.saveAllMtypes(list); }
    @PutMapping("/material-types/{code}") @PreAuthorize("hasAuthority('masters.edit')") public MaterialTypeMap updateMt(@PathVariable String code, @RequestBody MaterialTypeMap m) {
        return masters.updateMtype(code, m);
    }
    @DeleteMapping("/material-types/{code}") @PreAuthorize("hasAuthority('masters.edit')") public void delMt(@PathVariable String code) { masters.deleteMtype(code); }

    @GetMapping("/number-series-bu") public List<NumberSeriesBuMap> getAllNumberSeriesBu() { return masters.getAllNumberSeriesBu(); }
    @PostMapping("/number-series-bu") @PreAuthorize("hasAuthority('masters.edit')") public NumberSeriesBuMap numSeries(@RequestBody NumberSeriesBuMap m) { return masters.saveNumberSeriesBu(m); }
    @PostMapping("/number-series-bu/batch") @PreAuthorize("hasAuthority('masters.edit')") public List<NumberSeriesBuMap> saveAllNumSeries(@RequestBody List<NumberSeriesBuMap> list) { return masters.saveAllNumberSeriesBu(list); }
    @DeleteMapping("/number-series-bu/{id}") @PreAuthorize("hasAuthority('masters.edit')") public void delNumSeries(@PathVariable Long id) { masters.deleteNumberSeriesBu(id); }

    @GetMapping("/material-groups") public List<MaterialGroupMap> getAllMaterialGroups(@RequestParam(defaultValue = "false") boolean activeOnly) { return masters.getAllMaterialGroups(activeOnly); }
    @PostMapping("/material-groups") @PreAuthorize("hasAuthority('masters.edit')") public MaterialGroupMap mg(@RequestBody MaterialGroupMap m) { return masters.saveMgroup(m); }
    @PostMapping("/material-groups/batch") @PreAuthorize("hasAuthority('masters.edit')") public List<MaterialGroupMap> saveAllMgroups(@RequestBody List<MaterialGroupMap> list) { return masters.saveAllMgroups(list); }
    @PutMapping("/material-groups/{code}") @PreAuthorize("hasAuthority('masters.edit')") public MaterialGroupMap updateMg(@PathVariable String code, @RequestBody MaterialGroupMap m) { return masters.updateMgroup(code, m); }
    @DeleteMapping("/material-groups/{code}") @PreAuthorize("hasAuthority('masters.edit')") public void delMg(@PathVariable String code) { masters.deleteMgroup(code); }

    @GetMapping("/functions")
    public List<?> getAllFunctions(
            @RequestParam(defaultValue = "true") boolean activeOnly,
            @RequestParam(defaultValue = "names") String format) {
        if ("objects".equalsIgnoreCase(format) || "full".equalsIgnoreCase(format) || "entities".equalsIgnoreCase(format)) {
            return masters.getAllFunctions(activeOnly);
        }
        return masters.getFunctionNames(activeOnly);
    }

    @GetMapping("/functions/items")
    public List<ResponsibleFunction> getAllFunctionItems(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return masters.getAllFunctions(activeOnly);
    }

    @PostMapping("/functions") @PreAuthorize("hasAuthority('masters.edit')") public ResponsibleFunction saveFunction(@RequestBody ResponsibleFunction f) { return masters.saveFunction(f); }
    @PostMapping("/functions/batch") @PreAuthorize("hasAuthority('masters.edit')") public List<ResponsibleFunction> saveAllFunctions(@RequestBody List<ResponsibleFunction> list) { return masters.saveAllFunctions(list); }

    @GetMapping("/action-types")
    public List<?> getAllActionTypes(
            @RequestParam(defaultValue = "true") boolean activeOnly,
            @RequestParam(defaultValue = "names") String format) {
        if ("objects".equalsIgnoreCase(format) || "full".equalsIgnoreCase(format) || "entities".equalsIgnoreCase(format)) {
            return masters.getAllActionTypes(activeOnly);
        }
        return masters.getActionTypeNames(activeOnly);
    }

    @GetMapping("/action-types/items")
    public List<ActionType> getAllActionTypeItems(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return masters.getAllActionTypes(activeOnly);
    }

    @PostMapping("/action-types") @PreAuthorize("hasAuthority('masters.edit')") public ActionType saveActionType(@RequestBody ActionType a) { return masters.saveActionType(a); }
    @PostMapping("/action-types/batch") @PreAuthorize("hasAuthority('masters.edit')") public List<ActionType> saveAllActionTypes(@RequestBody List<ActionType> list) { return masters.saveAllActionTypes(list); }

    @PutMapping({"/lists/actionTypes", "/lists/acttypes"})
    @PreAuthorize("hasAuthority('masters.edit')")
    public ResponseEntity<List<ActionType>> updateActionTypes(@RequestBody List<ActionTypeDto> actionTypes) {
        return ResponseEntity.ok(masters.updateActionTypes(actionTypes));
    }

    @PutMapping("/lists/functions")
    @PreAuthorize("hasAuthority('masters.edit')")
    public ResponseEntity<List<ResponsibleFunction>> updateFunctions(@RequestBody List<ResponsibleFunctionDto> functions) {
        return ResponseEntity.ok(masters.updateFunctions(functions));
    }

    @PutMapping("/lists/{kind}")
    @PreAuthorize("hasAuthority('masters.edit')")
    public ResponseEntity<?> list(@PathVariable String kind, @RequestBody List<ActionTypeDto> items) {
        masters.setListGeneric(kind, items);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/persons") @PreAuthorize("hasAuthority('masters.edit')") public ResponsiblePerson person(@RequestBody ResponsiblePerson p) { return masters.savePerson(p); }
    @DeleteMapping("/persons/{id}") @PreAuthorize("hasAuthority('masters.edit')") public void delPerson(@PathVariable Long id) { masters.deletePerson(id); }
}
