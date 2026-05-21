# llm-cascade — Offene Todos

> **Source-of-Truth:** `~/obsidian-brain/shared/04 Ressourcen/KI & Automatisierung/Claude Memory/project_next_session.md`
>
> Diese Datei ist ein **Spiegel** der Brain-Tasks im Repo. Bei Konflikt: Brain gewinnt.
>
> **Pattern:** Jedes 4dataclub-Repo bekommt eine `TODOS.md`.

Stand: 2026-05-16

---

## 🟢 Phase N — Ollama als Local-LLM-Provider (Single-Host)

> Plan-File: `~/.claude/plans/ich-werde-dir-fragen-lucky-pearl.md` (freigegeben 2026-05-16).
> User-Diktat: 80% Routine lokal, 20% Hard-Tasks an Cloud.
> llm-cascade ist das **Hauptarbeitsfeld** für Phase N.

| # | Task | Datei(en) | Aufwand |
|---|---|---|---|
| N.2 | Ollama-Container im `docker-compose.yml` mit `profiles: ["local-llm"]` (opt-in via `--profile local-llm`). Volume `ollama_data` für Modell-Persistenz. macOS-Hinweis (Metal automatisch) + Linux-NVIDIA-Hinweis als Kommentar. | `docker-compose.yml` | 1h |
| N.3 | `@Bean("ollama")` in `LlmProviderConfig.java` — verwendet bestehenden `OpenAiCompatProvider` mit `${ollama.base-url:http://ollama:11434/v1}`. **Kein neuer Provider-Code nötig.** | `src/main/java/com/dataclub/llmcascade/config/LlmProviderConfig.java` | 30min |
| N.3 | `application.properties`: Default `ollama.base-url=http://ollama:11434/v1` | `src/main/resources/application.properties` | inkl. oben |
| N.4 | DataInitializer-Seed: `provider=ollama, modelId=gemma3:4b, apiKeySettingKey=ollamaBaseUrl, enabled=false, cooldown503OverrideSec=null` | `src/main/java/com/dataclub/llmcascade/DataInitializer.java` (oder analog) | 30min |
| N.4 | Auto-Pull bei `enabled=true`: `docker exec llm-cascade-ollama-1 ollama pull <modelId>` triggern wenn Modell noch nicht da | DataInitializer / Service-Hook | inkl. oben |

**Phase N+1 (später, Multi-Host):**
- `AiModelConfig.providerBaseUrl`-Spalte ergänzen (DB-Migration via `ddl-auto=update`)
- `OpenAiCompatProvider` so refactorn dass `baseUrl` aus Config statt Bean-Default kommt
- (Kommentar in `LlmProviderConfig.java` Z.13–14: „echte per-Modell-baseUrl wäre Phase 5" → das ist Phase N+1)

## Verifikation

```bash
cd ~/Downloads/ki-projekte/llm-cascade
docker compose --profile local-llm up -d
docker exec llm-cascade-ollama-1 ollama pull gemma3:4b

curl http://localhost:8090/api/models                   # ollama-Provider muss auftauchen
curl -X POST http://localhost:8090/api/generate \
  -H 'Content-Type: application/json' \
  -d '{"provider":"ollama","modelId":"gemma3:4b","prompt":"Übersetze: Speichern → AZ"}'
```

---

## Versionierung

GHCR-Image: `ghcr.io/4dataclub/llm-cascade:0.2.0` aktuell.
Bei Phase N → Bump auf `0.3.0` (Ollama-Bean ist additive Erweiterung, kein Breaking-Change).
