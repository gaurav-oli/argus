# Argus — Deploy & Tailscale Runbook

How to run Argus on the **Mac Mini M3** and reach it from your iPhone/iPad over
**Tailscale** — never the public internet.

**Topology:** the Mini runs the full stack — Postgres + Redis + backend + frontend
in Docker Compose, and **Ollama natively on the host** (Docker on macOS has no
Metal/GPU passthrough). Tailscale provides a private HTTPS origin for your devices.

```
iPhone / iPad ──Tailscale──┐
MacBook (dev) ──Tailscale──┤   https://<mini>.<tailnet>.ts.net
                           ▼            (tailscale serve, HTTPS)
                    ┌──────────────── Mac Mini ────────────────┐
                    │  /     → frontend  (127.0.0.1:3000)       │
                    │  /api  → backend   (127.0.0.1:8080)       │
                    │  /ws   → backend   (127.0.0.1:8080/ws)    │
                    │  backend → Postgres + Redis (compose net) │
                    │  backend → Ollama (host.docker.internal)  │
                    └───────────────────────────────────────────┘
```

All container ports bind to **127.0.0.1** only; the sole entry point is Tailscale.

---

## 1. Prerequisites (one-time)

**On the Mac Mini:**
- macOS with **FileVault ON** (secrets at rest — NFR-3).
- **Docker Desktop** (or Colima) running.
- **Ollama** installed natively: `brew install ollama` then `ollama serve` (run as a login item / background service).
- **Tailscale** installed: https://tailscale.com/download/mac
- Git access to the repo.

**On the iPhone/iPad:**
- Tailscale app installed and signed into the **same tailnet**.

## 2. Tailscale network (one-time)

```sh
# On the Mini:
tailscale up                      # sign in; approve the device in the admin console
tailscale status                  # confirm it's connected
tailscale ip -4                   # note the 100.x address
```
- In the Tailscale admin console, enable **MagicDNS**. Your Mini gets a stable name like `mini.<tailnet>.ts.net`.
- On the iPhone, open the Tailscale app and connect. Confirm you can `ping` the Mini's MagicDNS name.

> Use **`tailscale serve`** (tailnet-only). **Never `tailscale funnel`** — Funnel exposes to the public internet and violates NFR-3.

## 3. Ollama model (one-time / when revised)

```sh
ollama pull gemma4:26b            # validated by Story 1.3 (2026-06-21)
ollama list                       # confirm it's present
```
**Story 1.3 result (2026-06-21):** `gemma4:26b` ≈ 17GB resident, ~22 tok/s, warm first-token <1s, ~28s cold-load. The 26B + full stack overflows the 28GB Mini into swap, so keep-alive is short (`5m`, unload-when-idle): the model frees ~17GB when idle and reloads on demand. **Do not pin it resident (`-1`) on a 28GB box.** Override via `ARGUS_BIG_MODEL` / `ARGUS_MODEL_KEEP_ALIVE` in `.env`.

## 4. Configure `.env` (one-time, then as keys change)

```sh
git clone https://github.com/gaurav-oli/argus.git   # or your fork
cd argus
cp .env.example .env
```
Edit `.env` and set at minimum:
- `POSTGRES_PASSWORD` — a strong password (the same value is used by Postgres and the backend container).
- `ANTHROPIC_API_KEY`, `FINNHUB_API_KEY` — as available.
- Single-origin frontend build values (replace `<mini>.<tailnet>`):
  ```
  NEXT_PUBLIC_API_BASE_URL=
  NEXT_PUBLIC_WS_URL=wss://<mini>.<tailnet>.ts.net/ws
  ```
  Empty API base → the browser calls `/api` on the **same origin** (no CORS); the WS URL points at the tailnet host.

## 5. Deploy

```sh
git pull
docker compose --profile deploy up -d --build
docker compose ps                 # postgres, redis, backend, frontend → healthy
docker compose logs -f backend    # watch startup (Flyway, profile=prod, Ollama)
```
- `--profile deploy` adds the `backend` + `frontend` containers. Without it, `docker compose up` stays **Postgres + Redis only** (the dev workflow).
- First build pulls base images + builds both apps; subsequent builds are cached.

