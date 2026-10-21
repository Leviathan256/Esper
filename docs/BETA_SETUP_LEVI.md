# Beta setup (Levi's accounts)

Everything here needs your own accounts. The code never blocks on it: until
you finish, CI stays green, the deploy job only builds and tests, and the app
shows "Server not configured". Everything is free. Contract: `docs/SERVER.md`.

Names used below: `HOST` is your server name (for example
`esper-levi.duckdns.org`), `IP` is the VM's public IP.

## 1. Oracle VM

1. Oracle Cloud console, Compute, Instances, Create instance.
2. Image: Ubuntu 24.04. Shape: Ampere `VM.Standard.A1.Flex`, 1 OCPU, 6 GB (Always Free).
3. Upload or paste your SSH public key. Create it and note the public IP (`IP`).
4. Networking, your VCN, Security Lists, Default Security List, Add Ingress Rules:
   source `0.0.0.0/0`, protocol TCP, destination port `80`. Add a second rule for `443`.
   (The VM's own firewall is opened by `setup.sh`.)

## 2. Free hostname

Pick one.

- DuckDNS (recommended): sign in at duckdns.org, add a domain such as
  `esper-levi`, set its IP to `IP`. `HOST=esper-levi.duckdns.org`.
- sslip.io: no account. `HOST` is the IP with dashes, for example
  `129-146-1-2.sslip.io` for `129.146.1.2`.

## 3. Deploy key

On your computer:

```bash
ssh-keygen -t ed25519 -f esper-deploy -N "" -C esper-deploy
```

Keep `esper-deploy` (private) for step 5. `esper-deploy.pub` goes to the VM.

## 4. Bootstrap the VM

```bash
scp -r deploy esper-deploy.pub ubuntu@IP:~
ssh ubuntu@IP
cd ~/deploy && sudo ./setup.sh HOST ~/esper-deploy.pub
```

It installs Java 17, Postgres and Caddy, creates the `esper` user and database,
writes `/etc/esper/server.env` and opens ports 80 and 443. Safe to re-run.

## 5. GitHub secrets and variable

Repo Esper, Settings, Secrets and variables, Actions.

Secrets:

| Name | Value |
| --- | --- |
| `ESPER_SERVER_HOST` | `HOST` |
| `ESPER_SERVER_SSH_KEY` | contents of `esper-deploy` |
| `ESPER_SERVER_KNOWN_HOSTS` | output of `ssh-keyscan -t ed25519 HOST` |

The signing secrets (`ESPER_KEYSTORE_BASE64`, `ESPER_KEYSTORE_PASSWORD`,
`ESPER_KEY_ALIAS`, `ESPER_KEY_PASSWORD`) must already be set (see the README).
Passkeys only work for builds signed with that real key.

Variable (the Variables tab): `ESPER_SERVER_URL` = `https://HOST`.

## 6. Signing fingerprint

Actions tab, workflow "Signing fingerprint", Run workflow. Open the run summary.
It prints two values:

- `assetlinks`: colon-separated SHA-256, used in step 7.
- `passkey origin`: `android:apk-key-hash:...`, used in step 8.

(Every nightly build summary shows the same two rows, taken from the signed APK.)

## 7. Digital Asset Links on GitHub Pages

Passkeys for `leviathan256.github.io` need this file at the root of your user
site. A project page under `/Esper` cannot serve `/.well-known/`.

1. On GitHub create a public repo named exactly `leviathan256.github.io`
   (owner Leviathan256). Settings, Pages: deploy from the default branch, root.
2. Add these files, then commit and push:

```bash
git clone https://github.com/Leviathan256/leviathan256.github.io && cd leviathan256.github.io
mkdir -p .well-known && touch .nojekyll
cp /path/to/Esper/pages/assetlinks.json .well-known/assetlinks.json
# edit .well-known/assetlinks.json: replace the placeholder with the `assetlinks` value
git add -A && git commit -m "Add assetlinks" && git push
```

`.nojekyll` makes Pages serve the dot-folder. Check (may take a minute):

```bash
curl -s https://leviathan256.github.io/.well-known/assetlinks.json
```

## 8. Tell the server which app may sign in

```bash
ssh ubuntu@IP
sudo nano /etc/esper/server.env      # set ESPER_RP_ORIGINS=<the `passkey origin` value>
```

The first deploy in step 9 restarts the service; restart now if it is running:
`sudo systemctl restart esper-server`.

## 9. Deploy

Actions tab, workflow "Deploy server", Run workflow (it also runs on pushes to
`main` touching `server/`, `engine/`, `content/` or `deploy/`). Then:

```bash
curl https://HOST/healthz      # prints: ok
```

If TLS fails, check ports 80 and 443 (steps 1 and 4) and
`sudo journalctl -u caddy -n 50` on the VM. Server logs:
`sudo journalctl -u esper-server -n 50`.

## 10. Build the app and verify

1. Push to `main` (or run "Release (nightly for Obtainium)"). The nightly build
   bakes in `ESPER_SERVER_URL`.
2. Install or update via Obtainium on an Android 14+ phone that has a passkey
   provider (Google Password Manager, Bitwarden and so on).
3. Create account (passkey prompt), create a character, win a fight, force-stop
   and reopen the app. The character must still be there.
4. If the passkey prompt says the app is not associated or fails with a
   "rejected" error: recheck the step 7 URL, that the fingerprint matches the
   APK's `signer`, and `ESPER_RP_ORIGINS`.

Android 13 and older cannot use passkeys without Play Services, so they cannot
sign in. Decision for you: accept Android 14+ only (recommended), or later add
the Play Services library or a recovery-code sign-in.
