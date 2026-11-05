package com.unichem.inventory.service;

import com.unichem.inventory.domain.ActionItem;
import com.unichem.inventory.domain.AppUser;
import com.unichem.inventory.domain.InventoryItem;
import com.unichem.inventory.dto.CalcItem;
import com.unichem.inventory.dto.Dtos.ActionRequest;
import com.unichem.inventory.dto.Dtos.CloseRequest;
import com.unichem.inventory.dto.Thresholds;
import com.unichem.inventory.repo.ActionItemRepository;
import com.unichem.inventory.repo.InventoryItemRepository;
import com.unichem.inventory.web.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/** Ownership → Action → Closure workflow with escalation and own-function authorization. */
@Service
@RequiredArgsConstructor
public class ActionService {
    private final ActionItemRepository repo;
    private final InventoryItemRepository inventory;
    private final InventoryService inv;
    private final CalcService calc;
    private final SettingsService settings;
    private final CurrentUserService current;
    private final AuditService audit;

    public static boolean overdue(ActionItem a) {
        return !"Closed".equals(a.getStage()) && a.getTargetDate() != null && a.getTargetDate().isBefore(LocalDate.now());
    }
    public static Integer daysLeft(ActionItem a) {
        return a.getTargetDate() == null ? null : (int) ChronoUnit.DAYS.between(LocalDate.now(), a.getTargetDate());
    }
    /** Overdue → L1 Function Head; overdue more than 14 days → L2 Plant Head. */
    public static String escalation(ActionItem a) {
        if ("Closed".equals(a.getStage())) return "";
        Integer d = daysLeft(a);
        if (d == null) return "No target date";
        if (d < -14) return "L2 · Plant Head";
        if (d < 0) return "L1 · Function Head";
        if (d <= 7) return "Due ≤7d";
        return "On track";
    }

    public static String defaultFn(CalcItem x) {
        if (x == null) return "Stores";
        if ("Consumable".equals(x.matType())) return switch (x.dept() == null ? "" : x.dept()) {
            case "Production" -> "Production"; case "Engineering" -> "Engineering"; case "QC" -> "QC"; case "EHS" -> "EHS"; default -> "Stores"; };
        if (x.flags().contains("EXP") || x.flags().contains("EXPD") || x.flags().contains("QH")) return "QC";
        return switch (x.matType()) {
            case "Intermediate", "Semi-Finished Goods", "Recovered Solvent" -> "Production";
            case "Finished Goods" -> "Planning";
            default -> "Stores";
        };
    }

    public static String[] suggest(CalcItem x) {
        if (x == null) return new String[]{"Other approved corrective action", ""};
        List<String> f = x.flags(); String b = x.batch() == null ? "" : x.batch();
        if (f.contains("EXPD")) return new String[]{"Disposal/write-off review", "Review expired batch " + b + " of " + x.code() + " for disposal / write-off"};
        if (f.contains("EXP")) return x.rsl() <= 60
                ? new String[]{"Priority consumption", "Consume " + x.code() + " batch " + b + " in next production campaign"}
                : new String[]{"Expiry/retest review", "Initiate " + x.dateType().toLowerCase() + " review for " + x.code() + " batch " + b};
        if (f.contains("BLK")) return new String[]{"Disposal/write-off review", "Disposition decision on blocked stock — " + (x.blockReason() == null ? "reason to be confirmed" : x.blockReason())};
        if (f.contains("QH")) return new String[]{"Expiry/retest review", "Expedite QC release of " + x.code() + " batch " + b};
        if (f.contains("NM") || f.contains("LL")) return "Engineering".equals(x.dept())
                ? new String[]{"Engineering consumption planning", "Plan consumption of " + x.name() + " in upcoming maintenance"}
                : new String[]{"Inventory rationalization", "Rationalise idle " + x.name() + "; transfer or stop re-ordering"};
        if (f.contains("AGED")) return new String[]{"Production utilization", "Plan utilisation of aged " + x.code() + " in next batch"};
        return new String[]{"Other approved corrective action", ""};
    }