## 6. Expose over Tailscale (HTTPS, single origin)

```sh
# Provision a tailnet TLS cert for the Mini (one-time; auto-renews):
tailscale cert <mini>.<tailnet>.ts.net

# Route one HTTPS origin to the two local services:
tailscale serve --bg --https=443 --set-path=/    http://127.0.0.1:3000
tailscale serve --bg --https=443 --set-path=/api http://127.0.0.1:8080/api
tailscale serve --bg --https=443 --set-path=/ws  http://127.0.0.1:8080/ws

tailscale serve status            # confirm the path mappings
```
> ⚠️ **`tailscale serve` CLI syntax changed across versions** (positional args vs `--set-path`, prefix-stripping behavior). Treat the commands above as the *intent*, not gospel — run `tailscale serve --help` for your installed version. The goal: `/` → frontend `:3000`, `/api` + `/ws` → backend `:8080`, HTTPS, tailnet-only. If `/api` requests 404, your version strips the mount prefix — point the target at the bare origin (`http://127.0.0.1:8080`) instead of `…:8080/api`.
>
> **Verify the WebSocket upgrade too** — path-scoped serve must forward the `Upgrade` header. After setup, confirm live data flows (below); if the dashboard's live pushes never arrive, the `/ws` upgrade is being dropped — serve `/ws` to the bare backend origin or expose `:8080` on its own serve mount.

HTTPS here also satisfies the prerequisite for the PWA service worker + Web Push (Epic 8).

## 7. Verify

```sh
# From the Mini or the dev laptop (both on the tailnet):
curl -s https://<mini>.<tailnet>.ts.net/api/system-info   # → JSON {name,version,profile:"prod",time}
```
- On the **iPhone**, open `https://<mini>.<tailnet>.ts.net` → the Argus dashboard shell loads, REST + live WebSocket work.
- Confirm **no public exposure**:
  ```sh
  tailscale funnel status          # must be OFF / no funnel
  # published container ports are loopback-only:
  docker compose ps --format '{{.Names}} {{.Ports}}'   # shows 127.0.0.1:* only
  ```

## 8. Update / redeploy

```sh
git pull
docker compose --profile deploy up -d --build
```

## 9. Stop / rollback

```sh
docker compose --profile deploy down          # stop app containers (keeps DB volumes)
# rollback to a known-good commit:
git checkout <good-commit>
docker compose --profile deploy up -d --build
```

## 10. Troubleshooting

| Symptom | Fix |
| --- | --- |
| Backend can't reach Ollama | Ensure `ollama serve` is running on the host; `OLLAMA_BASE_URL=http://host.docker.internal:11434`; the `backend` service has `extra_hosts: host.docker.internal:host-gateway`. |
| Backend DB auth fails | `POSTGRES_PASSWORD` in `.env` must match what Postgres was first initialized with. If you changed it, recreate the volume: `docker compose down -v` (⚠️ deletes data). |
| Frontend calls hit `localhost:8080` from the phone | The image was built without the single-origin args. Rebuild after setting `NEXT_PUBLIC_API_BASE_URL=` (empty) and `NEXT_PUBLIC_WS_URL=wss://…/ws`: `docker compose --profile deploy up -d --build`. |
| `https://…ts.net` not loading | Check `tailscale serve status`, that the cert was issued, and the iPhone is connected to the tailnet (MagicDNS on). |
| Model too large / slow | Per Story 1.3 the 26B is RAM-tight on 28GB; keep `ARGUS_MODEL_KEEP_ALIVE` short (`5m`) so it unloads when idle. If first Ask-AI is slow, that's the ~28s cold-load (expected). Don't set keep-alive `-1`. For a lighter option, Gemma 4 12B Unified fits with headroom. |

## Notes
- **Dev laptop (16GB)** cannot run the 26B model — local verification uses the dev profile / a small model. The prod model path is exercised only on the Mini.
- **Recovery / backup** runbook is separate (Epic 10, Story 10.3).
