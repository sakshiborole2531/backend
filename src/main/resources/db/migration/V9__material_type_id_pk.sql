-- V9: Make (id) the primary key of material_type_map so multiple rows with the same material_type can coexist under different categories/business units
-- Drop existing primary key on sap_code
ALTER TABLE material_type_map DROP CONSTRAINT material_type_map_pkey;

-- Add id column as BIGSERIAL PRIMARY KEY
ALTER TABLE material_type_map ADD COLUMN id BIGSERIAL PRIMARY KEY;

-- Ensure composite unique index exists on (sap_code, category, business_unit)
DROP INDEX IF EXISTS uq_material_type_composite;
CREATE UNIQUE INDEX uq_material_type_composite
    ON material_type_map(LOWER(sap_code), LOWER(category), LOWER(COALESCE(business_unit, '')));
