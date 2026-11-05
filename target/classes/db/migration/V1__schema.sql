-- =====================================================================
-- Unichem Inventory Management — PostgreSQL schema
-- Visibility → Identification → Prioritization → Ownership → Action → Closure
-- =====================================================================

-- ---------- Organization masters ----------
CREATE TABLE business_unit (
    code        VARCHAR(40)  PRIMARY KEY,
    name        VARCHAR(160) NOT NULL,
    alias       VARCHAR(120)
);

CREATE TABLE plant (
    id          BIGSERIAL    PRIMARY KEY,
    sap_code    VARCHAR(10)  UNIQUE,
    name        VARCHAR(80)  NOT NULL UNIQUE,
    bu_code     VARCHAR(40)  NOT NULL REFERENCES business_unit(code) ON UPDATE CASCADE,
    city        VARCHAR(80),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order  INT          NOT NULL DEFAULT 0
);
CREATE INDEX ix_plant_bu ON plant(bu_code);

CREATE TABLE storage_location (
    id           BIGSERIAL   PRIMARY KEY,
    plant_name   VARCHAR(80) REFERENCES plant(name) ON UPDATE CASCADE ON DELETE CASCADE,
    code         VARCHAR(10) NOT NULL,
    name         VARCHAR(80) NOT NULL,
    storage_type VARCHAR(40) NOT NULL DEFAULT 'General',
    CONSTRAINT uq_sloc UNIQUE (code, name, plant_name)
);
CREATE INDEX ix_sloc_code ON storage_location(code);

CREATE TABLE material_type_map (
    sap_code      VARCHAR(10)  PRIMARY KEY,
    description   VARCHAR(120),
    number_series VARCHAR(40),
    category      VARCHAR(40)  NOT NULL,
    department    VARCHAR(20),
    CONSTRAINT ck_mtype_cat CHECK (category IN ('Raw Material','Intermediate','Semi-Finished Goods','Finished Goods','Recovered Solvent','Packing Material','Consumable','Exclude')),
    CONSTRAINT ck_mtype_dept CHECK (department IS NULL OR department IN ('Production','Engineering','QC','EHS','Other'))
);

CREATE TABLE material_group_map (
    sap_code     VARCHAR(10)  PRIMARY KEY,
    description  VARCHAR(120),
    category     VARCHAR(40),
    department   VARCHAR(20),
    sub_category VARCHAR(120),
    CONSTRAINT ck_mgroup_cat CHECK (category IS NULL OR category IN ('Raw Material','Intermediate','Semi-Finished Goods','Finished Goods','Recovered Solvent','Packing Material','Consumable','Exclude')),
    CONSTRAINT ck_mgroup_dept CHECK (department IS NULL OR department IN ('Production','Engineering','QC','EHS','Other'))
);

CREATE TABLE responsible_function (
    name        VARCHAR(60) PRIMARY KEY,
    sort_order  INT NOT NULL DEFAULT 0
);

CREATE TABLE action_type (
    name        VARCHAR(80) PRIMARY KEY,
    sort_order  INT NOT NULL DEFAULT 0
);

CREATE TABLE responsible_person (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(120) NOT NULL UNIQUE,
    function_name VARCHAR(60) REFERENCES responsible_function(name) ON UPDATE CASCADE ON DELETE SET NULL,
    email       VARCHAR(160)
);

-- Thresholds and other scalar settings (expiryDays, agingDays, ...)
CREATE TABLE app_setting (
    key         VARCHAR(60)  PRIMARY KEY,
    value       VARCHAR(400) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by  VARCHAR(60)
);

-- ---------- Users, roles, privileges ----------
CREATE TABLE app_role (
    code        VARCHAR(40)  PRIMARY KEY,
    label       VARCHAR(120) NOT NULL,
    builtin     BOOLEAN      NOT NULL DEFAULT FALSE,
    description VARCHAR(400)
);

CREATE TABLE role_permission (
    role_code   VARCHAR(40) NOT NULL REFERENCES app_role(code) ON DELETE CASCADE,
    permission  VARCHAR(40) NOT NULL,
    PRIMARY KEY (role_code, permission)
);

