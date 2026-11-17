# Beta setup (Levi's accounts)

Everything here needs your own accounts or your own server. The code never
blocks on it: until you finish, CI stays green, the deploy job only tests and
builds the image, and the app shows "Server not configured". Contract:
`docs/SERVER.md`. Why this shape: `docs/adr/0001-self-hosted-k3s-over-oracle-vm.md`.

`HOST` below is your server's Tailscale name (for example `srv-levi`).

## 1. Cloudflare DNS for esper.media

The zone already uses Cloudflare's nameservers.

1. DNS: add `A` (and `AAAA` if the host has IPv6) for `esper.media` to the
   server's public IP. **Proxied** (orange cloud).
2. SSL/TLS, Overview: mode **Full (strict)**.
3. My Profile, API Tokens, Create Token, template "Edit zone DNS". Zone
   Resources: Include, Specific zone, `esper.media`. Keep the token for step 3.

## 2. Tailscale access for CI

1. Tailscale admin console, Access controls: add a `tag:ci` tag owned by you,
   and allow `tag:ci` to reach `HOST` on port 6443.
2. Settings, OAuth clients, Generate: scope `auth_keys` (write), tag `tag:ci`.
   Keep the client ID and secret for step 6.

## 3. Bootstrap the server

On the server, from a checkout of this repo:

```bash
cd deploy && sudo ./host-setup.sh
```

It installs k3s and cert-manager and creates the `esper` namespace and its
Secrets. It prompts for the Cloudflare token (step 1.3) and the passkey origin
(step 4; press Enter to set either later). Safe to re-run. It does not apply
the firewall yet (#12).

## 4. Signing fingerprint

Actions tab, workflow "Signing fingerprint", Run workflow. Open the run summary.
It prints two values:

- `assetlinks`: colon-separated SHA-256, used in step 5.
- `passkey origin`: `android:apk-key-hash:...`, used in step 5.

(Every nightly build summary shows the same two rows, taken from the signed APK.)

The signing secrets (`ESPER_KEYSTORE_BASE64`, `ESPER_KEYSTORE_PASSWORD`,
`ESPER_KEY_ALIAS`, `ESPER_KEY_PASSWORD`) must already be set (see the README).
Passkeys only work for builds signed with that real key.

## 5. Tell the server which app may sign in

1. Put the `assetlinks` value into `deploy/k8s/base/assetlinks.yaml` (replace the
   placeholder fingerprint) and commit it. It is public by design.
2. On the server, set the passkey origin if you skipped it in step 3:

```bash
sudo ./host-setup.sh --rp-origins 'android:apk-key-hash:...'
```

## 6. GitHub secrets and variable

Repo Esper, Settings, Secrets and variables, Actions.

| Secret | Value |
| --- | --- |
| `TS_OAUTH_CLIENT_ID` | from step 2 |
| `TS_OAUTH_SECRET` | from step 2 |
| `ESPER_KUBECONFIG` | `sudo k3s kubectl config view --raw` on the server, with `server:` changed to `https://HOST:6443` |

Variable (Variables tab): `ESPER_SERVER_URL` = `https://esper.media`.

## 7. Deploy

Actions tab, workflow "Deploy server", Run workflow (it also runs on pushes to
`main` touching `server/`, `content/` or `deploy/`).

GHCR makes a new package private, and the cluster pulls without credentials.
After the first run has pushed the image: your GitHub profile, Packages,
`esper-server`, Package settings, Change visibility, **Public**. Then re-run
the workflow (a private image shows as `ImagePullBackOff` in the pod list).

Then:

```bash
curl https://esper.media/healthz                       # prints: ok
curl https://esper.media/.well-known/assetlinks.json   # prints the step 5 file
```

If it fails: `sudo k3s kubectl -n esper get pods,certificate,ingress` and
`sudo k3s kubectl -n esper logs deploy/esper-server`. A Cloudflare 526 means
the origin certificate is not issued yet: check the `Certificate` and the
cert-manager logs.

## 8. Backups

A nightly job writes `pg_dump` files to `/var/backups/esper` on the server and
keeps 14 days. They are on the same disk as the database: copy them off the
server if losing the host would matter.

## 9. Build the app and verify

1. Push to `main` (or run "Release (nightly for Obtainium)"). The nightly build
   bakes in `ESPER_SERVER_URL`.
2. Install or update via Obtainium on an Android 14+ phone that has a passkey
   provider (Google Password Manager, Bitwarden and so on).
3. Create account (passkey prompt), create a character, win a fight, force-stop
   and reopen the app. The character must still be there.
4. If the passkey prompt says the app is not associated or fails with a
   "rejected" error: recheck the step 7 assetlinks URL, that the fingerprint
   matches the APK's `signer`, and the passkey origin.

Android 13 and older cannot use passkeys without Play Services, so they cannot
sign in. Decision for you: accept Android 14+ only (recommended), or later add
the Play Services library or a recovery-code sign-in.
