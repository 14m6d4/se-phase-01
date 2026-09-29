# Research: running the k6 baseline on a Kaggle CPU notebook (issue #7)

Date: 2026-09-29. Context: [ADR 0003](../adr/0003-load-test-on-kaggle-without-docker.md), `docs/spec.md` "Load test (baseline)".
Primary sources only; anything not confirmed by one is marked **UNVERIFIED**.

## Conclusions for the notebook

- The image is **Ubuntu 24.04 (noble)** and runs as **root**, so no `sudo` is needed (`sudo` is installed anyway).
- **JDK 21 is already installed** (`openjdk-21-jdk-headless`, put there by the Colab base image). Run `java -version` first and only `apt-get install -y openjdk-21-jdk-headless` if it is missing.
- `apt-get install -y postgresql` gives **PostgreSQL 16**. `btree_gist` ships inside `postgresql-16` and is a **trusted** extension. The app user can therefore run `CREATE EXTENSION btree_gist` in V5 as long as it has `CREATE` on the database. Simplest setup: make the `cinema` role the database owner.
- There is no systemd, and the base image has `/usr/sbin/policy-rc.d`, so apt will not start Postgres. Start it yourself with `service postgresql start` or `pg_ctlcluster 16 main start`. The package creates the `main` cluster on port 5432 when it installs.
- Create the role and database with `su postgres -c "psql ..."`: `CREATE ROLE cinema LOGIN PASSWORD '...'; CREATE DATABASE cinema OWNER cinema;`. The app connects over TCP to `localhost:5432/cinema` as user `cinema` (defaults in `application.yml`).
- **Internet must be ON** in the notebook settings, because `mvnw`, Maven Central and the k6 tarball all need the network. Enabling internet requires phone verification. That rule comes from Kaggle forum/support pages, not from the docs, so it is UNVERIFIED.
- Build with `./mvnw -B -Dmaven.test.skip=true package`, not `-DskipTests`. `-DskipTests` still compiles the test sources, which means downloading Testcontainers and the other test dependencies. The wrapper type is `only-script`, which needs `curl`/`wget` and `unzip`.
- k6 has **v2.3.0 (2026-09-21)** as its latest release. Install it with `curl -sL https://github.com/grafana/k6/releases/download/v2.3.0/k6-v2.3.0-linux-amd64.tar.gz | tar xz` and pin the version, because k6 v2 removed some flags.
- Use `handleSummary(data)` to write the JSON, and set `summaryTrendStats: ['avg','min','med','max','p(90)','p(95)','p(99)']`. Without that setting there is no p(99). k6 discourages `--summary-export`, and its format differs from `handleSummary` data (details in section 3).
- Hardware: 4 CPU cores and 30 GB RAM. A session can run for 12 h, but the interactive editor stops after 20 min idle. `/kaggle/working` holds 20 GB and is saved. For an unattended run, use "Save & Run All". Record `nproc` and `free -h` anyway, because Kaggle says the CPU platform varies.
- Start the JAR with `subprocess.Popen(..., start_new_session=True)`, send its output to a log file, and poll `/actuator/health` from the same or a later cell. The docs do not say whether a process started this way survives across cells (UNVERIFIED, but it lives as long as the kernel container does).

## 1. Notebook image, OS, Java, PostgreSQL

