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
| `ollama` | Lokales Ollama (`${OLLAMA_BASE_URL}`, OpenAI-kompatibel) | `llama3.2:3b`, `qwen2.5-coder:7b`, `gemma3:4b` |
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

## Routing-Strategien — was der Caller wählt

llm-cascade kennt **drei Routing-Mechanismen**, die sich beliebig kombinieren:

### 1. Explizite Kategorie (klassisch, immer verfügbar)

```json
POST /api/generate
{ "prompt": "...", "category": "content" }
```

Probiert nur Modelle mit `category=content` (plus `general` als Fallback).
Klassisches Failover bei HTTP-Fehler innerhalb der Kategorie.

### 2. Semantic Routing via `purpose` (seit v0.6.0)

Statt eine hardcoded Kategorie zu wählen, beschreibt der Caller den Task
in natürlicher Sprache:

```json
POST /api/generate
{ "prompt": "...", "purpose": "übersetze deutsche i18n keys nach französisch" }
```

`SemanticCategoryRouter` macht einen Mini-LLM-Call mit den
`category_meta.description`-Texten aller verfügbaren Kategorien und
entscheidet welche passt. Resultat wird im In-Mem-LRU-Cache abgelegt
(1000 Slots, 24h TTL, key = SHA-256 des trimmed-lowercase `purpose`).

**Cache-Invalidation:** jeder `PUT/DELETE /api/categories/{name}` leert
den Cache komplett — sonst würden stale Routing-Decisions auf alte
Descriptions zeigen.

**Endpoints:**
- `GET    /api/routing/cache` — Snapshot + Stats
- `DELETE /api/routing/cache` — komplett leeren
- `DELETE /api/routing/cache/{purposeHash}` — einzelnen Eintrag
- `POST   /api/routing/test` — Test-Preview ohne echten Generate-Call

**Routing-Calls werden mit `service="__routing__"` im `llm_call_log`
geloggt** — damit sie im Stats-Tab vom eigentlichen Payload-Call
unterscheidbar sind.

### 3. Auto-Escalation via `escalate` (v0.7.0 — geplant)

```json
POST /api/generate
{
  "prompt": "Generiere Mathe-Übung 7. Klasse",
  "purpose": "Lehrcontent für Schulkinder",
  "escalate": true,
  "validatorSchema": { "type": "object", "required": ["frage","antwort"], ... }
}
```

llm-cascade durchläuft die Kategorien sortiert nach `category_meta.orderIdx`
(=Tier-Reihenfolge) und validiert nach jedem Call:

```
┌───────────────────────────────────────────────────────────────┐
│  [1] Semantic Router wählt Initial-Tier (purpose → category): │
│      Read category_meta.description aller Kategorien          │
│      → Antwort: „content" (weil description „Lehrcontent")    │
│                                                                │
│  [2] Versuche Initial-Tier:                                    │
│      gemini-2.5-flash → Antwort                                │
│      Validator-Pipeline:                                        │
│        a) JSON.parse() ok?                                     │
│        b) validatorSchema-Match? (org.everit:json-schema)      │
│        c) Quality-Heuristik (Refusal-Phrasen, Min-Length)      │
│      ✗ Schema-fail → ESCALATE                                  │
│                                                                │
│  [3] Auto-Escalation — nächstes Tier nach orderIdx:            │
│      → Tier „dev" (orderIdx +1)                               │
│      gemini-2.5-pro → Antwort                                  │
│      Validator → ✓ pass → RETURN                              │
└───────────────────────────────────────────────────────────────┘
```

**Failover ≠ Auto-Escalation:**

| | Failover | Auto-Escalation |
|---|---|---|
| **Trigger** | HTTP-Fehler (429, 503, Timeout) | Validator-Fail (Schema, Quality) |
| **Sprung** | Nächstes Modell, selbe Kategorie | Nächste Kategorie nach orderIdx |
| **Kombiniert** | innerhalb Tier | Tier → Tier |

