package com.unichem.inventory.service;

import com.unichem.inventory.domain.*;
import com.unichem.inventory.dto.CalcItem;
import com.unichem.inventory.dto.Thresholds;
import com.unichem.inventory.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/** Illustrative sample data (same catalogue and distribution as the original dashboard) — for demos and UAT only. */
@Service
@RequiredArgsConstructor
public class SampleDataService {
    private final InventoryItemRepository inventory;
    private final ActionItemRepository actions;
    private final InventorySnapshotRepository snaps;
    private final PlantRepository plants;
    private final CalcService calc;
    private final SettingsService settings;
    private final SnapshotService snapshots;
    private final AuditService audit;
    private final CurrentUserService current;

    private long seed = 20261022L;
    private double rnd() { seed = (seed * 1664525 + 1013904223) % 4294967296L; return seed / 4294967296.0; }
    private int ri(int a, int b) { return (int) Math.floor(a + rnd() * (b - a + 1)); }
    private <T> T pick(List<T> l) { return l.get((int) Math.floor(rnd() * l.size())); }

    private static final Object[][] C = {
        {"Formulation","Raw Material","RM-1102-0045","Lactose monohydrate IP","KG",240,1500,4000,"Raw Materials",4},
        {"Formulation","Raw Material","RM-1148-0203","Povidone K-30","KG",780,300,900,"Raw Materials",3},
        {"Formulation","Raw Material","RM-1153-0118","Microcrystalline cellulose","KG",190,1500,4000,"Raw Materials",4},
        {"Formulation","Raw Material","RM-1160-0071","Magnesium stearate","KG",310,200,700,"Raw Materials",3},
        {"API","Raw Material","RM-2201-0010","4-Aminophenol","KG",520,800,2500,"Raw Materials",3},
        {"API","Raw Material","RM-2230-0051","2-Butyl-4-chloro-5-formyl imidazole","KG",4200,100,450,"Raw Materials",3},
        {"API","Raw Material","RM-2245-0019","Methanol","LTR",42,8000,20000,"Solvent RM",3},
        {"API","Intermediate","IN-3101-0004","Losartan trityl intermediate","KG",6800,60,220,"WIP / Semi FG",3},
        {"API","Intermediate","IN-3108-0012","Amlodipine phthaloyl base","KG",3900,80,300,"WIP / Semi FG",3},
        {"Formulation","Semi-Finished Goods","SF-4102-0031","Granules – Losartan 50 mg blend","KG",640,300,900,"WIP / Semi FG",3},
        {"Formulation","Semi-Finished Goods","SF-4110-0017","Coated cores – Amlodipine","KG",820,200,600,"WIP / Semi FG",2},
        {"API","Finished Goods","FG-5101-0001","Losartan potassium USP","KG",9800,40,160,"Finished Goods",3},
        {"API","Finished Goods","FG-5104-0003","Amlodipine besylate EP","KG",7400,40,140,"Finished Goods",3},
        {"Formulation","Finished Goods","FG-6201-0010","Losartan 50 mg tablets (10×10)","BOX",62,4000,15000,"Finished Goods",3},
        {"Formulation","Finished Goods","FG-6208-0014","Amlodipine 5 mg tablets (10×10)","BOX",38,5000,18000,"Finished Goods",3},
        {"API","Recovered Solvent","RS-7101-0001","Recovered methanol","LTR",24,5000,14000,"Recovered Mat.",3},
        {"API","Recovered Solvent","RS-7105-0002","Recovered toluene","LTR",38,3000,9000,"Recovered Mat.",2},
        {"Formulation","Packing Material","PK-3307-0012","HDPE container 500 ml","NOS",14,20000,60000,"Packing Material",3},
        {"Formulation","Packing Material","PK-3312-0091","Printed carton – Lot A","NOS",4.2,40000,120000,"Packing Material",3},
        {"Formulation","Packing Material","PK-3320-0044","Alu-Alu blister foil","KG",620,300,1200,"Packing Material",3},
        {"API","Packing Material","PK-3401-0007","HDPE drum 50 L","NOS",420,300,1200,"Packing Material",2},
        {"Formulation","Consumable","CN-5502-0078","Filter cartridge 0.22 µm","NOS",3400,20,80,"Engg Stores",2,"Engineering","Filters"},
        {"API","Consumable","CN-5520-0011","Mechanical seal kit","NOS",14500,4,15,"Engg Stores",2,"Engineering","Seals"},
        {"API","Consumable","CN-5524-0015","Bearing 6205 ZZ","NOS",420,40,160,"Engg Stores",2,"Engineering","Bearings"},
        {"Formulation","Consumable","CN-5601-0003","Sieve mesh 40#","NOS",2600,10,40,"Misc Items Store",2,"Production","Sieves"},
        {"Formulation","Consumable","CN-5605-0009","Punch & die set – D tooling","SET",48000,1,4,"Misc Items Store",1,"Production","Tooling"},
        {"API","Consumable","CN-5701-0005","HPLC column C18 250 mm","NOS",28000,3,12,"Control Sample",2,"QC","Columns"},
        {"API","Consumable","CN-5711-0018","Karl Fischer reagent","LTR",5200,10,40,"Control Sample",2,"QC","Reagents"},
        {"Formulation","Consumable","CN-5510-0032","Gowning kit (sterile)","SET",950,100,350,"General Stores",2,"EHS","PPE"},
        {"API","Consumable","CN-5807-0011","Chemical spill kit","SET",6400,5,20,"General Stores",1,"EHS","Emergency"},
        {"Formulation","Consumable","CN-5901-0002","Housekeeping consumables","SET",900,40,150,"General Stores",1,"Other","Housekeeping"}
    };
    private static final Map<String, int[]> SHELF = Map.of("Raw Material", new int[]{730, 1095, 1460}, "Intermediate", new int[]{180, 270, 365},
            "Semi-Finished Goods", new int[]{90, 180, 270}, "Finished Goods", new int[]{730, 1095}, "Recovered Solvent", new int[]{365, 540});
    private static final Map<String, Double> TARGET = Map.of("Raw Material", 6.1e7, "Finished Goods", 2.55e7, "Packing Material", 2.35e7,
            "Intermediate", 1.25e7, "Semi-Finished Goods", 0.67e7, "Consumable", 0.84e7, "Recovered Solvent", 0.52e7);

