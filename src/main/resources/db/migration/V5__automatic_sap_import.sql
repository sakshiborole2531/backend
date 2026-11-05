-- =====================================================================
-- V5: Automatic Daily SAP Inventory Import Configuration & Import History
-- =====================================================================

CREATE TABLE IF NOT EXISTS automatic_import_configuration (
    id                           BIGSERIAL     PRIMARY KEY,
    organization_id              BIGINT        NOT NULL DEFAULT 1,
    enabled                      BOOLEAN       NOT NULL DEFAULT FALSE,
    configured_folder_path       VARCHAR(500)  NOT NULL DEFAULT 'C:\Inventory_Sap',
    configured_file_name         VARCHAR(255)  NOT NULL DEFAULT 'SAP_Inventory.xlsx',
    configured_file_path         VARCHAR(760)  NOT NULL DEFAULT 'C:\Inventory_Sap\SAP_Inventory.xlsx',
    import_frequency_hours       INT           NOT NULL DEFAULT 24 CHECK (import_frequency_hours > 0),
    import_mode                  VARCHAR(40)   NOT NULL DEFAULT 'REPLACE_IN_SCOPE',
    skip_unchanged_file          BOOLEAN       NOT NULL DEFAULT TRUE,
    scope_bu_code                VARCHAR(40),
    scope_plant_name             VARCHAR(80),
    first_execution_at           TIMESTAMPTZ,
    last_execution_at            TIMESTAMPTZ,
    last_successful_import_at    TIMESTAMPTZ,
    last_imported_file_modified_at TIMESTAMPTZ,
    last_imported_file_size      BIGINT,
    next_execution_at            TIMESTAMPTZ,
    last_import_status           VARCHAR(40)   NOT NULL DEFAULT 'IDLE',
    last_import_message          VARCHAR(2000),
    lock_acquired_at             TIMESTAMPTZ,
    locked_by                    VARCHAR(120),
    created_at                   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by                   VARCHAR(60),
    updated_by                   VARCHAR(60),
    version                      BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_auto_import_org UNIQUE (organization_id)
);

CREATE TABLE IF NOT EXISTS import_history (
    id                   BIGSERIAL     PRIMARY KEY,
    configuration_id     BIGINT        REFERENCES automatic_import_configuration(id) ON DELETE SET NULL,
    file_name            VARCHAR(255)  NOT NULL,
    file_path            VARCHAR(760)  NOT NULL,
    file_size            BIGINT        NOT NULL DEFAULT 0,
    file_modified_at     TIMESTAMPTZ,
    import_type          VARCHAR(20)   NOT NULL CHECK (import_type IN ('MANUAL', 'AUTOMATIC')),
    import_started_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    import_completed_at  TIMESTAMPTZ,
    total_records        INT           NOT NULL DEFAULT 0,
    successful_records   INT           NOT NULL DEFAULT 0,
    failed_records       INT           NOT NULL DEFAULT 0,
    added_records        INT           NOT NULL DEFAULT 0,
    updated_records      INT           NOT NULL DEFAULT 0,
    status               VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED')),
    error_message        VARCHAR(2000),
    executed_by          VARCHAR(60),
    execution_duration   BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS ix_import_history_started_at ON import_history(import_started_at DESC);
CREATE INDEX IF NOT EXISTS ix_import_history_config_id ON import_history(configuration_id);
CREATE INDEX IF NOT EXISTS ix_import_history_status ON import_history(status);

-- Seed default configuration for organization 1 if not already present
INSERT INTO automatic_import_configuration (
    organization_id,
    enabled,
    configured_folder_path,
    configured_file_name,
    configured_file_path,
    import_frequency_hours,
    import_mode,
    skip_unchanged_file,
    last_import_status,
    created_by
)
SELECT
    1,
    FALSE,
    'C:\Inventory_Sap',
    'SAP_Inventory.xlsx',
    'C:\Inventory_Sap\SAP_Inventory.xlsx',
    24,
    'REPLACE_IN_SCOPE',
    TRUE,
    'DISABLED',
    'system'
WHERE NOT EXISTS (
    SELECT 1 FROM automatic_import_configuration WHERE organization_id = 1
);
