# llm-cascade

**Eine geteilte Resource für mehrere Projekte, die LLMs anrufen müssen.** Statt
in jedem Projekt einzeln Provider-Code, Cooldown-Logik, Failover und Admin-UI
nachzubauen, läuft `llm-cascade` als kleiner Docker-Service neben deinem
Hauptprojekt. Du fragst per HTTP an, sie sucht das beste verfügbare Modell aus
der konfigurierten Liste raus und liefert die Antwort.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                                                                         │
│  Dein Projekt (z.B. EduPro, Switcher, …)                                │
│                                                                         │
│       │ "Bitte übersetze diesen Satz."                                  │
│       ▼                                                                 │
│  ┌─────────────────────────────────────────────┐                        │
│  │  llm-cascade Service                        │                        │
│  │                                             │                        │
│  │  1. Schaue in der Liste, was an erster      │                        │
│  │     Stelle steht: gemini-2.5-flash          │                        │
│  │  2. Anfragen → 503 (überlastet) → 30 s      │                        │
│  │     Cooldown auf flash setzen               │                        │
│  │  3. Nächstes Modell: gemini-2.5-flash-lite  │                        │
│  │     Anfragen → 200 OK                       │                        │
│  │  4. Antwort zurück                          │                        │
│  │                                             │                        │
│  └─────────────────────────────────────────────┘                        │
│       │ "Hier ist die Übersetzung."                                     │
│       ▼                                                                 │
│  Dein Projekt nutzt das Ergebnis                                        │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

## Warum das gut ist

- **Ein Update, mehrere Projekte.** Wird ein Modell deprecated (passiert, siehe
  `gemini-2.0-flash` Mai 2026), reicht ein Push auf das `llm-cascade`-Image.
  Alle Konsumenten profitieren ohne Code-Touch.
- **Auto-Disable bei 404.** Wenn ein Provider sagt „Modell gibt's nicht mehr",
  wird die Zeile in der DB auf `autoDisabled=true` gesetzt + die Begründung
  abgespeichert. Im Admin-UI siehst du sofort warum und kannst entscheiden:
  manuell wieder freigeben oder löschen.
- **Cooldown mit Auto-Promote.** Ein Modell auf 503 → 30 s Pause, nächstes wird
  probiert. Sobald der Cooldown vom Primary abläuft, springt die Cascade zurück
  auf Position 1. Selbstheilend.
- **Keine Code-Änderung nötig um Modelle zu ändern.** Admin geht in die UI,
  fügt z.B. `anthropic/claude-sonnet-4-6` hinzu, setzt den Key, fertig.

## Unterstützte Provider

| `provider`-Wert | API | Beispiel-Modelle |
|---|---|---|
| `gemini` | Google AI Studio (`generativelanguage.googleapis.com`) | `gemini-2.5-flash`, `gemini-2.5-pro`, `gemini-3-pro-preview` |
| `openai` | OpenAI direkt (`api.openai.com/v1`) | `gpt-4o`, `gpt-4o-mini`, `o1-mini` |
| `openrouter` | OpenRouter (`openrouter.ai/api/v1`) | `deepseek/deepseek-chat-v3.1`, `meta-llama/llama-3.3-70b-instruct:free` |
| `deepseek` | DeepSeek direkt (`api.deepseek.com/v1`) | `deepseek-chat`, `deepseek-coder` |
| `anthropic` | Anthropic Messages API | `claude-sonnet-4-6`, `claude-opus-4-7`, `claude-haiku-4-5-20251001` |
| `openai_compat` | Catch-all für andere OpenAI-API-kompatible Endpoints | beliebige selbst-gehostete LLMs |

Neue Provider hinzufügen = neue `@Component`-Klasse in `provider/` die
`LlmProvider`-Interface implementiert. Bean-Name = `provider`-Wert in der DB.

## So nutzt du es in deinem Projekt

### 1. Container starten

In deinem `docker-compose.yml`:

```yaml
services:
  llm_cascade:
    image: ghcr.io/4dataclub/llm-cascade:latest
    container_name: my_app_llm_cascade
    environment:
      # Wichtig: zeigt auf die DB DEINES Projekts (gleiche Postgres-Instanz,
      # eigene DB ODER eigene Tabellen-Namespace). llm-cascade legt die
      # nötigen Tabellen via JPA ddl-auto=update selbst an.
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/my_app_db
      SPRING_DATASOURCE_USERNAME: my_app_user
      SPRING_DATASOURCE_PASSWORD: my_app_pass
    depends_on: ["db"]
    restart: unless-stopped
```