    @Transactional
    public Map<String, Object> load() {
        seed = 20261022L;
        LocalDate today = LocalDate.now();
        actions.deleteAllInBatch();
        inventory.deleteAllInBatch();
        Map<String, List<String>> byBu = plants.findAll().stream().filter(Plant::isActive)
                .collect(Collectors.groupingBy(Plant::getBuCode, Collectors.mapping(Plant::getName, Collectors.toList())));
        List<InventoryItem> recs = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Object[] c : C) {
            String bu = (String) c[0], type = (String) c[1], code = (String) c[2], name = (String) c[3], uom = (String) c[4], loc = (String) c[8];
            double cost = ((Number) c[5]).doubleValue(); int qmin = (Integer) c[6], qmax = (Integer) c[7], maxB = (Integer) c[9];
            List<String> pl = byBu.getOrDefault(bu, List.of());
            if (pl.isEmpty()) continue;
            boolean cons = "Consumable".equals(type);
            int nb = ri(1, maxB);
            for (int b = 0; b < nb; b++) {
                String plant = pick(pl);
                double r0 = rnd();
                int age = cons ? (r0 < .25 ? ri(5, 60) : r0 < .5 ? ri(61, 180) : r0 < .75 ? ri(181, 365) : ri(366, 720))
                        : (r0 < .32 ? ri(2, 30) : r0 < .52 ? ri(31, 60) : r0 < .67 ? ri(61, 90) : r0 < .86 ? ri(91, 180) : ri(181, 480));
                LocalDate receipt = today.minusDays(age);
                int qty = (int) Math.round(qmin + rnd() * (qmax - qmin));
                double u = qty, q = 0, bl = 0; String reason = null;
                double r = rnd();
                if (!cons && age <= 25 && r < .45) { q = qty; u = 0; }
                else if (r < .10) { bl = qty; u = 0; reason = pick(List.of("QC deviation – under investigation", "Vendor quality hold", "Damaged packaging", "OOS result – pending disposition")); }
                else if (r < .14 && !cons) { bl = Math.round(qty * .3); u = qty - bl; reason = "Partial rejection – sampling"; }
                LocalDate mfg = null, exp = null; String dt = "Expiry";
                if (SHELF.containsKey(type) || "Reagents".equals(c.length > 11 ? c[11] : null)) {
                    int[] opts = SHELF.getOrDefault(type, new int[]{540});
                    int sl = opts[(int) Math.floor(rnd() * opts.length)];
                    double rr = rnd();
                    int rem = rr < .04 ? -ri(3, 40) : rr < .10 ? ri(0, 30) : rr < .20 ? ri(31, 90) : rr < .34 ? ri(91, 180) : ri(181, sl);
                    exp = today.plusDays(rem); mfg = today.minusDays(sl - rem);
                    if (mfg.isAfter(receipt)) mfg = receipt.minusDays(ri(5, 40));
                    dt = "Raw Material".equals(type) || "Intermediate".equals(type) ? "Retest" : "Expiry";
                    if (rem < 0 && bl == 0) { bl = u; u = 0; q = 0; reason = "Expired – pending destruction"; }
                }
                LocalDate last = null;
                if (cons) { double r2 = rnd(); last = r2 < .45 ? today.minusDays(ri(1, 60)) : r2 < .7 ? today.minusDays(ri(61, 220)) : r2 < .85 ? today.minusDays(ri(221, 400)) : null; if (last != null && last.isBefore(receipt)) last = null; }
                else if (rnd() < .6 && age > 3) last = today.minusDays(ri(0, Math.min(age, 60)));
                String batch = cons ? (rnd() < .5 ? "" : "L" + ri(1000, 9999)) : Constants.SHORT.get(type) + ri(2401, 2699);
                if (!keys.add(plant + "|" + code + "|" + batch)) continue;
                InventoryItem it = new InventoryItem();
                it.setBuCode(bu); it.setPlant(plant); it.setMatType(type); it.setDepartment(cons ? (String) c[10] : null); it.setSubCategory(cons ? (String) c[11] : null);
                it.setCode(code); it.setName(name); it.setBatch(batch); it.setUom(uom); it.setStorageLoc(loc);
                it.setUnrestrictedQty(BigDecimal.valueOf(u)); it.setQualityQty(BigDecimal.valueOf(q)); it.setBlockedQty(BigDecimal.valueOf(bl));
                it.setUnitCost(BigDecimal.valueOf(cost)); it.setReceiptDate(receipt); it.setLyingSince(receipt); it.setMfgDate(mfg); it.setExpiryDate(exp);
                it.setDateType(dt); it.setLastIssueDate(last); it.setBlockReason(reason);
                recs.add(it);
            }
        }
        // scale costs so category values are realistic (~₹14 Cr holding)
        for (var e : TARGET.entrySet()) {
            List<InventoryItem> r = recs.stream().filter(x -> x.getMatType().equals(e.getKey())).toList();
            double tot = r.stream().mapToDouble(x -> x.getUnrestrictedQty().add(x.getQualityQty()).add(x.getBlockedQty()).doubleValue() * x.getUnitCost().doubleValue()).sum();
            if (tot > 0) r.forEach(x -> x.setUnitCost(x.getUnitCost().multiply(BigDecimal.valueOf(e.getValue() / tot)).setScale(2, RoundingMode.HALF_UP)));
        }
        inventory.saveAll(recs);
        inventory.flush();