CREATE TABLE app_user (
    id             BIGSERIAL    PRIMARY KEY,
    username       VARCHAR(60)  NOT NULL,
    name           VARCHAR(120) NOT NULL,
    email          VARCHAR(160),
    phone          VARCHAR(40),
    emp_id         VARCHAR(40),
    designation    VARCHAR(120),
    department     VARCHAR(120),
    location       VARCHAR(120),
    function_name  VARCHAR(60),
    role_code      VARCHAR(40)  NOT NULL REFERENCES app_role(code),
    scope_all      BOOLEAN      NOT NULL DEFAULT FALSE,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    valid_until    DATE,
    password_hash  VARCHAR(100) NOT NULL,
    must_change    BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_logins  INT          NOT NULL DEFAULT 0,
    locked_until   TIMESTAMPTZ,
    last_login     TIMESTAMPTZ,
    pw_changed     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(60),
    theme          VARCHAR(10),
    CONSTRAINT ck_username CHECK (username ~ '^[A-Za-z0-9._-]{3,}$')
);
CREATE UNIQUE INDEX uq_user_username ON app_user (lower(username));
CREATE INDEX ix_user_role ON app_user(role_code);

CREATE TABLE user_scope_bu (
    user_id  BIGINT      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    bu_code  VARCHAR(40) NOT NULL REFERENCES business_unit(code) ON UPDATE CASCADE ON DELETE CASCADE,
    PRIMARY KEY (user_id, bu_code)
);
CREATE TABLE user_scope_unit (
    user_id    BIGINT      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    plant_name VARCHAR(80) NOT NULL REFERENCES plant(name) ON UPDATE CASCADE ON DELETE CASCADE,
    PRIMARY KEY (user_id, plant_name)
);

-- Individual privileges: grant / deny a permission for one user, optionally until a date
CREATE TABLE user_grant (
    user_id     BIGINT      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    permission  VARCHAR(40) NOT NULL,
    mode        VARCHAR(5)  NOT NULL CHECK (mode IN ('grant','deny')),
    until_date  DATE,
    granted_by  VARCHAR(120),
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason      VARCHAR(400),
    PRIMARY KEY (user_id, permission)
);

CREATE TABLE access_request (
    id            BIGSERIAL    PRIMARY KEY,
    requested_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status        VARCHAR(10)  NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','rejected')),
    name          VARCHAR(120) NOT NULL,
    username      VARCHAR(60)  NOT NULL,
    email         VARCHAR(160) NOT NULL,
    emp_id        VARCHAR(40),
    designation   VARCHAR(120),
    department    VARCHAR(120),
    phone         VARCHAR(40),
    bu_code       VARCHAR(40),
    unit          VARCHAR(80),
    role_code     VARCHAR(40),
    justification VARCHAR(400) NOT NULL,
    decided_by    VARCHAR(120),
    decided_at    TIMESTAMPTZ,
    reason        VARCHAR(400)
);
CREATE INDEX ix_access_request_status ON access_request(status);

-- ---------- Inventory ----------
CREATE TABLE inventory_item (
    id               BIGSERIAL      PRIMARY KEY,
    bu_code          VARCHAR(40)    NOT NULL,
    plant            VARCHAR(80)    NOT NULL,
    mat_type         VARCHAR(40)    NOT NULL,
    department       VARCHAR(20),
    sub_category     VARCHAR(120),
    code             VARCHAR(40)    NOT NULL,
    name             VARCHAR(200)   NOT NULL,
    batch            VARCHAR(40)    NOT NULL DEFAULT '',
    uom              VARCHAR(10),
    storage_loc      VARCHAR(80),
    unrestricted_qty NUMERIC(18,3)  NOT NULL DEFAULT 0,
    quality_qty      NUMERIC(18,3)  NOT NULL DEFAULT 0,
    blocked_qty      NUMERIC(18,3)  NOT NULL DEFAULT 0,
    unit_cost        NUMERIC(18,4)  NOT NULL DEFAULT 0,
    receipt_date     DATE,
    lying_since      DATE,
    mfg_date         DATE,
    expiry_date      DATE,
    date_type        VARCHAR(10)    NOT NULL DEFAULT 'Expiry' CHECK (date_type IN ('Expiry','Retest')),
    last_issue_date  DATE,
    block_reason     VARCHAR(200),
    remarks          VARCHAR(500),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_inventory_key UNIQUE (plant, code, batch),
    CONSTRAINT ck_inv_qty CHECK (unrestricted_qty >= 0 AND quality_qty >= 0 AND blocked_qty >= 0),
    CONSTRAINT ck_inv_cost CHECK (unit_cost >= 0),
    CONSTRAINT ck_inv_type CHECK (mat_type IN ('Raw Material','Intermediate','Semi-Finished Goods','Finished Goods','Recovered Solvent','Packing Material','Consumable')),
    CONSTRAINT ck_inv_dept CHECK ((mat_type = 'Consumable' AND department IS NOT NULL AND department IN ('Production','Engineering','QC','EHS','Other')) OR (mat_type <> 'Consumable' AND department IS NULL))
);
CREATE INDEX ix_inv_scope   ON inventory_item(bu_code, plant);
CREATE INDEX ix_inv_type    ON inventory_item(mat_type);
CREATE INDEX ix_inv_expiry  ON inventory_item(expiry_date);
CREATE INDEX ix_inv_receipt ON inventory_item(receipt_date);
CREATE INDEX ix_inv_code    ON inventory_item(code);

