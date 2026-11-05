package com.unichem.inventory.web;

import com.unichem.inventory.dto.Dtos.ActionRequest;
import com.unichem.inventory.dto.Dtos.CloseRequest;
import com.unichem.inventory.service.ActionService;
import com.unichem.inventory.service.ExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/actions")
@RequiredArgsConstructor
public class ActionController {
    private final ActionService actions;
    private final ExportService export;

    @GetMapping @PreAuthorize("hasAuthority('view.actions')")
    public List<Map<String, Object>> list() { return actions.visible().stream().map(actions::view).toList(); }

    @GetMapping("/queue") @PreAuthorize("hasAuthority('view.actions')")
    public List<Map<String, Object>> queue(@RequestParam(defaultValue = "All") String prio) { return actions.queue(prio); }

    @PostMapping @PreAuthorize("hasAuthority('action.create')")
    public Map<String, Object> create(@Valid @RequestBody ActionRequest r) { return actions.view(actions.create(r)); }

    @PutMapping("/{id}") @PreAuthorize("hasAnyAuthority('action.update','action.update.own')")
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody ActionRequest r) { return actions.view(actions.update(id, r)); }

    @PostMapping("/{id}/start") @PreAuthorize("hasAnyAuthority('action.update','action.update.own')")
    public Map<String, Object> start(@PathVariable String id) { return actions.view(actions.start(id)); }

    @PostMapping("/{id}/close") @PreAuthorize("hasAnyAuthority('action.close','action.close.own')")
    public Map<String, Object> close(@PathVariable String id, @Valid @RequestBody CloseRequest r) { return actions.view(actions.close(id, r)); }

    @PostMapping("/{id}/reopen") @PreAuthorize("hasAnyAuthority('action.update','action.update.own')")
    public Map<String, Object> reopen(@PathVariable String id) { return actions.view(actions.reopen(id)); }

    @DeleteMapping("/{id}") @PreAuthorize("hasAuthority('action.delete')")
    public void delete(@PathVariable String id) { actions.delete(id); }

    @GetMapping("/export") @PreAuthorize("hasAuthority('export.data')")
    public ResponseEntity<byte[]> export() { return InventoryController.xlsx(export.actions(), "Unichem_Inventory_Actions_" + LocalDate.now() + ".xlsx"); }
}