Auto-Escalation kombiniert beide Mechanismen: zuerst Failover innerhalb
Tier, dann Escalate auf nächsten Tier wenn alles fehlschlägt.

## Eine Frage, drei Türen — wer wählt die Kategorie?

Die Mechanismen oben füllen **denselben einen `category`-Schlitz** pro Call.
Stell dir llm-cascade als **Poststelle mit beschrifteten Fächern** vor: jede
Anfrage ist ein Brief, der in genau **ein** Fach muss. Es gibt **eine Frage** —
*„welches Fach / welcher Spezialist?"* — und **mehrere Türen** zur Antwort, mit
fester Präzedenz (`ApiController`):

```
   Frage: "Welche Kategorie macht das?"
   │
 ① Body-`category` gesetzt   → explizites Etikett, der Caller weiß es schon
   │   schlägt …
 ② preferredCategory-Setting → manueller Override (UI-Toggle / "alles nach X")
   │   schlägt …
 ③ purpose gesetzt           → SemanticCategoryRouter rät aus dem Inhalt
   │   sonst …
 ④ general                   → Fallback
```

**Immer nur EINE Tür pro Brief** — ① Etikett > ② Override > ③ Scanner > ④ general.
Nicht zwei gleichzeitig am selben Call.

**Zwei Konsumenten, andere Tür:**

| | **EduPro** | **Switcher Supermodell=AN** |
|---|---|---|
| Genutzte Tür | ③ Semantic (`purpose`) — bzw. ② Override | ① explizites `category` im Body |
| Kategorie = | Task-Typ (content/dev/utility/general) | Compound `<rolle>-<pool>` |
| Wer wählt | llm-cascade klassifiziert automatisch | Orchestrator-Agent labelt + manueller Pool |

Beide nutzen **dieselbe Maschine** — der Unterschied ist nur, **wer** die Kategorie
wählt und **worauf basierend**, nicht der Code-Pfad.

## Kategorien als Tiers — die zentrale Architektur-Idee

Die `category_meta.orderIdx` ist nicht nur „UI-Sortierung" — sie definiert
**Eskalations-Tiers**. Lokale, billige Modelle stehen in Tier 0; Cloud-Premium
in Tier N.

```
┌──────────────────────────────────────────────────────────────────────┐
│  Kategorien WERDEN zu Tiers                                          │
│                                                                       │
│  category    orderIdx   Modelle                  Tier-Charakter      │
│  ────────    ────────   ───────────────────────  ─────────────────── │
│  utility            0   ollama:llama3.2:3b       Tier 0: lokal,     │
│                         ollama:gemma3:4b           simpel, gratis,   │
│                                                    Daten bleiben     │
│                                                    im Haus           │
│                                                                       │
│  content            1   gemini-2.5-flash         Tier 1: Cloud      │
│                         gemini-2.5-flash-lite      mittel, billig,  │
│                                                    Standard-Übungen │
│                                                                       │
│  dev                2   gemini-2.5-pro           Tier 2: Cloud      │
│                         claude-opus-4-7            premium, komplex, │
│                                                    Code-Reviews,     │
│                                                    Final QA          │
│                                                                       │
│  general           99   (was übrig bleibt)       globaler Fallback  │
└──────────────────────────────────────────────────────────────────────┘
```

**Was du als Admin im UI definierst** (alles ohne Code-Edit):
- **Kategorien + descriptions** — Semantic Router weiß was wofür
- **`orderIdx`** — Tier-Reihenfolge für Eskalation
- **Modelle pro Kategorie** — was probiert wird

→ User-controllable, ohne Neustart wirksam.

## Dynamisches Routing pro Caller

Jeder Caller liefert seinen eigenen `purpose`. Der Router entscheidet pro
Call individuell — dieselbe Cascade-API, beliebig viele Caller, jeder
bekommt seinen optimalen Routing-Pfad:

