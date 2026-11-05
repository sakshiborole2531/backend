-- =====================================================================
-- V4: Business Unit generated ID, timestamps, organization support,
--     and Plant -> BusinessUnit (business_unit_id) foreign key relationship
-- =====================================================================

-- 1. Sequence and generated ID for business_unit (guaranteeing API=1, Formulation=2, Corporate=3)
CREATE SEQUENCE IF NOT EXISTS business_unit_id_seq START 1;

ALTER TABLE business_unit
    ADD COLUMN IF NOT EXISTS id BIGINT,
    ADD COLUMN IF NOT EXISTS organization_id BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Deterministically assign IDs to existing records preserving order (API=1, Formulation=2, Corporate=3, then others)
WITH ordered_bu AS (
    SELECT code,
           ROW_NUMBER() OVER (
               ORDER BY
                   CASE code
                       WHEN 'API' THEN 1
                       WHEN 'Formulation' THEN 2
                       WHEN 'Corporate' THEN 3
                       ELSE 10
                   END,
                   CASE WHEN sort_order > 0 THEN sort_order ELSE 9999 END,
                   code ASC
           ) AS rn
    FROM business_unit
    WHERE id IS NULL
)
UPDATE business_unit b
SET id = o.rn
FROM ordered_bu o
WHERE b.code = o.code;

SELECT setval(
    'business_unit_id_seq',
    COALESCE((SELECT MAX(id) FROM business_unit), 1),
    (SELECT COUNT(*) > 0 FROM business_unit)
);

ALTER TABLE business_unit
    ALTER COLUMN id SET DEFAULT nextval('business_unit_id_seq'),
    ALTER COLUMN id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_business_unit_id'
    ) THEN
        ALTER TABLE business_unit ADD CONSTRAINT uq_business_unit_id UNIQUE (id);
    END IF;
END $$;

-- Case-insensitive unique Business Unit name (code) per organization
CREATE UNIQUE INDEX IF NOT EXISTS uq_business_unit_org_code
    ON business_unit (organization_id, lower(code));

-- 2. Link plant to business_unit via business_unit_id foreign key (NO CASCADE DELETE)
ALTER TABLE plant
    ADD COLUMN IF NOT EXISTS business_unit_id BIGINT;

UPDATE plant p
SET business_unit_id = b.id
FROM business_unit b
WHERE p.bu_code = b.code
  AND p.business_unit_id IS NULL;

ALTER TABLE plant
    ALTER COLUMN business_unit_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_plant_business_unit_id'
    ) THEN
        ALTER TABLE plant
            ADD CONSTRAINT fk_plant_business_unit_id
            FOREIGN KEY (business_unit_id)
            REFERENCES business_unit(id)
            ON UPDATE CASCADE
            ON DELETE RESTRICT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_plant_business_unit_id ON plant(business_unit_id);
