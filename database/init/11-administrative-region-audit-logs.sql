CREATE TABLE administrative_region_audit_logs
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    region_id  UUID        NOT NULL,
    actor_id   UUID        NOT NULL REFERENCES users (id),
    action     VARCHAR(50) NOT NULL CHECK (
        action IN ('CREATE', 'UPDATE', 'DELETE', 'BOUNDARY_INGEST')
    ),
    metadata   JSONB,
    created_at TIMESTAMPTZ      DEFAULT NOW()
);

CREATE INDEX idx_admin_region_audit_logs_region ON administrative_region_audit_logs (region_id, created_at);
CREATE INDEX idx_admin_region_audit_logs_actor ON administrative_region_audit_logs (actor_id);
