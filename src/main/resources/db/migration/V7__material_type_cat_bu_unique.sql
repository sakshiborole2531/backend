-- V7: Unique constraint on (category, business_unit) for Material Types when business_unit is specified
-- Exclude categories with empty/null business_unit since default legacy records can be unassigned

CREATE UNIQUE INDEX IF NOT EXISTS uq_material_type_cat_bu
    ON material_type_map(LOWER(category), LOWER(business_unit))
    WHERE business_unit IS NOT NULL AND TRIM(business_unit) <> '' AND LOWER(category) <> 'exclude';