```
ExercisePoolService          purpose="Mathe-Übung 7. Klasse"           → content
VocabularyI18nService        purpose="übersetze i18n keys nach FR"     → utility
ExamGeneratorService         purpose="Prüfung schwierig + Erklärungen" → content/dev
TesterAgent                  purpose="Test-Cases für UserService Java" → dev
BackendAgent                 purpose="Spring Boot Endpoint generieren" → dev
FrontendAgent                purpose="Angular Component User-Profil"   → dev
ProjektleiterAgent           purpose="Sprint-Plan aus 12 PRs"          → content/dev
ChatAgent (kindgerecht)      purpose="Schüler-Chat Photosynthese"      → content
SwitcherClaude (lokal-first) purpose="schneller Refactor Java"         → free-only/local
```

Selbe Cascade-API, beliebig viele Caller, jeder bekommt seinen optimalen
Routing-Pfad. Du als Admin definierst nur die Kategorien + descriptions.

## Hardware-Realitäts-Check

Lokale Modelle hängen an Server-Hardware:

| Modell                | RAM-Bedarf | Status auf CPU-Only mit 8 GB RAM |
|-----------------------|-----------:|----------------------------------|
| `llama3.2:3b`         | ~2 GB      | ✓ stabil                         |
| `gemma3:4b`           | ~3 GB      | ✓ stabil                         |
| `qwen2.5:7b-instruct` | ~5 GB      | ✗ OOM-Crash beim Laden          |
| `qwen3-coder:30b`     | ~18 GB     | ✗ braucht GPU mit 24 GB VRAM    |
| `gemma4:24b`          | ~16 GB     | ✗ braucht GPU mit 16 GB VRAM    |
| `llama3.1:70b`        | ~40 GB     | ✗ braucht GPU-Cluster           |

**Konsequenz:** Auf CPU-Only-Servern stehen in Tier 0 nur 3-4B-Modelle.
Tier 1+ MUSS Cloud sein. Für „echtes lokal-only" → 16+ GB VRAM-Hardware.

## Hardware-Safety — Server darf nicht lahmgelegt werden

**Kritische Anforderung:** Ein Modell darf nicht aktivierbar/nutzbar sein,
wenn die Server-Hardware nicht ausreicht. Sonst kann ein zu großes
Ollama-Modell beim Laden den Server in OOM-Crash zwingen.

```
┌──────────────────────────────────────────────────────────────────────┐
│  Preflight-Check vor Modell-Aktivierung (v0.7.0 — geplant)           │
│                                                                       │
│  POST /api/models   { provider:"ollama",                             │
│                       modelId:"qwen3-coder:30b", enabled:true }      │
│       ↓                                                               │
│  HardwareChecker.validate(provider, modelId):                        │
│     1. provider != "ollama" → OK (Cloud, kein lokales Resource-Lock) │
│     2. provider == "ollama":                                         │
│        a) Ollama API: GET /api/show?name=qwen3-coder:30b            │
│           → modelSize: 18 GB                                          │
│        b) System: /proc/meminfo + nvidia-smi                         │
│           → freeRAM: 4.5 GB, freeVRAM: 0 GB                          │
│        c) modelSize > 0.8 * (freeRAM + freeVRAM) → FAIL              │
│        d) HTTP 422 mit Begründung                                    │
│           { ok:false,                                                 │
│             error:"Hardware unzureichend",                           │
│             details:"18 GB nötig, 4.5 GB frei" }                    │
└──────────────────────────────────────────────────────────────────────┘
```

**Abgefangen:** zu großes Ollama-Modell auf CPU-only-Server, GPU-Modell
ohne GPU vorhanden. Sicherheitspuffer 20% (`modelSize > 0.8 * available`).

**Nicht geprüft:** Cloud-Provider (laufen nicht lokal), Disk-Space (Ollama
handelt das selbst).