- The Kaggle CPU image is built `FROM us-docker.pkg.dev/colab-images/public/cpu-runtime:release-colab-external-images_20260917-060051_RC00`, and its Dockerfile has no `USER` directive ([Dockerfile.tmpl](https://github.com/Kaggle/docker-python/blob/main/Dockerfile.tmpl)). Kaggle states that the Python notebook container is the one in that repo ([Kaggle Notebooks docs, "The Notebook Environment"](https://www.kaggle.com/docs/notebooks)).
- The base image's OCI config (fetched from the public registry `us-docker.pkg.dev/v2/colab-images/public/cpu-runtime`) has these properties:
  - It carries the label `org.opencontainers.image.version=24.04`.
  - Its first layer's `/etc/os-release` reads `Ubuntu 24.04.5 LTS (Noble Numbat)`.
  - `User` is unset, so the container runs as root.
  - Its build history runs `apt-get install --no-install-recommends openjdk-21-jdk-headless` and also installs `sudo`.
  - It adds these apt repos: graphics-drivers PPA, deadsnakes PPA, CRAN, and r2u. It does **not** add the PostgreSQL PGDG repo, so `postgresql` comes from Ubuntu.
  - The Kaggle Dockerfile adds nothing Java-related and removes nothing Java-related.
  - The raw config and os-release were read directly, so they are a primary artifact. Whether the JDK is still present inside a running Kaggle session is **UNVERIFIED**; check with `java -version`.
- On Ubuntu noble:
  - [openjdk-21-jdk-headless](https://packages.ubuntu.com/noble-updates/openjdk-21-jdk-headless) is available at version 21.0.12.1+1-1~24.04.4.
  - [postgresql](https://packages.ubuntu.com/noble-updates/postgresql) 16+257build1.1 depends on [postgresql-16](https://packages.ubuntu.com/noble-updates/postgresql-16), version 16.15-0ubuntu0.24.04.1.
- btree_gist ships in the main server package. The [postgresql-16 file list](https://packages.ubuntu.com/noble-updates/amd64/postgresql-16/filelist) contains `/usr/lib/postgresql/16/lib/btree_gist.so` and `/usr/share/postgresql/16/extension/btree_gist.control`. On noble, `postgresql-contrib` is only a metapackage from postgresql-common ([page](https://packages.ubuntu.com/noble/postgresql-contrib)).
- Trusted extension:
  - The btree_gist docs say: "This module is considered 'trusted', that is, it can be installed by non-superusers who have `CREATE` privilege on the current database." ([PG 16 btree_gist](https://www.postgresql.org/docs/16/btree-gist.html))
  - For a trusted extension, the calling user owns the extension object and the bootstrap superuser owns its contained objects ([CREATE EXTENSION](https://www.postgresql.org/docs/16/sql-createextension.html)).
- Starting Postgres without systemd:
  - `postgresql-common` ships `/etc/init.d/postgresql`, `/usr/bin/pg_ctlcluster` and `/usr/bin/pg_createcluster` ([file list](https://packages.ubuntu.com/noble-updates/all/postgresql-common/filelist)).
  - The base layer of the Colab image contains `/usr/sbin/policy-rc.d`. This is the stock Docker Ubuntu mechanism that blocks service start during apt install. That it actually blocks the start on Kaggle is **UNVERIFIED**; the notebook starts Postgres explicitly either way.
  - `pg_ctlcluster [options] version name start` must run "as the user who owns the database cluster or as root" and writes its log to `/var/log/postgresql/postgresql-<ver>-<name>.log` ([pg_ctlcluster(1)](https://manpages.ubuntu.com/manpages/noble/man1/pg_ctlcluster.1.html)).
  - `create_main_cluster` makes a `main` cluster "when a new postgresql-NN server package is installed", on the first free port starting at 5432 ([pg_createcluster(1)](https://manpages.ubuntu.com/manpages/noble/man1/pg_createcluster.1.html)).

## 2. Kaggle limits and execution model

All from [Kaggle Notebooks docs, "Technical Specifications"](https://www.kaggle.com/docs/notebooks). The page is rendered by JS; its text was read through the `cms.LegacyCmsService/GetPage` endpoint that the page itself calls.

- Hardware:
  - CPU notebook: "4 CPU cores", "30 Gigabytes of RAM".
  - P100 and T4x2 notebooks: 4 cores and 29 GB.
  - "CPU Platforms (ex. Intel Skylake, Broadwell, AMD) may be variable during regular notebook runs."
- Time and disk:
  - "12 hours execution time for CPU and GPU notebook sessions."
  - "20 Gigabytes of auto-saved disk space (/kaggle/working)", plus scratch disk outside it that is not saved. The size of the scratch disk is not stated.
  - "20 minutes of idle time for your interactive session." For longer work, use "Save & Run All", which "creates a new session with a completely clean state and runs your notebook from top to bottom … must execute within 12 hours" (it is the old "Commit").
- Internet:
  - The docs say to make sure "Internet" is enabled in the Settings pane before `!pip install`. Internet is one of the notebook's "Session options" (same page).
  - Phone verification before internet can be enabled is **UNVERIFIED** in the docs; only forum and support threads state it (e.g. [kaggle.com/general/331276](https://www.kaggle.com/general/331276)).
  - That a "Save & Run All" run keeps the notebook's internet setting follows from internet being a notebook setting, but no explicit sentence says so (**UNVERIFIED**).
- Shell commands run with a `!` prefix in a cell (same page).
- Background processes across cells: the Kaggle docs say nothing about them (**UNVERIFIED**). In practice, a child of the Jupyter kernel keeps running until the kernel or session ends. Use `start_new_session=True` and log to a file under `/kaggle/working` so the logs are saved with the version.

## 3. k6

- Install:
  - The docs say: "Our GitHub Releases page has a standalone binary for all platforms … place the `k6` binary in your `PATH`." ([Install k6](https://grafana.com/docs/k6/latest/set-up/install-k6/))
  - Latest release: **v2.3.0**, published 2026-09-21. Assets follow the pattern `k6-v<ver>-linux-amd64.tar.gz` at `https://github.com/grafana/k6/releases/download/v<ver>/` ([releases API](https://api.github.com/repos/grafana/k6/releases)). The v1.8.x line is still being patched (v1.8.1, 2026-08-12).
- k6 v2 breaking changes that matter here ([v2.0.0 notes](https://github.com/grafana/k6/releases/tag/v2.0.0)):
  - `--no-summary` was removed; use `--summary-mode=disabled` instead.
  - `--summary-mode=legacy` was removed.
  - `externally-controlled` was removed.
  - `--summary-export` was **not** removed.
  - v2.2.0 added `handleSummaryTimeout`, default 120 s ([v2.2.0](https://github.com/grafana/k6/releases/tag/v2.2.0)).
- `setup()` ([Test lifecycle](https://grafana.com/docs/k6/latest/using-k6/test-lifecycle/)):
  - It runs **once per test**, after init and before VU code.
  - "each stage and each VU has access to a fresh 'copy' of whatever data the `setup()` function returns."
  - Only JSON data can be passed, not functions.
  - `setupTimeout` defaults to `60s` ([options reference](https://grafana.com/docs/k6/latest/using-k6/k6-options/reference/)).
  - The spec's "2,000 customers log in during setup" means 2,000 BCrypt logins. Those need a raised `setupTimeout`, or a batched `http.batch` login.
- `ramping-arrival-rate` options ([executor docs](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/ramping-arrival-rate/)):
  - `stages` (required, `[{target, duration}]`)
  - `preAllocatedVUs` (required)
  - `startRate` (default 0)
  - `timeUnit` (default `"1s"`)
  - `maxVUs` (default = `preAllocatedVUs`)
  - When k6 runs out of VUs, it emits `dropped_iterations` (general k6 metric; not quoted from this page).
- Percentiles:
  - The default `summaryTrendStats` is `avg,min,med,max,p(90),p(95)` ([options reference](https://grafana.com/docs/k6/latest/using-k6/k6-options/reference/); also `DefaultSummaryTrendStats` in [lib/options.go](https://github.com/grafana/k6/blob/v2.3.0/lib/options.go)).
  - For **p(99), add it to `summaryTrendStats`**. This affects both outputs.
- `handleSummary(data)` ([Custom summary](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/)):
  - It returns a map `{ 'stdout': ..., 'path.json': JSON.stringify(data) }`.
  - Values are read as `data.metrics.<name>.values.<stat>`.
  - Read these fields:
    - `http_reqs.values.rate` (and `.count`)
    - `http_req_duration.values['med'|'p(95)'|'p(99)']` in ms
    - `http_req_failed.values.rate`
    - `dropped_iterations.values.count`. The metric is present only if something was dropped; this is inferred and **UNVERIFIED**, so guard for it being missing.
  - Default text output: `import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js'`.
- `--summary-export`:
  - The docs say: "not deprecated yet, we now discourage it … use the `handleSummary()` function" ([options reference](https://grafana.com/docs/k6/latest/using-k6/k6-options/reference/)).
  - Its format is produced by `oldJSONSummary` in [internal/js/summary-wrapper.js](https://github.com/grafana/k6/blob/v2.3.0/internal/js/summary-wrapper.js). It **flattens** `values` (`metrics.http_req_duration['p(95)']`) and **renames a Rate metric's `rate` to `value`**. So `http_req_failed` is `metrics.http_req_failed.value`, while `http_reqs` (a counter) keeps `.rate`.
  - With `--new-machine-readable-summary` (v1.5.0+), the export uses the new schema ([grafana/k6-summary](https://github.com/grafana/k6-summary)).
  - Recommendation: use `handleSummary` only.

## 4. Building on Kaggle

- The repo's `.mvn/wrapper/maven-wrapper.properties` sets `distributionType=only-script` and `distributionUrl=…/apache-maven-3.9.16-bin.zip`.
- `only-script` "download[s] the maven directly via wget or curl … then call[s] the original `mvn`". It uses no wrapper JAR. The download goes under `$MAVEN_USER_HOME`, which defaults to `$HOME/.m2` ([Maven Wrapper](https://maven.apache.org/tools/wrapper/)).
- `mvnw` also needs `unzip`. The script checks for it, and both images have it (the Kaggle Dockerfile installs `unzip`).
- The first build therefore needs network for Maven itself and for all plugins and dependencies from Maven Central. This is expected; download time is **UNVERIFIED** (not measured).
- `-DskipTests` only skips running tests. `-Dmaven.test.skip=true` also skips compiling them, which avoids resolving test-scope dependencies ([Surefire: skipping tests](https://maven.apache.org/surefire/maven-surefire-plugin/examples/skipping-tests.html)).
- Getting the source: `git clone` the public repo (internet on). A private repo would need a token, stored for example as a Kaggle secret; that route was not researched.
- Alternatives to building in the notebook:
  - **GitHub Release asset.** CI publishes `cinema.jar` and the notebook `curl`s it. The notebook still needs internet for k6.
  - **Kaggle Dataset.** Kaggle can create a dataset from "Remote Files — list of public URL(s)" or from a "Github Repository". These are fetched server-side, and a publisher can set "automatic interval updates" ([Kaggle Datasets docs](https://www.kaggle.com/docs/datasets)). Datasets are private by default. The mount path (`/kaggle/input/<slug>`) is standard Kaggle behaviour, but these docs do not state it (**UNVERIFIED**).
  - Either option avoids depending on Maven Central on Kaggle and makes runs reproducible against a fixed JAR. Building in place keeps the notebook self-contained, which is what issue #7 asks for.
