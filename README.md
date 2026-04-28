# AI PMO Agent (MVP)

Monorepo with a **Spring Boot** REST backend and an **Angular** dashboard. The agent scores work items for delays (dwell time, PR cycle, status churn), enriches with **Jira** and **GitHub** when configured (otherwise **mock** or **simulated** data), calls **Groq** (OpenAI-compatible chat completions) for structured insights, and optionally posts selective alerts to a **Microsoft Teams** incoming webhook.

## Prerequisites

- **JDK 17+** (set `JAVA_HOME`). This repo includes the **Gradle Wrapper** (`gradlew` / `gradlew.bat`) in [`backend`](backend/); you do not need a global Gradle install.
- **Node.js 18.19+** (recommended) and **npm** for the frontend.
- **Groq API key** ([Groq console](https://console.groq.com/keys)); set environment variable `GROQ_API_KEY` or `groq.api.key` in `local-keys.properties`.
- Optional: **Teams incoming webhook URL** for channel notifications.
- Optional: **Jira** + **GitHub** — see `application.properties` and `backend/config/local-keys.properties.example`.

## Configuration (secrets)

Do not commit real keys. Use **one** of these:

### Option A — Local file (easiest)

1. In [`backend/config/`](backend/config/), copy the example file:
   - **Windows (CMD):** `copy local-keys.properties.example local-keys.properties`
   - **PowerShell:** `Copy-Item local-keys.properties.example local-keys.properties`
2. Edit **`backend/config/local-keys.properties`** and set:
   - `groq.api.key=gsk_...` (or rely on `GROQ_API_KEY` from the environment)
   - `teams.webhook.url=...` (optional)
   - Jira / GitHub keys as documented in the example file

`local-keys.properties` is [gitignored](.gitignore) and loaded automatically via `spring.config.import` in [`application.properties`](backend/src/main/resources/application.properties).

### Option B — Environment variables

| Variable | Purpose |
|----------|---------|
| `GROQ_API_KEY` | Groq API key (also set as `groq.api.key` via `application.properties`) |
| `TEAMS_WEBHOOK_URL` | Teams webhook URL (optional) |

Env vars override values from the local file if both are set.

Default Groq model is `llama-3.3-70b-versatile` (change with `groq.model` in `local-keys.properties` or `application.properties`).

HTTP timeouts for outbound REST (Jira, GitHub, Groq LLM) are controlled in [`application.properties`](backend/src/main/resources/application.properties): `http.client.connect-timeout-ms`, `http.client.read-timeout-ms`, and `http.client.openai.*` for the LLM client.

## Run the backend

```bash
cd backend
set GROQ_API_KEY=your_key_here
set TEAMS_WEBHOOK_URL=https://outlook.office.com/webhook/...   # optional
gradlew.bat bootRun
```

(On PowerShell use `$env:GROQ_API_KEY = "..."`. On macOS/Linux use `./gradlew bootRun` instead of `gradlew.bat`.)

Other useful tasks: `gradlew.bat build`, `gradlew.bat test`.

API base URL: `http://localhost:8080`

| Endpoint | Method | Query | Description |
|----------|--------|--------|---------------|
| `/api/tickets` | GET | `simulation=true` optional | Tickets after **metrics** (Jira/GitHub, mock, or **simulated** dataset) |
| `/api/run-agent` | POST | `simulation=true` optional | Full agent pipeline: metrics → AI for outliers → selective Teams → returns `AgentRunResponse` |
| `/api/insights` | GET | `simulation=true` optional | Last **run-agent** snapshot if present; else baseline analyzed payload |

### Simulation mode

`simulation=true` forces the curated **SIM-** ticket set (demo-friendly). Omit the parameter or use `false` for normal resolution (integrations + mock fallback).

### API response highlights

- **`ProjectSummary`**: `trendSummary` (run-over-run vs last agent run when available, else vs baseline), `reasonForStatus` (plain-language RED/AMBER/GREEN), plus counts and `prDelayTrendPercent`.
- **`Ticket`**: `flags` (e.g. `STUCK`, `PR_DELAY`, `TREND_SPIKE`, `SLOWDOWN`, `BOUNCING`), `trendIndicator` (`UP` / `DOWN` / `STABLE` vs batch), `confidence` (AI `LOW`/`MEDIUM`/`HIGH` on outliers), severity, PR/dwell hours.

## Run the frontend

If `node_modules` is incomplete (e.g. missing `fesm2022/*.mjs` under `@angular/core`, or `ng build` cannot resolve `@angular/core`), delete `frontend/node_modules` and `frontend/package-lock.json`, then run `npm install` again.

```bash
cd frontend
npm install
npm start
```

Open `http://localhost:4200`. The dev server uses [`proxy.conf.json`](frontend/proxy.conf.json) to forward `/api` to `http://localhost:8080`, so start the backend first.

**Without proxy:** set the API base in [`api.service.ts`](frontend/src/app/services/api.service.ts) to `http://localhost:8080` and rely on backend CORS (already enabled for `http://localhost:4200`).

The dashboard includes a **“Use simulated demo data”** toggle that passes `simulation=true` to the API.

## Flow

1. Open the dashboard — loads `GET /api/insights` (respects simulation toggle).
2. Click **Run Agent** — `POST /api/run-agent` applies metrics, calls Groq for **MEDIUM/HIGH** outliers, sends **Teams** only for **HIGH** or **multiple flags** (with dedupe and cooldown), stores the snapshot for insights.

## IDE (Cursor / VS Code) — Java errors on every file

If Spring/Java show errors until types “cannot be resolved”:

1. Install the **Extension Pack for Java** (and optionally **Spring Boot Extension Pack**). This repo lists them under [`ai-pmo-agent/.vscode/extensions.json`](ai-pmo-agent/.vscode/extensions.json).
2. Ensure **JDK 17+** is installed and selected: Command Palette → **Java: Configure Java Runtime** (or set `java.jdt.ls.java.home` in settings).
3. **Gradle** import is enabled in [`.vscode/settings.json`](../.vscode/settings.json) or [`ai-pmo-agent/.vscode/settings.json`](ai-pmo-agent/.vscode/settings.json) (`java.import.gradle.enabled`, wrapper enabled). Open the folder that contains [`backend/build.gradle.kts`](backend/build.gradle.kts).
4. Command Palette → **Java: Clean Java Language Server Workspace**, then reload when prompted.
5. **Lombok** is enabled in settings; `@Data` / `@Builder` require this. After changing `build.gradle.kts`, run **Java: Force Java Compilation** → **Full**.

If the first `./gradlew` run fails while downloading Gradle with an SSL error, fix corporate proxy or JDK trust store, or install Gradle locally and run `gradle wrapper` once.

## Project layout

```
ai-pmo-agent/
  backend/     # Spring Boot (Java, Gradle)
  frontend/    # Angular CLI app
```
