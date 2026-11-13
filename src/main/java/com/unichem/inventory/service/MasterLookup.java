package com.unichem.inventory.service;

import com.unichem.inventory.domain.*;
import com.unichem.inventory.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.unichem.inventory.domain.Constants.MAT_TYPES;

/** Resolves free text / SAP codes to master values (plant, BU, category, department, storage location). */
@Component
@RequiredArgsConstructor
public class MasterLookup {
    private final PlantRepository plants;
    private final BusinessUnitRepository bus;
    private final MaterialTypeMapRepository mtypes;
    private final MaterialGroupMapRepository mgroups;
    private final StorageLocationRepository slocs;

    /** Snapshot of master data for a batch of rows (one query per master, not per row). */
    public Ctx ctx() { return new Ctx(plants.findAll(), bus.findAll(), mtypes.findAll(), mgroups.findAll(), slocs.findAll()); }

    public record Ctx(List<Plant> plants, List<BusinessUnit> bus, List<MaterialTypeMap> mtypes, List<MaterialGroupMap> mgroups, List<StorageLocation> slocs) {
        public Optional<Plant> plant(String v) {
            String t = v == null ? "" : v.trim();
            return plants.stream().filter(p -> p.getSapCode() != null && p.getSapCode().equalsIgnoreCase(t)).findFirst()
                    .or(() -> plants.stream().filter(p -> p.getName().equalsIgnoreCase(t)).findFirst());
        }
        public String bu(String v) {
            if (v == null || v.isBlank()) return "";
            String t = v.trim().toLowerCase();
            for (BusinessUnit b : bus) {
                if (b.getCode().equalsIgnoreCase(t) || b.getName().equalsIgnoreCase(t)) return b.getCode();
                if (b.getAlias() != null && Arrays.stream(b.getAlias().split("[,;|]")).map(String::trim).anyMatch(a -> a.equalsIgnoreCase(t))) return b.getCode();
            }
            return "";
        }
        public Optional<MaterialTypeMap> mtype(String v) { return mtype(v, null); }
        public Optional<MaterialTypeMap> mtype(String v, String bu) {
            if (v == null || v.isBlank()) return Optional.empty();
            String t = v.trim();
            if (bu != null && !bu.isBlank()) {
                String b = bu.trim();
                Optional<MaterialTypeMap> match = mtypes.stream()
                        .filter(m -> m.getSapCode().equalsIgnoreCase(t) && m.getBusinessUnit() != null && m.getBusinessUnit().equalsIgnoreCase(b))
                        .findFirst();
                if (match.isPresent()) return match;
            }
            return mtypes.stream().filter(m -> m.getSapCode().equalsIgnoreCase(t)).findFirst();
        }
        public Optional<MaterialGroupMap> mgroup(String v) { return v == null ? Optional.empty() : mgroups.stream().filter(m -> m.getSapCode().equalsIgnoreCase(v.trim())).findFirst(); }
        public Optional<StorageLocation> sloc(String v, String plant) {
            if (v == null || v.isBlank()) return Optional.empty();
            String t = v.trim();
            return slocs.stream().filter(l -> l.getCode().equalsIgnoreCase(t) && (l.getPlantName() == null || l.getPlantName().equals(plant))).findFirst()
                    .or(() -> slocs.stream().filter(l -> l.getName().equalsIgnoreCase(t)).findFirst());
        }
        /** Category from a SAP material type, an exact category name, or free text. */
        public String category(String v) {
            if (v == null || v.isBlank()) return "";
            Optional<MaterialTypeMap> m = mtype(v);
            if (m.isPresent() && m.get().getCategory() != null && MAT_TYPES.contains(m.get().getCategory())) return m.get().getCategory();
            return catFromText(v);
        }
    }

    public static String catFromText(String v) {
        String t = v == null ? "" : v.trim();
        if (t.isEmpty()) return "";
        for (String m : MAT_TYPES) if (m.equalsIgnoreCase(t)) return m;
        String l = t.toLowerCase();
        if (l.matches(".*(semi|bulk|halb|in-?process|granul|blend|core).*") || l.equals("sfg")) return "Semi-Finished Goods";
        if (l.matches(".*(consum|spare).*")) return "Consumable";
        if (l.contains("solvent")) return "Recovered Solvent";
        if (l.contains("pack") || l.equals("pm")) return "Packing Material";
        if (l.contains("finish") || l.equals("fg")) return "Finished Goods";
        if (l.contains("intermed") || l.equals("int")) return "Intermediate";
        if (l.contains("raw") || l.equals("rm")) return "Raw Material";
        return "";
    }

    public static String dept(String v) {
        if (v == null) return "";
        String t = v.trim();
        if (t.toLowerCase().contains("quality")) return "QC";
        return Constants.DEPTS.stream().filter(d -> d.equalsIgnoreCase(t)).findFirst().orElse("");
    }
}
