# Chipster Web Server — Dev Setup

This file covers the **backend** (chipster-web-server, PostgreSQL). The frontend is in `chipster-web` — see `../chipster-web/CLAUDE.md` for Angular setup and the full picture of how the two repos work together.

## Two Setup Modes

There are two ways to run the dev environment:

- **Servers in container** (below) — everything runs inside the sandbox container
- **Servers on host** — backend runs on the host, container is used only for the Claude Code session; see the host-mode section below

---

## Mode 1: Servers in Container

### Starting the Container

Before anything else, start the sandbox with ports forwarded:
```
WORKSPACE=~/workspace PORTS=8000-8110,4200 ./sandbox.sh
```
- `WORKSPACE` — mounts your local git workspace into the container
- `PORTS` — exposes chipster services (8000–8110) and the Angular dev server (4200)

**Check before starting servers:**
```
cat /sys/fs/cgroup/pids.max   # must be ≥ 4096
free -h                        # must have ≥ 6 GiB RAM
```
If either is insufficient, restart the container with the correct settings.

### Dockerfile Dependencies

All dependencies are pre-installed in the Dockerfile. Do not install things ad-hoc at runtime — if something is missing, add it to the Dockerfile and rebuild. Currently installed:

- Java 25 (Amazon Corretto) — for ServerLauncher/Gradle
- Node.js 24 + npm — for Angular
- TypeScript + @angular/cli (global) — for Angular dev server
- PostgreSQL 14 — database

### Starting PostgreSQL

Must run as `claudeuser` using a custom data directory (the system cluster is owned by `postgres`).

If `/home/claudeuser/pgdata` doesn't exist (fresh environment), initialize first:
```
/usr/lib/postgresql/14/bin/initdb -D /home/claudeuser/pgdata
```
(`initdb` is not on PATH — use the full path.)

Then start:
```
/usr/lib/postgresql/14/bin/postgres -D /home/claudeuser/pgdata -k /home/claudeuser/pgdata -p 5432 2>/home/claudeuser/pgdata/postgres.log &
```

- Socket: `/home/claudeuser/pgdata` (avoids permission issues with `/var/run/postgresql/`)
- To stop: `kill $(cat /home/claudeuser/pgdata/postmaster.pid | head -1)`
- To connect: `psql -p 5432 -h 127.0.0.1`

### Starting ServerLauncher

Create required databases (only needed once):
```
createdb -h 127.0.0.1 -p 5432 auth_db
createdb -h 127.0.0.1 -p 5432 session_db_db
createdb -h 127.0.0.1 -p 5432 job_history_db
```

Start:
```
cd /workspace/chipster-web-server && ./gradlew run --no-daemon
```

Use `--no-daemon` to avoid a background Gradle daemon consuming extra memory.

**Ready signal:** Watch for `"up and running"` in the log. Takes ~2.5 minutes:
```
timeout 180 grep -m 1 "up and running" <(tail -f /tmp/serverlauncher.log 2>/dev/null) && echo "READY"
```

To kill stuck Java processes: `kill -9 $(ps aux | grep java | grep -v grep | awk '{print $2}')`
(`pkill -f java` is not always reliable.)

### Configuration — `conf/chipster.yaml`

Required settings when running in the container:
```yaml
db-user: claudeuser
variable-ext-ip: localhost
url-ext-web-server: http://localhost:4200
```

- `db-user: claudeuser` — required because the DB was initialized as `claudeuser` (not needed in host mode)
- `variable-ext-ip: localhost` — services advertise `localhost` URLs so the browser can reach them
- `url-ext-web-server: http://localhost:4200` — CORS filter allows requests from the Angular dev server

To run Python jobs (system only has `python3`, not `python`):
```yaml
toolbox-runtime-command-python: python3
```

**Job scheduling:** When running in the container, the scheduler cannot start new containers — use "process" scripts to run jobs directly as processes.

Create an empty `~/.bash_profile` if missing, to suppress a non-fatal job script warning:
```
touch /home/claudeuser/.bash_profile
```

---

## Mode 2: Servers on Host

Use this when running chipster-web-server in VS Code on the host (e.g. for debugging).

### Starting the Container

No port forwarding needed — services run on the host:
```
WORKSPACE=~/workspace ./sandbox.sh
```

### Starting Servers on Host

Ask the user to run these on the host:

1. **PostgreSQL:**
   ```
   /opt/homebrew/opt/postgresql@14/bin/postgres -D /opt/homebrew/var/postgresql@14
   ```

2. **chipster-web-server:** Open the `chipster-web-server` folder in VS Code, open `ServerLauncher.java`, click "Debug Java".

3. **Angular:** see `../chipster-web/CLAUDE.md`

### Reaching Host Services from the Container

`localhost` inside the container resolves to the container itself, not the host. Use `host.docker.internal` instead:
```
http://host.docker.internal:<port>
```

### Configuration — `conf/chipster.yaml` (host mode)

Do NOT set `db-user` — leave it commented out or absent (uses the default host user).

### Running Jobs as Podman Containers (host mode, verified working)

```yaml
scheduler-bash-script-dir-in-jar: "bash-job-scheduler/podman"
scheduler-bash-image-repository: image-registry.apps.2.rahti.csc.fi/chipster-images/
scheduler-bash-image-tag: v4.19.1

scheduler-bash-tools-bin-host-mount-path: ~/workspace
toolbox-runtime-tools-bin-name: empty-tools-bin

scheduler-bash-env-name-1: PODMAN_SOCKET
scheduler-bash-env-value-1: podman machine ssh curl --unix-socket /run/user/$UID/podman/podman.sock

scheduler-bash-env-name-2: url_int_override_toolbox
scheduler-bash-env-value-2: http://host.containers.internal:8008
scheduler-bash-env-name-3: url_int_override_session_db
scheduler-bash-env-value-3: http://host.containers.internal:8004
scheduler-bash-env-name-4: url_int_override_scheduler
scheduler-bash-env-value-4: http://host.containers.internal:8006
scheduler-bash-env-name-5: url_int_override_file_broker
scheduler-bash-env-value-5: http://host.containers.internal:8007
```

Create the empty tools-bin directory once:
```
mkdir -p ~/workspace/empty-tools-bin
```

Notes:
- `url_int_service_locator` is hardcoded in `run.bash` as `http://host.containers.internal:8003` — not configurable via env vars, but works correctly on podman.
- `$UID` in PODMAN_SOCKET is expanded at runtime by bash, not Java.
