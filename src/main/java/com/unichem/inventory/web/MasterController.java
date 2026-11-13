package com.unichem.inventory.web;

import com.unichem.inventory.domain.*;
import com.unichem.inventory.dto.Dtos;
import com.unichem.inventory.dto.Dtos.BusinessUnitDto;
import com.unichem.inventory.dto.Dtos.SidebarResponseDto;
import com.unichem.inventory.repo.SyncLogRepository;
import com.unichem.inventory.service.*;
import lombok.RequiredArgsConstructor;
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
    @GetMapping public Map<String, Object> all() { return masters.all(); }
    @GetMapping("/sidebar") public SidebarResponseDto sidebar() { return masters.sidebar(); }
    @GetMapping("/settings") public Map<String, String> settings() { return settings.all(); }
    @GetMapping("/sync-log") public List<SyncLog> sync() { return syncLog.findTop30ByOrderByAtDesc(); }

    @PutMapping("/settings") @PreAuthorize("hasAuthority('masters.edit')")
    public Map<String, String> saveSettings(@RequestBody Map<String, String> v) { return settings.update(v, current.require().getUsername()); }

    @PostMapping("/business-units") @PreAuthorize("hasAuthority('masters.edit')") public BusinessUnit bu(@RequestBody BusinessUnit b) { return masters.saveBu(b); }
    @PutMapping("/business-units/{code}") @PreAuthorize("hasAuthority('masters.edit')") public BusinessUnitDto updateBu(@PathVariable String code, @RequestBody Dtos.BusinessUnitRequest r) { return masters.updateBusinessUnitByIdentifier(code, r); }
    @DeleteMapping("/business-units/{code}") @PreAuthorize("hasAuthority('masters.edit')") public void delBu(@PathVariable String code) { masters.deleteBusinessUnitByIdentifier(code); }

    @PostMapping("/plants") @PreAuthorize("hasAuthority('masters.edit')") public Plant plant(@RequestBody Plant p) { return masters.savePlant(p); }
    @DeleteMapping("/plants/{id}") @PreAuthorize("hasAuthority('masters.edit')") public void delPlant(@PathVariable Long id) { masters.deletePlant(id); }

    @PostMapping("/storage-locations") @PreAuthorize("hasAuthority('masters.edit')") public StorageLocation sloc(@RequestBody StorageLocation s) { return masters.saveSloc(s); }
    @DeleteMapping("/storage-locations/{id}") @PreAuthorize("hasAuthority('masters.edit')") public void delSloc(@PathVariable Long id) { masters.deleteSloc(id); }

    @GetMapping("/material-types") public List<MaterialTypeMap> getAllMaterialTypes() { return masters.getAllMaterialTypes(); }
    @GetMapping("/material-types/{code}") public MaterialTypeMap getMaterialType(@PathVariable String code) { return masters.getMaterialTypeByIdentifier(code); }
    @PostMapping("/material-types") @PreAuthorize("hasAuthority('masters.edit')") public MaterialTypeMap mt(@RequestBody MaterialTypeMap m) { return masters.saveMtype(m); }
    @PostMapping("/material-types/batch") @PreAuthorize("hasAuthority('masters.edit')") public List<MaterialTypeMap> saveAllMtypes(@RequestBody List<MaterialTypeMap> list) { return masters.saveAllMtypes(list); }
    @PutMapping("/material-types/{code}") @PreAuthorize("hasAuthority('masters.edit')") public MaterialTypeMap updateMt(@PathVariable String code, @RequestBody MaterialTypeMap m) {
        return masters.updateMtype(code, m);
    }
    @DeleteMapping("/material-types/{code}") @PreAuthorize("hasAuthority('masters.edit')") public void delMt(@PathVariable String code) { masters.deleteMtype(code); }

    @PostMapping("/material-groups") @PreAuthorize("hasAuthority('masters.edit')") public MaterialGroupMap mg(@RequestBody MaterialGroupMap m) { return masters.saveMgroup(m); }
    @DeleteMapping("/material-groups/{code}") @PreAuthorize("hasAuthority('masters.edit')") public void delMg(@PathVariable String code) { masters.deleteMgroup(code); }

    @PutMapping("/lists/{kind}") @PreAuthorize("hasAuthority('masters.edit')") public void list(@PathVariable String kind, @RequestBody List<String> v) { masters.setList(kind, v); }

    @PostMapping("/persons") @PreAuthorize("hasAuthority('masters.edit')") public ResponsiblePerson person(@RequestBody ResponsiblePerson p) { return masters.savePerson(p); }
    @DeleteMapping("/persons/{id}") @PreAuthorize("hasAuthority('masters.edit')") public void delPerson(@PathVariable Long id) { masters.deletePerson(id); }
}
