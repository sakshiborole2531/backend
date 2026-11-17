-- =====================================================================
-- V11: Ensure active (BOOLEAN NOT NULL DEFAULT TRUE) and sort_order
--      columns exist on all master tables:
--      business_unit, storage_location, material_type_map,
--      material_group_map, responsible_function, action_type,
--      responsible_person, plant, and number_series_bu_map.
-- =====================================================================

ALTER TABLE business_unit
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0;

ALTER TABLE plant
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0;

ALTER TABLE storage_location
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0;

ALTER TABLE material_type_map
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0;

ALTER TABLE material_group_map
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0;

WITH ordered_mgroup AS (
    SELECT sap_code, ROW_NUMBER() OVER (ORDER BY sap_code ASC) AS rn
    FROM material_group_map
)
UPDATE material_group_map g
SET sort_order = o.rn
FROM ordered_mgroup o
WHERE g.sap_code = o.sap_code AND g.sort_order = 0;

ALTER TABLE responsible_function
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0;

ALTER TABLE action_type
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0;

ALTER TABLE responsible_person
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0;
