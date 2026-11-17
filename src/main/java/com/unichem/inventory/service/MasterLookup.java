package com.unichem.inventory.service;

import com.unichem.inventory.domain.*;
import com.unichem.inventory.repo.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

import static com.unichem.inventory.domain.Constants.MAT_TYPES;

/** Resolves free text / SAP codes to master values (plant, BU, category, department, storage location). */
@Component
public class MasterLookup {
    private static final Pattern SFG_PATTERN = Pattern.compile(".*(semi|bulk|halb|in-?process|granul|blend|core).*");
    private static final Pattern CONS_PATTERN = Pattern.compile(".*(consum|spare).*");

    private final PlantRepository plants;
    private final BusinessUnitRepository bus;
    private final MaterialTypeMapRepository mtypes;
    private final MaterialGroupMapRepository mgroups;
    private final StorageLocationRepository slocs;
    private final NumberSeriesBuMapRepository numSeries;

    @Autowired
    public MasterLookup(PlantRepository plants, BusinessUnitRepository bus, MaterialTypeMapRepository mtypes,
                        MaterialGroupMapRepository mgroups, StorageLocationRepository slocs,
                        @Autowired(required = false) NumberSeriesBuMapRepository numSeries) {
        this.plants = plants;
        this.bus = bus;
        this.mtypes = mtypes;
        this.mgroups = mgroups;
        this.slocs = slocs;
        this.numSeries = numSeries;
    }

    public MasterLookup(PlantRepository plants, BusinessUnitRepository bus, MaterialTypeMapRepository mtypes,
                        MaterialGroupMapRepository mgroups, StorageLocationRepository slocs) {
        this(plants, bus, mtypes, mgroups, slocs, null);
    }

    /** Snapshot of master data for a batch of rows (one query per master, not per row). */
    public Ctx ctx() {
        List<NumberSeriesBuMap> ns = numSeries != null ? numSeries.findByActiveTrueOrderBySortOrderAscSeriesPatternAsc() : List.of();
        return new Ctx(plants.findAll(), bus.findAll(), mtypes.findAll(), mgroups.findAll(), slocs.findAll(), ns != null ? ns : List.of());
    }

    public record Ctx(List<Plant> plants, List<BusinessUnit> bus, List<MaterialTypeMap> mtypes, List<MaterialGroupMap> mgroups, List<StorageLocation> slocs, List<NumberSeriesBuMap> numSeries) {
        public Optional<Plant> plant(String v) {
            String t = v == null ? "" : v.trim();
            return plants.stream().filter(p -> p.getSapCode() != null && p.getSapCode().equalsIgnoreCase(t)).findFirst()
                    .or(() -> plants.stream().filter(p -> p.getName().equalsIgnoreCase(t)).findFirst());
        }
        public String bu(String v) {
            if (v == null || v.isBlank()) return "";
            String t = v.trim().toLowerCase();
            for (BusinessUnit b : bus) {
                if (!b.isActive()) continue;
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
                        .filter(MaterialTypeMap::isActive)
                        .filter(m -> m.getSapCode().equalsIgnoreCase(t) && m.getBusinessUnit() != null && m.getBusinessUnit().equalsIgnoreCase(b))
                        .findFirst();
                if (match.isPresent()) return match;
            }
            return mtypes.stream().filter(MaterialTypeMap::isActive).filter(m -> m.getSapCode().equalsIgnoreCase(t)).findFirst();
        }
        public Optional<MaterialGroupMap> mgroup(String v) {
            return v == null ? Optional.empty() : mgroups.stream().filter(MaterialGroupMap::isActive).filter(m -> m.getSapCode().equalsIgnoreCase(v.trim())).findFirst();
        }
        public Optional<StorageLocation> sloc(String v, String plant) {
            if (v == null || v.isBlank()) return Optional.empty();
            String t = v.trim();
            return slocs.stream()
                    .filter(StorageLocation::isActive)
                    .filter(l -> l.getCode().equalsIgnoreCase(t) && (l.getPlantName() == null || l.getPlantName().equals(plant))).findFirst()
                    .or(() -> slocs.stream().filter(StorageLocation::isActive).filter(l -> l.getName().equalsIgnoreCase(t)).findFirst());
        }
        /** Category from a SAP material type, an exact category name, or free text. */
        public String category(String v) {
            if (v == null || v.isBlank()) return "";
            Optional<MaterialTypeMap> m = mtype(v);
            if (m.isPresent() && m.get().getCategory() != null && MAT_TYPES.contains(m.get().getCategory())) return m.get().getCategory();
            return catFromText(v);
        }

        /**
         * Resolves category dynamically based on Material Code/Series pattern + Business Unit (BU).
         * Prioritizes longer/more specific pattern matches for the given BU.
         * Falls back to default category if no pattern matches.
         */
        public String resolveCategory(String materialCode, String bu, String fallbackCategory) {
            if (materialCode != null && !materialCode.isBlank() && bu != null && !bu.isBlank() && numSeries != null && !numSeries.isEmpty()) {
                String codeTrimmed = materialCode.trim();
                String buNorm = bu(bu);
                if (buNorm.isBlank()) buNorm = bu.trim();

                final String targetBu = buNorm;

                // Find matching active series for this BU, sorted by pattern specificity (longer prefix first)
                Optional<NumberSeriesBuMap> match = numSeries.stream()
                        .filter(ns -> Boolean.TRUE.equals(ns.getActive()))
                        .filter(ns -> {
                            String entryBu = ns.getBusinessUnit() != null ? ns.getBusinessUnit().trim() : "";
                            return entryBu.equalsIgnoreCase("All") || entryBu.equalsIgnoreCase(targetBu) || entryBu.equalsIgnoreCase(bu.trim());
                        })
                        .filter(ns -> matchesSeries(codeTrimmed, ns.getSeriesPattern()))
                        .sorted((a, b) -> {
                            // Specific BU match ranks higher than "All"
                            boolean aSpecific = !a.getBusinessUnit().equalsIgnoreCase("All");
                            boolean bSpecific = !b.getBusinessUnit().equalsIgnoreCase("All");
                            if (aSpecific != bSpecific) return aSpecific ? -1 : 1;
                            // Longer pattern ranks higher
                            return Integer.compare(b.getSeriesPattern().length(), a.getSeriesPattern().length());
                        })
                        .findFirst();

                if (match.isPresent() && match.get().getCategory() != null && !match.get().getCategory().isBlank()) {
                    return match.get().getCategory().trim();
                }
            }

            if (fallbackCategory != null && !fallbackCategory.isBlank()) {
                return category(fallbackCategory);
            }
            return "";
        }

        private static boolean matchesSeries(String code, String pattern) {
            if (code == null || pattern == null || pattern.isBlank()) return false;
            String p = pattern.trim();
            if (p.equals("*")) return true;
            if (p.endsWith("*")) {
                String prefix = p.substring(0, p.length() - 1);
                return code.regionMatches(true, 0, prefix, 0, prefix.length());
            }
            if (p.contains("*")) {
                String regex = "^" + Pattern.quote(p).replace("*", "\\E.*\\Q") + "$";
                return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(code).matches();
            }
            return code.equalsIgnoreCase(p);
        }
    }

    public static String catFromText(String v) {
        String t = v == null ? "" : v.trim();
        if (t.isEmpty()) return "";
        for (String m : MAT_TYPES) if (m.equalsIgnoreCase(t)) return m;
        String l = t.toLowerCase();
        if (SFG_PATTERN.matcher(l).matches() || l.equals("sfg")) return "Semi-Finished Goods";
        if (CONS_PATTERN.matcher(l).matches()) return "Consumable";
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
