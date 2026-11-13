-- V8: Update unique constraint on Material Types to composite (material_type, dashboard_category, business_unit)
-- Drop previous category + business_unit unique index
DROP INDEX IF EXISTS uq_material_type_cat_bu;

-- Drop single-column unique constraint on sap_code if present
ALTER TABLE material_type_map DROP CONSTRAINT IF EXISTS uq_material_type_code;

-- Create composite unique index on (sap_code, category, business_unit)
-- Treating NULL/empty business_unit uniformly with COALESCE
CREATE UNIQUE INDEX IF NOT EXISTS uq_material_type_composite
    ON material_type_map(LOWER(sap_code), LOWER(category), LOWER(COALESCE(business_unit, '')));
