-- S2: Revocable, rotating refresh tokens stored as SHA-256 hashes (raw tokens are never persisted).
CREATE TABLE refresh_token (
    id                   uuid        PRIMARY KEY,
    account_id           uuid        NOT NULL CONSTRAINT fk_refresh_token_account REFERENCES account (id) ON DELETE CASCADE,
    family_id            uuid        NOT NULL,
    token_hash           varchar(64) NOT NULL CONSTRAINT uq_refresh_token_hash UNIQUE,
    created_at           timestamptz NOT NULL,
    expires_at           timestamptz NOT NULL,
    revoked_at           timestamptz,
    replaced_by_token_id uuid
);

CREATE INDEX ix_refresh_token_account_id ON refresh_token (account_id);
CREATE INDEX ix_refresh_token_family_id ON refresh_token (family_id);
CREATE INDEX ix_refresh_token_expires_at ON refresh_token (expires_at);