**Konfigurierbar:**
```
hardware.check.enabled=true
hardware.check.ram-safety-factor=0.8
hardware.check.allow-cpu-fallback=true
```

## Inferenz-Server pro Modell — localhost oder extern (v0.8.0)

**Das Problem in einfachen Worten:** Lokale Modelle (Ollama) laufen normalerweise
auf demselben Rechner wie llm-cascade — „localhost". Wenn dieser Rechner aber zu
schwach ist (kein GPU, wenig RAM), willst du ein Modell vielleicht auf einen
**stärkeren externen Server** auslagern (z.B. eine GPU-Maschine im Büro), ohne
alle anderen Modelle anzufassen. Cloud-Modelle (Gemini, OpenRouter…) sind davon
nicht betroffen — die haben feste Adressen im Internet.

**Wie es funktioniert:** Du legst benannte **Provider-Server** an (einer ist der
Default „localhost"). Jedes Modell kann optional einen davon auswählen. Wählt es
keinen, nimmt ein Ollama-Modell automatisch den Default-Server.

```
   Ein Modell soll antworten
            │
            ▼
   Welcher Server ist gemeint?  (ProviderServerResolver)
            │
   ┌────────┴───────────────────────────────────────────────┐
   │ 1. Modell hat einen Server-Namen?                       │
   │       └─► dessen URL          z.B. http://gpu-box:11434/v1
   │ 2. Modell hat eine direkte URL? (legacy)                │
   │       └─► diese URL                                     │
   │ 3. Ist es ein Ollama-Modell ohne Auswahl?               │
   │       └─► Default-Server "localhost"  http://ollama:11434/v1
   │ 4. Sonst (Cloud-Provider)                               │
   │       └─► fester Provider-Default (Gemini/OpenRouter…)  │
   └────────┬───────────────────────────────────────────────┘
            ▼
   generate(prompt, modelId, apiKey, effectiveBaseUrl)
            ▼
   POST {effectiveBaseUrl}/chat/completions
```

**Wichtig (vorher ein Bug):** Bis v0.7.x wurde dieser „effektive Server" zwar für
den Hardware-Check berechnet, der echte Inferenz-Call lief aber trotzdem immer
auf dem statischen localhost. Seit v0.8.0 trifft der Call wirklich den gewählten
Server — sonst hätte das Hardware-Badge „extern entsperrt" gelogen.

**API (CRUD für Server):**
```
GET    /api/provider-servers           Liste
PUT    /api/provider-servers/{name}    anlegen/ändern  {baseUrl, isDefault?, description?}
DELETE /api/provider-servers/{name}    löschen (Default nicht löschbar)
```
Der Default-Server „localhost" wird beim ersten Backend-Start automatisch
angelegt (`DefaultProviderServerInit`).

### Auto-Provision: Modell landet automatisch auf dem Server (v0.8.1)

„Man muss nichts tun": weist man ein Ollama-Modell einem Server zu, **pullt die
Cascade das Modell dort automatisch** — du musst dich nicht per SSH auf den
Server hängen und manuell `ollama pull` machen.

Wichtig zum Verständnis: **die Cascade rechnet nie selbst, sie ist nur ein
HTTP-Client zu Ollama.** Auf dem externen Server muss **Ollama bereits laufen**
(seine Base-URL hast du ja eingetragen). „Aufsetzen" heißt hier nur: das Modell
auf dieses Ollama ziehen.

```
[1] Server eintragen      gpu-box → http://gpu-box:11434/v1
[2] Modell zuweisen (UI-Dropdown in der Modell-Tabelle)
        │
        ▼
[3] Cascade ──POST {url}/api/pull {name}──▶ Ollama@gpu-box   (lädt die Gewichte)
        │       (OllamaProvisioner, async, In-flight-Guard)
        ▼
[4] Generate ──POST {url}/v1/chat/completions──▶ Ollama@gpu-box
        ▼
[5] Ollama@gpu-box rechnet (GPU) ──Text──▶ Cascade ──▶ Aufrufer
```

Trigger: `modelCreate` + `modelUpdate` (bei `provider=ollama`) lösen den Pull aus
— auch für den localhost-Default, damit lokale Modelle ohne Handgriff bereitstehen.
Hinweis: für **Übersetzung** ist ein Cloud-Modell (DeepSeek/OpenRouter) meist
billiger + besser als ein kleines lokales Modell auf CPU; lokale Server lohnen
sich v.a. mit GPU oder aus Datenschutz-Gründen.

## Manueller Override (Switcher-Use-Case)

EduPro vertraut Auto-Escalation, Switcher will manuelle Kontrolle. Caller
kann `maxTier` setzen — Hard-Limit für Escalation:

```json
POST /api/generate
{
  "purpose": "...",
  "escalate": true,
  "maxTier": 0       ← Escalate NUR bis Tier 0. Bei Validator-Fail:
}                       HTTP 503 statt Cloud-Switching.
```

**Konsumenten-Unterschied:**

| App | Default | UI-Toggle? |
|---|---|---|
| **EduPro** | `auto` (kein maxTier) — System entscheidet | nein, transparent |
| **Switcher** | `manual` (maxTier=N) — User wählt | ja, prominent |

## Stats-Tracking — wie verfolge ich Routing-Entscheidungen?

**Was wirklich pro Call in `llm_call_log` gespeichert wird:** `service`, `lang`,
`provider`, `model`, `category`, `success`, `outputChars`, `calledAt` — plus zwei
bewusst optionale Felder:

| Feld | wann befüllt | warum optional |
|---|---|---|
| `promptSnippet` | nur wenn Setting `logPromptSnippet=true` | Datenschutz — Nutzer-Prompts sonst nicht in der DB |
| `output` | nur bei `service="__routing__"` | speichert die vom Router gewählte Kategorie (max. 32 Zeichen), nie Chat-Inhalte |

**Auswertung über die Stats-Endpunkte** (das Admin-UI rendert daraus Charts):

```
GET /api/stats/calls                 letzte 50 Calls
GET /api/stats/quality               Erfolgsquote + Score-Tier je Modell
GET /api/stats/performance           Latenz + Durchsatz je provider:model
GET /api/stats/trend                 Calls/Tag (Erfolg vs. Fehler)
GET /api/stats/totals                Aggregate (24h / 7d / 30d)
GET /api/stats/failover[-breakdown]  Failover-Events + Aufschlüsselung
GET /api/stats/log-snippets          Prompt-Snippet-Feed (nur wenn Logging AN)
```

**Datenschutz-Schalter `logPromptSnippet` (Default AUS):** pro Call landet ein
gekürzter Prompt-Ausschnitt (max. 160 Zeichen) in `llm_call_log.prompt_snippet`
— **nur** wenn der Schalter explizit AN ist. Standardmäßig `null`, damit
Kunden-Eingaben im Normalbetrieb nicht persistiert werden. Gedacht für
Debug/Live-Watch; jederzeit zur Laufzeit umschaltbar (kein Rebuild):

```bash
curl -X POST http://localhost:8090/api/settings/logPromptSnippet -d 'true'   # AN
curl -X POST http://localhost:8090/api/settings/logPromptSnippet -d 'false'  # AUS
```

`GET /api/stats/calls` liefert das Feld als `promptSnippet` mit aus (von den
Watch-Skripten genutzt).

**SQL-Beispiel (Erfolgsquote je Modell, letzte 24 h):**
```sql
SELECT provider, model, COUNT(*) AS total,
       ROUND(100.0 * SUM(CASE WHEN success THEN 1 ELSE 0 END) / COUNT(*), 1) AS success_pct
FROM llm_call_log
WHERE called_at > NOW() - INTERVAL '24 hours'
GROUP BY provider, model
ORDER BY total DESC;
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
- v0.8.0: Per-Modell **Inferenz-Server-Routing** — Ollama-Modell auf externen Server auslagern, Default bleibt localhost (`ProviderServerResolver`, siehe „Inferenz-Server pro Modell")
- Cascade-Logik 1:1 aus EduPro extrahiert (`feedback_no_architecture_changes`-konform)
- Auto-Disable bei `MODEL_INVALID` (HTTP 404) — Reason wird in DB persistiert
- Setup-canonical: docker-compose.yml + DataInitializer (kein setup.sh nötig — Web-App-Pattern, siehe `feedback_setup_canonical`)

---

## Pool × Area Architektur (ab v0.9.0)

### Konzept

Das Routing-Datenmodell wurde von einem einfachen `category`-Feld auf eine
zweidimensionale **Pool × Area Matrix** umgestellt.

```
           AREA →   cloud     free      local
POOL ↓             ───────   ───────   ───────
  cloud            Opus 4.7    —         —
  free               —       deepseek    —
  local               —         —      qwen2.5
```

| Dimension | Bedeutung | Wer entscheidet |
|-----------|-----------|-----------------|
| **Pool**  | Technischer Kontext (cloud / free / local) | Nutzer — nie automatisch |
| **Area**  | Fachliche Rolle (implement / content / cloud …) | Nutzer oder SemanticRouter |

Jede Kombination `pool × area` kann einem eigenen Modell zugewiesen werden.
Modelle in einer Kombination bilden die **Cascade** (Failover-Reihenfolge).

### Datenmodell

`AiModelConfig` hat jetzt drei neue Felder:

| Feld | Typ | Beispiel |
|------|-----|---------|
| `pool` | String(50) | `cloud` |
| `area` | String(50) | `implement` |
| `orchestrator` | Boolean | `true` (nur Orchestrator-Area) |

**Backward-Compat:** Das alte `category`-Feld bleibt erhalten. Beim Start leitet
`PoolAreaMigrationRunner` automatisch `pool` + `area` aus `category` ab:
- `implement-cloud` → pool=cloud, area=implement
- `orchestrator-cloud` → pool=cloud, area=orchestrator, orchestrator=true
- `cloud` → area=cloud (pool-benannte Catch-All)

### Routing-Formate (loadCascade)

`loadCascade(category)` versteht drei Formate:

```
"cloud:implement"   →  pool=cloud, area=implement  (neu, explizit)
"implement-cloud"   →  pool=cloud, area=implement  (Legacy, letzer Bindestrich)
"content"           →  category=content (altes System, edupro-Compat)
```

### OpenAI-Compat Endpoint

Neuer Endpunkt `POST /v1/chat/completions` für ccr-Router-Integration:

```bash
curl -X POST http://localhost:8090/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "cloud",
    "messages": [{"role":"user","content":"Hallo!"}],
    "stream": false
  }'