-- ---------- Actions (Ownership → Action → Closure) ----------
CREATE SEQUENCE action_seq START 1;

CREATE TABLE action_item (
    id             VARCHAR(12)   PRIMARY KEY,           -- ACT-0001
    item_key       VARCHAR(200),                        -- plant|code|batch (survives SAP refreshes)
    bu_code        VARCHAR(40),
    plant          VARCHAR(80),
    code           VARCHAR(40),
    name           VARCHAR(200),
    batch          VARCHAR(40),
    mat_type       VARCHAR(40),
    value_at_raise NUMERIC(18,2),
    exception      VARCHAR(300),
    action_type    VARCHAR(80)   NOT NULL,
    plan           VARCHAR(1000) NOT NULL,
    priority       VARCHAR(2)    NOT NULL DEFAULT 'P3' CHECK (priority IN ('P1','P2','P3')),
    function_name  VARCHAR(60)   NOT NULL,
    person         VARCHAR(120),
    created_date   DATE          NOT NULL DEFAULT CURRENT_DATE,
    target_date    DATE,
    stage          VARCHAR(12)   NOT NULL DEFAULT 'Prioritized' CHECK (stage IN ('Prioritized','Owned','In Action','Closed')),
    remarks        VARCHAR(500),
    closed_date    DATE,
    closure        VARCHAR(1000),
    closed_by      VARCHAR(120),
    created_by     VARCHAR(60),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_action_closed CHECK (stage <> 'Closed' OR (closed_date IS NOT NULL AND closure IS NOT NULL))
);
CREATE INDEX ix_action_stage  ON action_item(stage);
CREATE INDEX ix_action_scope  ON action_item(bu_code, plant);
CREATE INDEX ix_action_target ON action_item(target_date);
CREATE INDEX ix_action_key    ON action_item(item_key);
-- only one open action per batch
CREATE UNIQUE INDEX uq_action_open_item ON action_item(item_key) WHERE stage <> 'Closed' AND item_key IS NOT NULL AND item_key <> '';