    /** Actions the user may see (scope + active plants). */
    @Transactional(readOnly = true)
    public List<ActionItem> visible() {
        Set<String> allowed = current.allowedPlants();
        Set<String> inactive = current.inactivePlants();
        return repo.findAll().stream()
                .filter(a -> a.getPlant() == null || !inactive.contains(a.getPlant()))
                .filter(a -> allowed == null || (a.getPlant() != null && allowed.contains(a.getPlant())))
                .sorted(Comparator.comparing((ActionItem a) -> a.getTargetDate() == null ? LocalDate.MAX : a.getTargetDate()))
                .toList();
    }

    /** Exceptions awaiting ownership: flagged records with no open action, ranked by risk score. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> queue(String prio) {
        Set<String> open = repo.findAll().stream().filter(a -> !"Closed".equals(a.getStage()) && a.getItemKey() != null)
                .map(ActionItem::getItemKey).collect(Collectors.toSet());
        return inv.calculated(null).stream()
                .filter(x -> !x.realFlags().isEmpty() && !open.contains(x.itemKey()))
                .filter(x -> prio == null || "All".equals(prio) || prio.equals(x.prio()))
                .sorted(Comparator.comparingInt(CalcItem::score).reversed())
                .map(x -> {
                    String[] s = suggest(x);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("item", x); m.put("suggestedFunction", defaultFn(x)); m.put("suggestedType", s[0]); m.put("suggestedPlan", s[1]);
                    return m;
                }).toList();
    }

    private boolean canAct(ActionItem a, String kind) {
        AppUser u = current.require();
        Set<String> p = current.permissions(u);
        if (p.contains("action." + kind)) return true;
        return p.contains("action." + kind + ".own") &&
                ((u.getFunctionName() != null && u.getFunctionName().equals(a.getFunctionName())) || (u.getName() != null && u.getName().equals(a.getPerson())));
    }

    private ActionItem load(String id) {
        ActionItem a = repo.findById(id).orElseThrow(() -> new NotFoundException("Action " + id + " not found"));
        if (a.getPlant() != null && !current.inScope(a.getPlant())) throw new AccessDeniedException("Action is outside your data access");
        return a;
    }

    @Transactional
    public ActionItem create(ActionRequest r) {
        current.requirePerm("action.create");
        AppUser me = current.require();
        ActionItem a = new ActionItem();
        a.setId(String.format("ACT-%04d", repo.nextSeq()));
        a.setCreatedBy(me.getUsername());
        if (r.inventoryId() != null) {
            InventoryItem it = inventory.findById(r.inventoryId()).orElseThrow(() -> new NotFoundException("Inventory record not found"));
            current.requireScope(it.getPlant());
            CalcItem x = calc.calc(it, settings.thresholds());
            if (!repo.findByItemKeyAndStageNot(x.itemKey(), "Closed").isEmpty())
                throw new IllegalStateException("This batch already has an open action");
            a.setItemKey(x.itemKey()); a.setBuCode(x.bu()); a.setPlant(x.plant()); a.setCode(x.code()); a.setName(x.name());
            a.setBatch(x.batch()); a.setMatType(x.matType()); a.setValueAtRaise(BigDecimal.valueOf(Math.round(x.value() * 100) / 100.0));
            if (r.exception() == null) { Thresholds t = settings.thresholds(); a.setException(x.realFlags().stream().map(f -> CalcService.flagLabel(f, t)).collect(Collectors.joining(", "))); }
        } else {
            a.setItemKey(""); a.setName(r.name() == null || r.name().isBlank() ? "General action" : r.name());
        }
        fill(a, r);
        repo.save(a);
        audit.log("Action created", a.getId() + " · " + a.getName() + " · " + a.getFunctionName() + (a.getPerson() != null ? " – " + a.getPerson() : ""), "action", a.getId(), null);
        return a;
    }

    @Transactional
    public ActionItem update(String id, ActionRequest r) {
        ActionItem a = load(id);
        if (!canAct(a, "update")) throw new AccessDeniedException("You can update only actions owned by your function");
        if ("Closed".equals(a.getStage())) throw new IllegalStateException("Closed actions cannot be edited — reopen first");
        if (r.name() != null && (a.getItemKey() == null || a.getItemKey().isEmpty())) a.setName(r.name());
        fill(a, r);
        audit.log("Action updated", a.getId() + " · " + a.getFunctionName() + (a.getPerson() != null ? " – " + a.getPerson() : ""), "action", id, null);
        return a;
    }

    private void fill(ActionItem a, ActionRequest r) {
        if (r.exception() != null) a.setException(r.exception());
        a.setActionType(r.actionType());
        a.setPriority(r.priority() == null ? "P3" : r.priority());
        a.setFunctionName(r.functionName());
        a.setPerson(r.person() == null || r.person().isBlank() ? null : r.person().trim());
        a.setPlan(r.plan().trim());
        a.setTargetDate(r.targetDate());
        a.setRemarks(r.remarks());
        // Stage moves automatically to Owned once function, person and target date are set
        if ("In Action".equals(r.stage())) a.setStage("In Action");
        else a.setStage(a.getPerson() != null && a.getTargetDate() != null ? "Owned" : "Prioritized");
    }

    @Transactional
    public ActionItem start(String id) {
        ActionItem a = load(id);
        if (!canAct(a, "update")) throw new AccessDeniedException("You don't have permission to change this action");
        if (!"Owned".equals(a.getStage()) && !"Prioritized".equals(a.getStage())) throw new IllegalStateException("Only owned actions can be started");
        a.setStage("In Action");
        audit.log("Action started", id, "action", id, null);
        return a;
    }

    @Transactional
    public ActionItem close(String id, CloseRequest r) {
        ActionItem a = load(id);
        if (!canAct(a, "close")) throw new AccessDeniedException("You can close only actions owned by your function");
        if ("Closed".equals(a.getStage())) throw new IllegalStateException("Action is already closed");
        a.setStage("Closed"); a.setClosedDate(r.closedDate());
        a.setClosure(r.outcome() + " — " + r.closure().trim());
        a.setClosedBy(current.require().getName());
        audit.log("Action closed", id + " · " + r.outcome(), "action", id, null);
        return a;
    }

    @Transactional
    public ActionItem reopen(String id) {
        ActionItem a = load(id);
        if (!canAct(a, "update")) throw new AccessDeniedException("You don't have permission to change this action");
        if (a.getItemKey() != null && !a.getItemKey().isEmpty() && repo.findByItemKeyAndStageNot(a.getItemKey(), "Closed").stream().anyMatch(o -> !o.getId().equals(id)))
            throw new IllegalStateException("Another open action exists for this batch");
        a.setStage("Owned"); a.setClosedDate(null); a.setClosure(null); a.setClosedBy(null);
        audit.log("Action reopened", id, "action", id, null);
        return a;
    }

    @Transactional
    public void delete(String id) {
        current.requirePerm("action.delete");
        ActionItem a = load(id);
        repo.delete(a);
        audit.log("Action deleted", id, "action", id, null);
    }

    public Map<String, Object> view(ActionItem a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId()); m.put("itemKey", a.getItemKey()); m.put("bu", a.getBuCode()); m.put("plant", a.getPlant());
        m.put("code", a.getCode()); m.put("name", a.getName()); m.put("batch", a.getBatch()); m.put("matType", a.getMatType());
        m.put("valueAtRaise", a.getValueAtRaise()); m.put("exception", a.getException()); m.put("actionType", a.getActionType());
        m.put("plan", a.getPlan()); m.put("priority", a.getPriority()); m.put("functionName", a.getFunctionName()); m.put("person", a.getPerson());
        m.put("createdDate", a.getCreatedDate()); m.put("targetDate", a.getTargetDate()); m.put("stage", a.getStage()); m.put("remarks", a.getRemarks());
        m.put("closedDate", a.getClosedDate()); m.put("closure", a.getClosure()); m.put("closedBy", a.getClosedBy());
        m.put("daysLeft", daysLeft(a)); m.put("overdue", overdue(a)); m.put("escalation", escalation(a));
        m.put("canUpdate", canAct(a, "update")); m.put("canClose", canAct(a, "close"));
        return m;
    }
}
