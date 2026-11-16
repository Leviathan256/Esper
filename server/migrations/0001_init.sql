-- docs/SERVER.md §4. Starts from an empty database.
CREATE TABLE accounts (
  id          uuid PRIMARY KEY,                    -- also the WebAuthn user handle
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE passkeys (
  credential_id bytea PRIMARY KEY,
  account_id    uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  passkey       jsonb NOT NULL,                    -- webauthn_rs::prelude::Passkey
  created_at    timestamptz NOT NULL DEFAULT now(),
  last_used_at  timestamptz
);
CREATE INDEX passkeys_account ON passkeys(account_id);
CREATE TABLE sessions (
  token_hash   bytea PRIMARY KEY,                  -- SHA-256 of the bearer token; raw token never stored
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
  state      jsonb NOT NULL,                     -- CharacterState JSON, as the app's SaveCodec writes it
  updated_at timestamptz NOT NULL DEFAULT now()
);
