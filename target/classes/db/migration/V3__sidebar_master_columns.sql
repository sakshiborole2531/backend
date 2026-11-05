-- Add active and sort_order columns to master tables so all sidebar masters support activation/deactivation and ordering

ALTER TABLE business_unit
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0;

UPDATE business_unit SET sort_order = CASE code
    WHEN 'API' THEN 1
    WHEN 'Formulation' THEN 2
    WHEN 'Corporate' THEN 3
    ELSE 99
END WHERE sort_order = 0;

ALTER TABLE storage_location
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0;

WITH ordered_sloc AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY code ASC, id ASC) AS rn
    FROM storage_location
)
UPDATE storage_location s
SET sort_order = o.rn
FROM ordered_sloc o
WHERE s.id = o.id AND s.sort_order = 0;

ALTER TABLE material_type_map
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0;

WITH ordered_mtype AS (
    SELECT sap_code, ROW_NUMBER() OVER (ORDER BY sap_code ASC) AS rn
    FROM material_type_map
)
UPDATE material_type_map m
SET sort_order = o.rn
FROM ordered_mtype o
WHERE m.sap_code = o.sap_code AND m.sort_order = 0;
