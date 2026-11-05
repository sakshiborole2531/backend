package com.unichem.inventory.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fixed vocabularies of the operating model (mirrors the original dashboard). */
public final class Constants {
    private Constants() {}
    public static final List<String> MAT_TYPES = List.of("Raw Material", "Intermediate", "Semi-Finished Goods",
            "Finished Goods", "Recovered Solvent", "Packing Material", "Consumable");
    /** Categories where an SLED/BBD (expiry or retest date) is expected. */
    public static final Set<String> SHELF_TYPES = Set.of("Raw Material", "Intermediate", "Semi-Finished Goods",
            "Finished Goods", "Recovered Solvent");
    public static final List<String> DEPTS = List.of("Production", "Engineering", "QC", "EHS", "Other");
    public static final List<String> STAGES = List.of("Prioritized", "Owned", "In Action", "Closed");
    public static final Map<String, String> SHORT = Map.of("Raw Material", "RM", "Intermediate", "INT",
            "Semi-Finished Goods", "SFG", "Finished Goods", "FG", "Recovered Solvent", "RS",
            "Packing Material", "PM", "Consumable", "CON");
    public static final List<String> PERMISSIONS = List.of("view.inventory", "view.years", "view.actions",
            "action.create", "action.update", "action.update.own", "action.close", "action.close.own", "action.delete",
            "report.pdf", "export.data", "import.data", "data.edit", "data.approve", "masters.edit", "sap.config",
            "data.clear.bu", "data.clear", "users.manage", "audit.view");
    /** Permissions that need a reason when granted and periodic review. */
    public static final Set<String> PRIVILEGED = Set.of("masters.edit", "sap.config", "data.clear.bu", "data.clear",
            "users.manage", "audit.view", "data.approve");
}
