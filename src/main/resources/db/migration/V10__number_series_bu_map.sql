-- V10: Create table for Material Number Series by Business Unit mapping
CREATE TABLE IF NOT EXISTS number_series_bu_map (
    id BIGSERIAL PRIMARY KEY,
    series_pattern VARCHAR(60) NOT NULL,
    business_unit VARCHAR(60) NOT NULL,
    category VARCHAR(60) NOT NULL,
    department VARCHAR(60),
    description VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0
);

-- Ensure active combinations of (series_pattern, business_unit) are unique
CREATE UNIQUE INDEX IF NOT EXISTS uq_number_series_bu_active
    ON number_series_bu_map(LOWER(TRIM(series_pattern)), LOWER(TRIM(business_unit)))
    WHERE active = TRUE;

-- Seed default initial mappings: 100* is FG for API, RM for Formulation
INSERT INTO number_series_bu_map (series_pattern, business_unit, category, department, description, active, sort_order)
VALUES
    ('100*', 'API', 'Finished Goods', 'All', 'API 100-series Finished Goods', TRUE, 1),
    ('100*', 'Formulation', 'Raw Material', 'All', 'Formulation 100-series Raw Material', TRUE, 2)
ON CONFLICT DO NOTHING;
