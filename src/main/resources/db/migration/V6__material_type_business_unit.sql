-- V6: Add business_unit to material_type_map
ALTER TABLE material_type_map
    ADD COLUMN IF NOT EXISTS business_unit VARCHAR(120);

CREATE INDEX IF NOT EXISTS ix_mtype_bu ON material_type_map(business_unit);