        // sample actions on the top exceptions
        Thresholds t = settings.thresholds();
        List<CalcItem> exc = recs.stream().map(x -> calc.calc(x, t)).filter(x -> !x.realFlags().isEmpty()).sorted(Comparator.comparingInt(CalcItem::score).reversed()).limit(14).toList();
        String[] persons = {"R. Patil", "S. Shah", "A. Kulkarni", "M. Desai", "P. Nair", "V. Joshi", "K. Iyer"};
        Object[][] plan = {{"Closed", -6}, {"In Action", 3}, {"Owned", -4}, {"Owned", 8}, {"In Action", -17}, {"Prioritized", null}, {"Closed", -2}, {"Owned", 12},
                {"In Action", 5}, {"Owned", -9}, {"Closed", -10}, {"Owned", 20}, {"In Action", 1}, {"Prioritized", null}};
        int i = 0;
        for (CalcItem x : exc) {
            String stage = (String) plan[i][0]; Integer off = (Integer) plan[i][1];
            String[] s = ActionService.suggest(x);
            ActionItem a = new ActionItem();
            a.setId(String.format("ACT-%04d", actions.nextSeq()));
            a.setItemKey(x.itemKey()); a.setBuCode(x.bu()); a.setPlant(x.plant()); a.setCode(x.code()); a.setName(x.name()); a.setBatch(x.batch());
            a.setMatType(x.matType()); a.setValueAtRaise(BigDecimal.valueOf(Math.round(x.value())));
            a.setException(x.realFlags().stream().map(f -> CalcService.flagLabel(f, t)).collect(Collectors.joining(", ")));
            a.setActionType(s[0]); a.setPlan(s[1].isEmpty() ? "Review exception" : s[1]); a.setPriority(x.prio());
            a.setFunctionName(ActionService.defaultFn(x)); a.setPerson("Prioritized".equals(stage) ? null : persons[i % persons.length]);
            a.setCreatedDate(today.minusDays(ri(10, 30))); a.setTargetDate(off == null ? null : today.plusDays(off)); a.setStage(stage);
            a.setCreatedBy("sample");
            if ("Closed".equals(stage)) { a.setClosedDate(today.minusDays(Math.min(Math.abs(off), Math.max(0, today.getDayOfMonth() - 1)))); a.setClosure("Consumed / utilised — issued against process order"); a.setClosedBy(persons[0]); }
            actions.save(a);
            i++;
        }
        history(recs, t);
        settings.update(Map.of("sync.source", "Sample data (illustrative)", "sync.lastRefresh", java.time.OffsetDateTime.now().toString()), current.require().getUsername());
        audit.log("Sample data loaded", recs.size() + " records · " + exc.size() + " actions");
        return Map.of("records", recs.size(), "actions", exc.size());
    }

    /** Illustrative monthly history for the last three financial years. */
    private void history(List<InventoryItem> recs, Thresholds t) {
        snaps.deleteAll(); snaps.flush();
        LocalDate now = LocalDate.now();
        int fs = t.fyStart();
        int startY = (now.getMonthValue() >= fs ? now.getYear() : now.getYear() - 1) - 3;
        YearMonth ym = YearMonth.of(startY, fs);
        YearMonth stop = YearMonth.from(now);
        List<CalcItem> base = recs.stream().map(x -> calc.calc(x, t)).toList();
        while (ym.isBefore(stop)) {
            LocalDate d = ym.atEndOfMonth();
            double yearsBack = java.time.temporal.ChronoUnit.DAYS.between(d, now) / 365.25;
            double trend = Math.pow(1.085, -yearsBack) * (1 + 0.05 * Math.sin(ym.getMonthValue() / 12.0 * 2 * Math.PI));
            double risk = 1 + yearsBack * 0.12;
            List<CalcItem> scaled = base.stream().map(x -> {
                double f = trend * (1 + (rnd() - .5) * .12);
                return new CalcItem(x.id(), x.bu(), x.plant(), x.matType(), x.dept(), x.subCat(), x.code(), x.name(), x.batch(), x.uom(), x.storageLoc(),
                        x.unrestrictedQty(), x.qualityQty(), x.blockedQty(), x.unitCost(), x.receiptDate(), x.lyingSince(), x.mfgDate(), x.expiryDate(),
                        x.dateType(), x.lastIssueDate(), x.blockReason(), x.remarks(), x.qty(), x.value() * f, x.uVal() * f, x.qVal() * f, x.bVal() * f * risk,
                        x.lying(), x.age(), x.rsl(), x.dsi(), x.idle(), x.slTotal(), x.slUsed(), x.movement(), x.bucket(), x.band(), x.statuses(),
                        x.restricted(), x.consStatus(), x.flags(), x.score(), x.prio(), x.itemKey());
            }).toList();
            snapshots.take("sample", d, scaled);
            ym = ym.plusMonths(1);
        }
        snapshots.take("sample", now, base);
    }
}