```

Das `model`-Feld wird als Routing-Target interpretiert:

| Wert | Bedeutung |
|------|-----------|
| `cloud` | Pool-Catch-All für supermodel=AUS, pool=cloud |
| `orchestrator-cloud` | Orchestrator-Area für supermodel=AN, pool=cloud |
| `implement-cloud` | Delegate-Area implement, pool=cloud |

> **⚠ Tool-Calling-Status (wichtig):** Dieser Endpunkt ist heute **text-only** —
> `extractPrompt()` nimmt nur den letzten User-Text, die Antwort enthaelt nur
> `content`. Ein `tools`-Array im Request wird verworfen, `tool_calls` werden NIE
> zurueckgegeben. Folge: **agentische Nutzung funktioniert NICHT durch die cascade** —
> ein Tool-Client (Claude Code: Read/Edit/Bash) oder eine Orchestrator-Delegation
> (supermodel=AN, Delegation IST ein Tool-Call) verliert hier die Tool-Calls und
> degradiert zum Chatbot. Nur reine Textnutzung (z.B. EduPro Content/i18n) ist
> unbetroffen.
>
> **Soll (geplant) — Tool-Passthrough:** `tools` durchreichen, `tool_calls`
> zurueckgeben, Ollama via `/api/chat`, provider-uebergreifender `ToolCallNormalizer`,
> Failover mit Message-History. Voll-Doku, Status & Umsetzungsplan:
> **`claude-code-switcher/docs/ARCHITEKTUR-tool-calling-pfade.md`**.

### supermodel=AUS vs supermodel=AN

#### SWITCHER supermodel=AUS

```
POOLS: cloud · free · local
AREAS: cloud "Allgemein, Fallback"
       free  "Allgemein, Fallback"
       local "Allgemein, Fallback"

