-- Step 2 schema (docs/SERVER.md §4). Positions and ceremonies are deliberately
-- NOT here: both live in memory only.
CREATE TABLE accounts (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_handle bytea NOT NULL UNIQUE,              -- WebAuthn user.id, 32 random bytes
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE passkeys (
  credential_id bytea PRIMARY KEY,
  account_id    uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  public_key    bytea NOT NULL,                   -- COSE
  sign_count    bigint NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  last_used_at  timestamptz
);
CREATE INDEX passkeys_account ON passkeys(account_id);
CREATE TABLE sessions (
  token_hash   bytea PRIMARY KEY,                 -- SHA-256 of the bearer token; raw token never stored
  account_id   uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  created_at   timestamptz NOT NULL DEFAULT now(),
  last_used_at timestamptz NOT NULL DEFAULT now(),
  expires_at   timestamptz NOT NULL
);
CREATE INDEX sessions_account ON sessions(account_id);
CREATE INDEX sessions_expires ON sessions(expires_at);
CREATE TABLE characters (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),   -- party/guild/claim tables reference this later
  account_id uuid NOT NULL UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
  state      jsonb NOT NULL,                     -- SaveCodec.encode(CharacterState)
  updated_at timestamptz NOT NULL DEFAULT now()
);