### 2. Bei Erststart Defaults pushen

llm-cascade startet mit leerer Modell-Liste. Dein Backend muss beim Boot
einmalig die Default-Modelle anlegen:

```bash
# Pseudocode -- in deinem Spring Boot DataInitializer:
if (cascadeApi.getModels().isEmpty()) {
    cascadeApi.createModel("gemini", "gemini-2.5-flash", "geminiApiKey");
    cascadeApi.createModel("gemini", "gemini-2.5-flash-lite", "geminiApiKey");
}
```

### 3. LLM-Calls machen

```bash
curl -X POST http://llm_cascade:8090/api/generate \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Übersetze ins Französische: Hallo Welt.", "service": "ui-i18n", "lang": "fr"}'
```

Response:
```json
{
  "text": "Bonjour le monde.",
  "model": "gemini:gemini-2.5-flash",
  "latencyMs": 812
}
```

### 4. API-Keys live setzen

```bash
# Über die HTTP-API:
curl -X POST http://llm_cascade:8090/api/settings/geminiApiKey \
  -H "Content-Type: application/json" \
  -d '{"value": "AIza..."}'
```

Oder direkt in der DB der `app_settings`-Tabelle. llm-cascade liest beim
nächsten Call automatisch den neuen Wert.

## HTTP-API

```
POST /api/generate               -- LLM-Call durch die Cascade
GET  /api/models                 -- Modell-Liste (für Admin-UI)
POST /api/models                 -- Neues Modell anlegen
PUT  /api/models/{id}            -- Modell-Toggle / Update
DELETE /api/models/{id}          -- Modell löschen
POST /api/models/reorder         -- Liste neu sortieren (cascade-order)
POST /api/models/{id}/test       -- Smoke-Test eines Modells
GET  /api/health                 -- Liveness (no DB)
GET  /api/health/keys            -- Welche Keys fehlen für enabled-Modelle?
GET  /api/settings               -- Alle App-Settings (mit Masking)
POST /api/settings/{key}         -- Wert setzen
GET  /api/stats/cascade          -- Aktive Modelle + Cooldown-Restzeit
GET  /api/stats/calls            -- Letzte 50 Calls
GET  /api/stats/failover         -- Letzte Failover-Events
```

## Wie die Cascade entscheidet

```
                     ┌──────────────────────────┐
                     │ Promote-Check vor Call:  │  ← bei jedem generate()
                     │ Primary-Cooldown abgelau-│
                     │ fen? → activeIdx = 0     │
                     └────────────┬─────────────┘
                                  ▼
              ┌───────────────────────────────────────┐
              │  Try model[activeIdx]                 │
              └───────────────────────────────────────┘
                                  ▼
       ┌──────────┬─────────────┬─────────────┬──────────────┐
       │          │             │             │              │
      200       429 short     429 long       503           404
      OK       retryDelay     retryDelay   server         model
                (<90s)        (≥90s)       error          gone
       │          │             │             │              │
       ▼          ▼             ▼             ▼              ▼
    Antwort   wait + retry   Cooldown +    Cooldown +    AUTO-DISABLE
    zurück   SAME Modell    next Modell   next Modell   in DB + Reason
                                                          + next Modell
                                  │
                                  ▼
                       ┌──────────────────────┐
                       │ Alle Modelle weg?    │
                       └──────────┬───────────┘
                                  ▼
                       Cascade-Exhausted Error
                       (klar, nicht 14h-hang)
```

## Datenbank

llm-cascade legt diese Tabellen in der per `SPRING_DATASOURCE_URL` angegebenen
DB an (JPA `ddl-auto=update`):

| Tabelle | Inhalt |
|---|---|
| `ai_model_config` | Eine Zeile pro konfiguriertem Modell. Provider, modelId, orderIdx, enabled, autoDisabled+Reason, cooldown-Override. |
| `llm_call_log` | Eine Zeile pro LLM-Call -- für Stats (welcher Service, welche Sprache, welches Modell, Output-Größe, Erfolg). |
| `llm_failover_events` | Timeline aller Switch-Downs / Promotes / Auto-Disables. |
| `app_settings` | Generischer Key-Value-Store (API-Keys live editierbar). Wird mit dem Host-Projekt geteilt wenn beide dieselbe DB nutzen -- ein Bonus: `geminiApiKey` im Host-Admin gesetzt → llm-cascade sieht's automatisch. |

