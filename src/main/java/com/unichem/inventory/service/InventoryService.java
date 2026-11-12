package com.unichem.inventory.service;

import com.unichem.inventory.domain.*;
import com.unichem.inventory.dto.CalcItem;
import com.unichem.inventory.dto.Dtos.InventoryRequest;
import com.unichem.inventory.dto.Thresholds;
import com.unichem.inventory.repo.ActionItemRepository;
import com.unichem.inventory.repo.InventoryItemRepository;
import com.unichem.inventory.web.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;

import static com.unichem.inventory.domain.Constants.*;

@Service
@RequiredArgsConstructor
public class InventoryService {
    private final InventoryItemRepository repo;
    private final ActionItemRepository actions;
    private final CalcService calc;
    private final SettingsService settings;
    private final CurrentUserService current;
    private final MasterLookup lookup;
    private final AuditService audit;

    /** Filters used by every analysis page. */
    public record Filter(String bu, String plant, String cat, String status, String dept, String loc, String q, Double minValue) {
        static boolean all(String v) { return v == null || v.isBlank() || "All".equals(v); }
        public boolean test(CalcItem x) {
            if (!all(bu) && !bu.equals(x.bu())) return false;
            if (!all(plant) && !plant.equals(x.plant())) return false;
            if (!all(cat)) {
                if ("Goods".equals(cat) ? "Consumable".equals(x.matType()) : !cat.equals(x.matType())) return false;
            }
            if (!all(status) && !x.statuses().contains(status)) return false;
            if (!all(dept) && !dept.equals(x.dept())) return false;
            if (!all(loc) && !loc.equals(x.storageLoc())) return false;
            if (q != null && !q.isBlank()) {
                String hay = String.join(" ", Stream.of(x.code(), x.name(), x.batch(), x.storageLoc(), x.subCat(), x.dept())
                        .map(s -> s == null ? "" : s).toList()).toLowerCase();
                if (!hay.contains(q.trim().toLowerCase())) return false;
            }
            return minValue == null || x.value() >= minValue;
        }
    }

    /** All records the user may see (data scope + active plants), calculated as of today. */
    @Transactional(readOnly = true)
    public List<CalcItem> calculated(Filter f) {
        if (f != null && f.bu() != null && !Filter.all(f.bu())) {
            current.requireBuScope(f.bu());
        }
        if (f != null && f.plant() != null && !Filter.all(f.plant())) {
            current.requireScope(f.plant());
        }
        Thresholds t = settings.thresholds();
        Set<String> visible = current.visiblePlants();
        Set<String> inactive = current.inactivePlants();
        Set<String> allowed = current.allowedPlants();
        return repo.findAll().stream()
                .filter(i -> visible.contains(i.getPlant()) || (!inactive.contains(i.getPlant()) && allowed == null))
                .map(i -> calc.calc(i, t))
                .filter(x -> f == null || f.test(x))
                .toList();
    }

    @Transactional(readOnly = true)
    public CalcItem one(Long id) {
        InventoryItem it = repo.findById(id).orElseThrow(() -> new NotFoundException("Inventory record " + id + " not found"));
        current.requireScope(it.getPlant());
        return calc.calc(it, settings.thresholds());
    }

    @Transactional
    public CalcItem create(InventoryRequest r) {
        InventoryItem it = new InventoryItem();
        apply(it, r);
        if (repo.findByPlantAndCodeAndBatch(it.getPlant(), it.getCode(), it.getBatch()).isPresent())
            throw new IllegalArgumentException("A record for plant " + it.getPlant() + ", material " + it.getCode() + ", batch " + it.getBatch() + " already exists");
        repo.save(it);
        audit.log("Record added", it.getPlant() + " · " + it.getCode() + " · " + it.getBatch(), "inventory", String.valueOf(it.getId()), null);
        return calc.calc(it, settings.thresholds());
    }

