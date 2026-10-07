# Runbook: the backend on proxy, app and db

How the backend runs on the three VMs from [`terraform.md`](terraform.md), and how to deploy it, roll it back and rebuild a VM (#34). Everything is by hand until a CI/CD pipeline replaces it ([ADR-0006](../adr/0006-phase-1-on-vms-and-compose.md)).

| VM | Runs | Files in the repo | Listens on |
|---|---|---|---|
| `proxy` | nginx and certbot from Debian, natively | [`deploy/proxy/`](../../deploy/proxy/) | `10.10.30.17:443` (floating IP) |
| `app` | the backend image, with Compose | [`deploy/app/`](../../deploy/app/) | `192.168.100.20:8080` |
| `db` | `postgres:17-alpine`, with Compose | [`deploy/db/`](../../deploy/db/) | `192.168.100.30:5432` |

`https://cinema-api.strycks.dpdns.org` → nginx on `proxy` → `app:8080` → `db:5432`. The service is reachable from the tailnet only.

## How it fits together

- **Files.** Each role's directory is copied from a checkout on the workstation to `/home/debian/cinema/` on its VM, `*.example` files excepted:

  ```sh
  rsync -a --exclude '*.example' deploy/app/ cinema-app:cinema/
  ```

- **Secrets** sit next to their `.example`, on the workstation only: `deploy/db/.env`, `deploy/app/.env`, `deploy/proxy/cloudflare.ini`. They're gitignored, mode 600 (`rsync -a` keeps the mode), and a copy lives in the password manager. The workstation copy is the one you edit; `rsync` sends it up. Don't edit `.env` on a VM: the next `rsync` overwrites it.
- **What runs** is `IMAGE_TAG` in `deploy/app/.env`: the full 40-character sha of a commit on `main`, whose image CI pushed to `ghcr.io/14m6d4/se-phase-01`. Never `latest`.
- **Names.** Containers don't read the VM's `/etc/hosts`, so the app gets `db` through `extra_hosts` (`DB_HOST_IP`). nginx runs natively and reads `/etc/hosts`.
- **Data.** PostgreSQL's data directory is `/srv/pgdata/data` on db, on the `pgdata` volume. If the volume isn't mounted, the directory is missing and the db container refuses to start. Dumps go to `/srv/pgdata/dumps/`, on the same volume: they survive a db rebuild, not the loss of the volume (off-host backups: #40).
- **Certificate.** One wildcard, `*.strycks.dpdns.org`, named `strycks.dpdns.org`, issued by DNS-01 through Cloudflare. Debian's `certbot.timer` renews it, and certbot reloads nginx after each renewal.

The commands below run from the repo root on the workstation, with the `ssh_config` output from `terraform.md` in `~/.ssh/config`.

## First setup

### Secrets and DNS

1. **Cloudflare token.** In the Cloudflare dashboard: **My Profile → API Tokens → Create Token → Edit zone DNS**. Permissions `Zone → DNS → Edit`, zone resources `Include → Specific zone → strycks.dpdns.org`. Copy the token into `deploy/proxy/cloudflare.ini` (from `cloudflare.ini.example`).
2. **GHCR token.** GHCR accepts only classic tokens: on GitHub, **Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate new token (classic)**, scope `read:packages` only, with an expiry date. The account needs read access to the `se-phase-01` package. Note the expiry: see [Rotating tokens](#rotating-tokens).
3. **`.env` files.** `cp .env.example .env` in `deploy/db/` and `deploy/app/`, then fill them. `DB_PASSWORD` is the same in both. Generate secrets with `openssl rand -base64 32`.
4. `chmod 600 deploy/{db,app}/.env deploy/proxy/cloudflare.ini`, and put a copy of the three files in the password manager.
5. **DNS.** In Cloudflare: an `A` record `cinema-api` → `10.10.30.17`, **DNS only** (grey cloud). Cloudflare's proxy can't reach a private address.

### db

```sh
ssh cinema-db 'sudo install -d /srv/pgdata/data && sudo install -d -o debian -g debian /srv/pgdata/dumps'
rsync -a --exclude '*.example' deploy/db/ cinema-db:cinema/
ssh cinema-db 'cd cinema && docker compose up -d --wait'
```

The first start creates the database with `DB_PASSWORD`. Later changes to `DB_PASSWORD` in `.env` don't change it in the database.

### app

Log in to GHCR once. The login is kept in `/home/debian/.docker/config.json` (mode 600). `read -s` keeps the token out of the shell history:

```sh
read -rsp 'PAT: ' pat && ssh cinema-app 'docker login ghcr.io -u <github user> --password-stdin' <<< "$pat"; unset pat
```

Set `IMAGE_TAG` in `deploy/app/.env` to the sha to run (see [Deploy](#deploy-a-new-sha) for which one), then:

```sh
rsync -a --exclude '*.example' deploy/app/ cinema-app:cinema/
ssh cinema-app 'cd cinema && docker compose pull && docker compose up -d --wait'
```

`--wait` returns once the healthcheck (`/actuator/health` is `UP`) passes, and fails if it doesn't within about two minutes. Flyway runs the migrations on the first start.

### proxy

A proxy built from the current cloud-init already has the packages. The proxy built before #34 needs them by hand, once:

```sh
ssh cinema-proxy 'sudo apt-get update && sudo apt-get install -y nginx certbot python3-certbot-dns-cloudflare'
```

Copy the files, and put the Cloudflare credentials where certbot reads them:

```sh
rsync -a --exclude '*.example' deploy/proxy/ cinema-proxy:cinema/
ssh cinema-proxy 'sudo install -D -m 600 -o root -g root cinema/cloudflare.ini /etc/letsencrypt/cloudflare.ini && rm cinema/cloudflare.ini'
```

Issue the certificate. The `--deploy-hook` is stored with it, so every renewal reloads nginx:

```sh
ssh cinema-proxy "sudo certbot certonly --non-interactive --agree-tos -m <email> \
  --dns-cloudflare --dns-cloudflare-credentials /etc/letsencrypt/cloudflare.ini \
  -d '*.strycks.dpdns.org' --cert-name strycks.dpdns.org \
  --deploy-hook 'systemctl reload nginx'"
```

Enable the site, and drop Debian's default site on port 80:

```sh
ssh cinema-proxy 'sudo install -m 644 cinema/cinema-api.conf /etc/nginx/sites-available/cinema-api \
  && sudo ln -sf ../sites-available/cinema-api /etc/nginx/sites-enabled/cinema-api \
  && sudo rm -f /etc/nginx/sites-enabled/default \
  && sudo nginx -t && sudo systemctl reload nginx'
ssh cinema-proxy 'systemctl is-enabled certbot.timer && sudo certbot renew --dry-run'
```

### Check

```sh
curl https://cinema-api.strycks.dpdns.org/actuator/health
```

It answers `{"status":"UP"}` with no certificate warning. Then run [`verify-deploy.sh`](verify-deploy.sh).

## Deploy a new sha

1. **Pick the sha.** A commit on `main` whose CI run is green, both `verify` and `image`:

   ```sh
   gh run list --workflow CI --branch main --limit 5
   ```

   Read the full sha from `git rev-parse <short sha>`. If it adds a migration (`src/main/resources/db/migration/`), check it against [the rule for migrations](#migrations-and-rolling-back).
2. **Dump the database**, named after the sha running now, which is the one in app's `.env` on the VM (your local copy changes in step 3). Keep the 5 newest:

   ```sh
   old=$(ssh cinema-app "grep '^IMAGE_TAG=' cinema/.env | cut -d= -f2")
   ssh cinema-db "cd cinema && docker compose exec -T db pg_dump -U cinema -Fc cinema \
     > /srv/pgdata/dumps/\$(date -u +%Y%m%dT%H%M%SZ)-$old.dump \
     && ls -1t /srv/pgdata/dumps/*.dump | tail -n +6 | xargs -r rm -- && ls -lh /srv/pgdata/dumps"
   ```

   The newest file must not be empty. If the dump fails, stop: don't deploy.
3. **Point `IMAGE_TAG` at the new sha** in `deploy/app/.env`, and write the old one in the `# Previous:` line below it.
4. **Deploy:**

   ```sh
   rsync -a --exclude '*.example' deploy/app/ cinema-app:cinema/
   ssh cinema-app 'cd cinema && docker compose pull && docker compose up -d --wait'
   curl https://cinema-api.strycks.dpdns.org/actuator/health
   ```

If `up --wait` fails, see why with `ssh cinema-app 'cd cinema && docker compose logs --tail 100 app'`, then [roll back](#roll-back).

## Roll back

Rolling back is deploying the previous sha, through the same steps of [Deploy](#deploy-a-new-sha):

1. Step 2: dump the database. The dump is named after the bad sha and keeps its data.
2. Step 3: put the sha from the `# Previous:` line back into `IMAGE_TAG`, and the bad one into `# Previous:`.
3. Step 4: deploy.

This works as long as the newer sha's migrations only added things. If one of them dropped or renamed something the older code uses, the older app fails its startup check (Hibernate `ddl-auto: validate`) or its queries. Then [restore the dump](#restore-a-dump) taken before that deploy instead.

### Migrations and rolling back

Flyway runs each migration once, in order, and records it in `flyway_schema_history`. It never undoes one. An older image starts fine on a newer schema: Flyway skips migrations it doesn't know. Whether the older code then works depends on what the newer migrations did:

- **Added** a table, a nullable column, an index: the older code ignores it, and rolling back works.
- **Dropped or renamed** something the older code uses: rolling back breaks.

So a migration only adds things. Dropping or renaming comes in a later release, once no running version uses the old shape ("expand, then contract"). Then the previous sha always runs on the current schema.

## Restore a dump

This puts the database back to the moment of a dump. Anything written since is lost.

1. Dump the current state first, as in [Deploy](#deploy-a-new-sha) step 2, in case the restore was a mistake.
2. Stop the app: `ssh cinema-app 'cd cinema && docker compose stop'`
3. Restore. `ls -lt /srv/pgdata/dumps` on db lists them; the sha in the file name is the version the dump goes with:

   ```sh
   ssh cinema-db 'cd cinema && docker compose exec -T db pg_restore -U cinema -d cinema --clean --if-exists \
     < /srv/pgdata/dumps/<file>.dump'
   ```

4. Set `IMAGE_TAG` to the sha in the file name and run step 4 of [Deploy](#deploy-a-new-sha).

## Reboot a VM

Nothing reboots on its own: `unattended-upgrades` installs security updates without rebooting. When `/run/reboot-required` exists on a VM, reboot it by hand. A reboot of app or db is downtime.

```sh
for vm in proxy app db; do ssh cinema-$vm 'test -f /run/reboot-required && echo "$(hostname): reboot required"'; done
ssh cinema-db 'sudo reboot'
```

The containers come back on their own (`restart: unless-stopped`), and so do nginx and the certbot timer. After the reboot, check:

```sh
ssh cinema-app 'cd cinema && docker compose ps'
ssh cinema-db 'cd cinema && docker compose ps'
curl https://cinema-api.strycks.dpdns.org/actuator/health
```

Reboot db before app if both need it. If a container stays down after a reboot with `cannot assign requested address`, Docker started before the VM had its address: start it with `docker compose up -d --wait`, and note it on #34.

## Rebuild a VM

A rebuild replaces the VM's root disk, as in [`terraform.md`](terraform.md#changing-things-later):

```sh
terraform -chdir=infra/terraform apply -replace=module.<vm>.openstack_blockstorage_volume_v3.root
ssh-keygen -R <address>   # 10.10.30.17 for proxy, the fixed IP for app and db
```

It keeps the address and, on db, the `pgdata` volume. It loses everything else on the VM, including team members' SSH keys (add them again, `terraform.md`).

- **app:** after the rebuild, follow [First setup → app](#app): the GHCR login, then `rsync` and `up`. It's down until then.
- **db:** before the rebuild, take a dump ([Deploy](#deploy-a-new-sha) step 2), then stop the app and the database so PostgreSQL shuts down cleanly:

  ```sh
  ssh cinema-app 'cd cinema && docker compose stop'
  ssh cinema-db 'cd cinema && docker compose down'
  ```

  After it, `/srv/pgdata` is mounted again with the data in place. Run `rsync` and `up` from [First setup → db](#db) (skip the `install -d`), then `ssh cinema-app 'cd cinema && docker compose start'`.
- **proxy:** save the certificate first, so the new proxy doesn't need a new one (Let's Encrypt issues at most 5 identical certificates a week):

  ```sh
  ssh cinema-proxy 'sudo tar -C /etc -czf - letsencrypt' > letsencrypt.tgz && chmod 600 letsencrypt.tgz
  ```

  After the rebuild, cloud-init has installed the packages. Restore the certificate, then enable the site as in [First setup → proxy](#proxy), skipping `apt-get` and `certbot certonly`. Delete `letsencrypt.tgz` afterwards: it holds the private key.

  ```sh
  ssh cinema-proxy 'sudo tar -C /etc -xzf -' < letsencrypt.tgz
  rsync -a --exclude '*.example' --exclude cloudflare.ini deploy/proxy/ cinema-proxy:cinema/
  ```

  Without the archive, follow [First setup → proxy](#proxy) whole.

Run [`verify-deploy.sh`](verify-deploy.sh) after any rebuild.

## Rotating tokens

- **GHCR PAT,** before it expires: generate a new classic token (`read:packages`), run the `docker login` from [First setup → app](#app) again, update the password manager, and delete the old token on GitHub. An expired token doesn't stop the running app, only the next `pull`.
- **Cloudflare token:** create a new one, put it in `deploy/proxy/cloudflare.ini`, install it as in [First setup → proxy](#proxy), run `sudo certbot renew --dry-run` on proxy, then delete the old token in Cloudflare. A broken token shows up only when a renewal fails, about 30 days before expiry.

## Where to look

| What | Command |
|---|---|
| App logs | `ssh cinema-app 'cd cinema && docker compose logs --tail 200 app'` |
| Database logs | `ssh cinema-db 'cd cinema && docker compose logs --tail 200 db'` |
| What runs | `ssh cinema-app 'cd cinema && docker compose ps'` |
| nginx logs | `ssh cinema-proxy 'sudo tail -n 100 /var/log/nginx/access.log /var/log/nginx/error.log'` |
| Certificate | `ssh cinema-proxy 'sudo certbot certificates'` |
| Renewals | `ssh cinema-proxy 'systemctl list-timers certbot.timer; sudo journalctl -u certbot --since -7d'` |