MODELLE: cloud × cloud → Opus 4.7
         free  × free  → deepseek-free
         local × local → qwen2.5-coder

→ ccr-Router → llm-cascade (model="cloud" / "free" / "local")
→ kein Semantic-Routing — jeder Pool hat eine eigene Catch-All-Area
```

#### SWITCHER supermodel=AN

```
POOLS: cloud · free · local
AREAS: implement · review · research · dispatch · orchestrator ★

MODELLE: cloud × orchestrator → Opus 4.8   (gepinnt, isOrchestrator=true)
         cloud × implement   → deepseek
         cloud × review      → gpt-4o-mini
         …

→ ccr-Router → llm-cascade (model="orchestrator-cloud")
→ Orchestrator delegiert an andere Areas
→ Failover in llm-cascade, kein Session-Neustart
```

#### EDUPRO supermodel=AUS

```
POOLS: cloud · free  (kein local)
AREAS: content "Lernmaterial, Prüfungen, Chat"
       dev     "Code-Analyse, PR-Review"
       utility "i18n, Audits, Verifier"
       general "Catch-All, provider-divers"

MODELLE: cloud × content → deepseek-chat
         cloud × dev     → deepseek-reasoner
         cloud × utility → gemini-flash-lite
         cloud × general → gemini-flash
         …