    @Transactional
    public CalcItem update(Long id, InventoryRequest r) {
        InventoryItem it = repo.findById(id).orElseThrow(() -> new NotFoundException("Inventory record " + id + " not found"));
        current.requireScope(it.getPlant());
        String oldKey = it.itemKey();
        apply(it, r);
        String newKey = it.itemKey();
        if (!oldKey.equals(newKey)) {
            repo.findByPlantAndCodeAndBatch(it.getPlant(), it.getCode(), it.getBatch())
                    .filter(o -> !o.getId().equals(id))
                    .ifPresent(o -> { throw new IllegalArgumentException("Another record already uses this plant + code + batch"); });
            actions.findByItemKey(oldKey).forEach(a -> a.setItemKey(newKey));   // keep actions linked
        }
        audit.log("Record updated", it.getPlant() + " · " + it.getCode() + " · " + it.getBatch(), "inventory", String.valueOf(id), null);
        return calc.calc(it, settings.thresholds());
    }

    @Transactional
    public void delete(Long id) {
        InventoryItem it = repo.findById(id).orElseThrow(() -> new NotFoundException("Inventory record " + id + " not found"));
        current.requireScope(it.getPlant());
        repo.delete(it);
        audit.log("Record deleted", it.getPlant() + " · " + it.getCode() + " · " + it.getBatch(), "inventory", String.valueOf(id), null);
    }

    /** Validation + normalization rules (port of normRec). */
    private void apply(InventoryItem it, InventoryRequest r) {
        MasterLookup.Ctx ctx = lookup.ctx();
        Plant p = ctx.plant(r.plant()).orElseThrow(() -> new IllegalArgumentException("Plant \"" + r.plant() + "\" is not in the plant master"));
        current.requireScope(p.getName());
        String cat = ctx.category(r.matType());
        if (cat.isEmpty()) throw new IllegalArgumentException("Unknown material category: " + r.matType());
        BigDecimal u = nz(r.unrestrictedQty()), q = nz(r.qualityQty()), b = nz(r.blockedQty());
        if (r.mfgDate() != null && r.expiryDate() != null && r.expiryDate().isBefore(r.mfgDate()))
            throw new IllegalArgumentException("SLED/BBD cannot be earlier than the Date of Manufacture");
        it.setPlant(p.getName());
        it.setBuCode(p.getBuCode());
        it.setMatType(cat);
        if ("Consumable".equals(cat)) {
            String d = MasterLookup.dept(r.dept());
            it.setDepartment(d.isEmpty() ? "Other" : d);
        } else it.setDepartment(null);
        it.setSubCategory(trim(r.subCat()));
        it.setCode(r.code().trim());
        it.setName(r.name().trim());
        it.setBatch(r.batch() == null ? "" : r.batch().trim());
        it.setUom(trim(r.uom()));
        it.setStorageLoc(ctx.sloc(r.storageLoc(), p.getName()).map(StorageLocation::getName).orElse(trim(r.storageLoc())));
        it.setUnrestrictedQty(u); it.setQualityQty(q); it.setBlockedQty(b);
        it.setUnitCost(r.unitCost());
        it.setReceiptDate(r.receiptDate()); it.setLyingSince(r.lyingSince()); it.setMfgDate(r.mfgDate());
        it.setExpiryDate(r.expiryDate());
        it.setDateType(r.dateType() != null && r.dateType().toLowerCase().contains("retest") ? "Retest" : "Expiry");
        it.setLastIssueDate(r.lastIssueDate());
        it.setBlockReason(trim(r.blockReason())); it.setRemarks(trim(r.remarks()));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static String trim(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    /** Data quality exceptions — incomplete data weakens aging, expiry and value KPIs. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> dataIssues() {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> visible = current.visiblePlants();
        for (CalcItem x : calculated(null)) {
            List<String> msgs = new ArrayList<>();
            if (x.unitCost() == 0) msgs.add("Unit cost missing / zero — value cannot be computed");
            if (x.receiptDate() == null && x.lyingSince() == null) msgs.add("GR Posting Date missing — age cannot be computed");
            if (x.matType() != null && SHELF_TYPES.contains(x.matType()) && x.expiryDate() == null) msgs.add("SLED/BBD missing — expiry cannot be monitored");
            if (x.qty() <= 0) msgs.add("Zero stock quantity");
            if (x.mfgDate() != null && x.expiryDate() != null && x.expiryDate().isBefore(x.mfgDate())) msgs.add("SLED/BBD earlier than Date of Manufacture");
            if (!visible.contains(x.plant())) msgs.add("Plant not in plant master");
            for (String m : msgs) out.add(Map.of("id", x.id(), "code", x.code(), "batch", x.batch(), "plant", x.plant(), "issue", m));
        }
        return out;
    }
}
