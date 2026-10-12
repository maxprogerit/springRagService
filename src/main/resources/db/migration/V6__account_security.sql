-- token_version: every issued JWT carries the version it was issued under; raising it revokes all older tokens
-- (sign out everywhere, password change or reset).
ALTER TABLE users ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
-- Existing accounts count as verified; new ones start unverified only when REQUIRE_EMAIL_VERIFICATION is on.
ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT TRUE;

-- One-time tokens e-mailed to users. Only the SHA-256 of the token is stored, never the token itself.
CREATE TABLE auth_tokens (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type       VARCHAR(20)  NOT NULL CHECK (type IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
    token_hash CHAR(64)     NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ  NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX auth_tokens_user_idx ON auth_tokens (user_id, type);