-- ---------- Year-wise history (monthly snapshots) ----------
CREATE TABLE inventory_snapshot (
    id          BIGSERIAL   PRIMARY KEY,
    period      VARCHAR(7)  NOT NULL UNIQUE,            -- YYYY-MM
    snap_date   DATE        NOT NULL,
    fy          VARCHAR(10) NOT NULL,
    source      VARCHAR(12) NOT NULL CHECK (source IN ('auto','manual','import','summary','sample')),
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_snapshot_fy ON inventory_snapshot(fy);

CREATE TABLE snapshot_row (
    id          BIGSERIAL     PRIMARY KEY,
    snapshot_id BIGINT        NOT NULL REFERENCES inventory_snapshot(id) ON DELETE CASCADE,
    bu_code     VARCHAR(40)   NOT NULL,
    plant       VARCHAR(80)   NOT NULL,
    category    VARCHAR(40)   NOT NULL,
    department  VARCHAR(20),
    batches     INT           NOT NULL DEFAULT 0,
    val_u       NUMERIC(18,2) NOT NULL DEFAULT 0,
    val_q       NUMERIC(18,2) NOT NULL DEFAULT 0,
    val_b       NUMERIC(18,2) NOT NULL DEFAULT 0,
    val_aged    NUMERIC(18,2) NOT NULL DEFAULT 0,
    val_exp     NUMERIC(18,2) NOT NULL DEFAULT 0,
    val_expd    NUMERIC(18,2) NOT NULL DEFAULT 0,
    val_idle    NUMERIC(18,2) NOT NULL DEFAULT 0
);
CREATE INDEX ix_snaprow_snapshot ON snapshot_row(snapshot_id);

-- ---------- Audit history ----------
CREATE TABLE audit_log (
    id        BIGSERIAL    PRIMARY KEY,
    at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    username  VARCHAR(60)  NOT NULL,
    name      VARCHAR(120),
    action    VARCHAR(80)  NOT NULL,
    detail    VARCHAR(2000),
    entity    VARCHAR(40),
    entity_id VARCHAR(60)
);
CREATE INDEX ix_audit_at   ON audit_log(at DESC);
CREATE INDEX ix_audit_user ON audit_log(username);

-- ---------- Sync / import log ----------
CREATE TABLE sync_log (
    id       BIGSERIAL    PRIMARY KEY,
    at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    source   VARCHAR(200) NOT NULL,
    records  INT          NOT NULL DEFAULT 0,
    status   VARCHAR(20)  NOT NULL,
    message  VARCHAR(1000)
);

-- =====================================================================
-- Reference data
-- =====================================================================
INSERT INTO app_setting(key,value) VALUES
 ('expiryDays','180'),('agingDays','180'),('longLyingDays','365'),('nonMovingDays','180'),
 ('slowMovingDays','90'),('consAgingDays','90'),('qualityHoldDays','30'),('highValue','500000'),
 ('fyStart','4'),('companyName','Unichem Laboratories Ltd.'),('companyShort','UNICHEM'),
 ('policy.minLen','8'),('policy.maxFail','5'),('policy.lockMin','15'),('policy.idleMin','30');

INSERT INTO responsible_function(name,sort_order) VALUES
 ('Stores',1),('Production',2),('QC',3),('QA',4),('Engineering',5),('EHS',6),('Purchase',7),('Planning',8),('Finance',9),('Other',10);

INSERT INTO action_type(name,sort_order) VALUES
 ('Consumption planning',1),('Priority consumption',2),('Production utilization',3),('Inter-departmental transfer',4),
 ('Expiry/retest review',5),('Inventory rationalization',6),('Disposal/write-off review',7),
 ('Engineering consumption planning',8),('Other approved corrective action',9);

INSERT INTO app_role(code,label,builtin,description) VALUES
 ('admin','Administrator',TRUE,'Full control, including users, roles, security policy and data clearing.'),
 ('invmgr','Inventory Manager (Corporate)',TRUE,'Runs inventory control across the organization: masters, imports, actions, BU-level data clearing.'),
 ('buhead','Business Unit Head',TRUE,'Reviews and drives actions for the assigned business unit.'),
 ('planthead','Plant / Unit Head',TRUE,'Reviews and drives actions for the assigned unit(s).'),
 ('store','Store User',TRUE,'Maintains stock data of the assigned unit(s) and works on store actions.'),
 ('fnowner','Function Owner (QC / Engg / EHS / Prod.)',TRUE,'Updates and closes actions owned by their function.'),
 ('viewer','Viewer / Auditor',TRUE,'Read-only access with reports.');

INSERT INTO role_permission(role_code,permission)
SELECT 'admin', p FROM unnest(ARRAY['view.inventory','view.years','view.actions','action.create','action.update','action.update.own','action.close','action.close.own','action.delete','report.pdf','export.data','import.data','data.edit','data.approve','masters.edit','sap.config','data.clear.bu','data.clear','users.manage','audit.view']) p;
INSERT INTO role_permission(role_code,permission)
SELECT 'invmgr', p FROM unnest(ARRAY['view.inventory','view.years','view.actions','action.create','action.update','action.update.own','action.close','action.close.own','action.delete','report.pdf','export.data','import.data','data.edit','data.approve','masters.edit','data.clear.bu','audit.view']) p;
INSERT INTO role_permission(role_code,permission)
SELECT r, p FROM unnest(ARRAY['buhead','planthead']) r, unnest(ARRAY['view.inventory','view.years','view.actions','action.create','action.update','action.close','report.pdf','export.data']) p;
INSERT INTO role_permission(role_code,permission)
SELECT 'store', p FROM unnest(ARRAY['view.inventory','view.years','view.actions','action.create','action.update.own','action.close.own','report.pdf','export.data','import.data','data.edit']) p;
INSERT INTO role_permission(role_code,permission)
SELECT 'fnowner', p FROM unnest(ARRAY['view.inventory','view.years','view.actions','action.update.own','action.close.own','report.pdf']) p;
INSERT INTO role_permission(role_code,permission)
SELECT 'viewer', p FROM unnest(ARRAY['view.inventory','view.years','view.actions','report.pdf']) p;