→ POST /api/generate mit purpose="..." → SemanticCategoryRouter
→ Router wählt Area anhand CategoryMeta.descriptions
→ KEIN Orchestrator-Area bei supermodel=AUS
```

#### EDUPRO supermodel=AN (zukünftig)

```
POOLS: cloud · free
AREAS: implement · review · research · dispatch · orchestrator ★
       (content/dev/utility/general verschwinden aus UI)

MODELLE: gleiche Struktur wie SWITCHER supermodel=AN
→ Orchestrator-Area isOrchestrator=true gepinnt
```

### SemanticCategoryRouter Pool-Scope

Neues `resolve(purpose, pool)` für Pool-gebundenes Semantic-Routing:

```java
// Nur Areas die im Pool "cloud" konfiguriert sind werden vorgeschlagen
String area = router.resolve("übersetze i18n-Keys ins Englische", "cloud");
// → "utility" (wenn cloud×utility konfiguriert ist und CategoryMeta description passt)
```

Fallback: wenn keine passende Area gefunden → Pool selbst als Catch-All.

### ccr-Router Integration (Switcher)

Der ccr-Router sendet jetzt alle Requests an llm-cascade statt direkt an Provider:

```json
{
  "Providers": [
    {
      "name": "llm-cascade",
      "api_base_url": "http://llm-cascade:8090/v1/chat/completions",
      "api_key": "sk-llm-cascade",
      "transformer": { "use": ["openrouter"] }
    }
  ],
  "Router": {
    "default": "llm-cascade,cloud",
    "background": "llm-cascade,cloud"
  }
}
```

Bei supermodel=AN: `"default": "llm-cascade,orchestrator-cloud"`.

**Vorteil:** Failover passiert transparent in llm-cascade ohne Session-Neustart.

---

## Lizenz

Open Source · 4dataclub.