## Architektur — wer kennt wen

```
   ┌──────────────────────────────────────────────────────────────────────┐
   │                                                                      │
   │              4dataclub/llm-cascade  (dieses Repo)                    │
   │              ─────────────────────────────────────                   │
   │              Spring Boot 3 + Java 17                                 │
   │                                                                      │
   │              ┌───────────────────────────────┐                       │
   │              │ HTTP-API (Port 8090)          │                       │
   │              ├───────────────────────────────┤                       │
   │              │ LlmCascadeService             │                       │
   │              │  - cooldown state             │                       │
   │              │  - promote-if-primary-free    │                       │
   │              ├───────────────────────────────┤                       │
   │              │ Map<provider-name, Impl>      │                       │
   │              │  gemini · openai · openrouter │                       │
   │              │  deepseek · anthropic · ...   │                       │
   │              └─────────────┬─────────────────┘                       │
   │                            │ JDBC                                    │
   │                            ▼                                         │
   │              ┌───────────────────────────────┐                       │
   │              │ Host-Project Postgres-DB      │                       │
   │              │ (eigene DB pro Projekt --      │                       │
   │              │  ai_model_config-Tabellen     │                       │
   │              │  pro Projekt isoliert)        │                       │
   │              └───────────────────────────────┘                       │
   │                                                                      │
   └──────────────────────────────────────────────────────────────────────┘
                            ▲                              ▲
                            │ HTTP/8090                    │ HTTP/8090
                            │ Docker-Network               │ Docker-Network
                            │                              │
          ┌─────────────────┴──────────┐  ┌────────────────┴────────────┐
          │                            │  │                             │
          │  EduPro                    │  │  Switcher (Java-Rewrite)    │
          │                            │  │                             │
          │  Angular Frontend          │  │  Angular Frontend (NEU)     │
          │  → Admin „KI-Modelle"      │  │  → Admin „Modelle"          │
          │  → ruft llm-cascade API    │  │  → ruft llm-cascade API     │
          │                            │  │                             │
          │  Spring Boot Backend       │  │  Spring Boot Backend (NEU)  │
          │  → Content-Gen + Agent     │  │  → Claude-Console-Routing   │
          │  → ruft llm-cascade API    │  │  → ruft llm-cascade API     │
          │                            │  │                             │
          │  Postgres (edupro-DB)      │  │  Postgres (switcher-DB)     │
          │   + llm-cascade-Tabellen   │  │   + llm-cascade-Tabellen    │
          │                            │  │                             │
          │  Default: gemini-2.5-flash │  │  Default: claude-sonnet-4-6 │
          │                            │  │                             │
          └────────────────────────────┘  └─────────────────────────────┘
```

**Daten isoliert pro Projekt** — die `ai_model_config`-Tabellen in EduPro-DB
und in Switcher-DB sind komplett getrennt. Was EduPro konfiguriert hat, sieht
Switcher nicht und umgekehrt. Code (Provider-Implementations, Cascade-Logik)
ist dasselbe Image.

## Development

```bash
# Standalone (mit eigenem Postgres-Container für lokales Testen):
docker compose up -d --build
# → http://localhost:8090/api/health

# Smoke-Test:
curl -X POST http://localhost:8090/api/models \
  -H "Content-Type: application/json" \
  -d '{"provider":"gemini","modelId":"gemini-2.5-flash","apiKeySettingKey":"geminiApiKey"}'

curl -X POST http://localhost:8090/api/settings/geminiApiKey \
  -H "Content-Type: application/json" \
  -d '{"value":"AIza..."}'

curl -X POST http://localhost:8090/api/generate \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Sag hallo auf Französisch.","service":"smoke","lang":"fr"}'
```

## Status

- Provider: `gemini`, `openai`, `openrouter`, `deepseek`, `anthropic`, `openai_compat` (catch-all)
- Cascade-Logik 1:1 aus EduPro extrahiert (`feedback_no_architecture_changes`-konform)
- Auto-Disable bei `MODEL_INVALID` (HTTP 404) — Reason wird in DB persistiert
- Setup-canonical: docker-compose.yml + DataInitializer (kein setup.sh nötig — Web-App-Pattern, siehe `feedback_setup_canonical`)

## Lizenz

Intern, 4dataclub.
