# kotoba-code

A **model-neutral, test-gated, kotoba-Datom-backed** agentic coding agent — in Clojure.

Drive *any* OpenAI-compatible model (kimi via OpenRouter, or the Murakumo gateway)
through a ReAct loop with `read_file` / `write_file` / `run_clojure` / `run_tests`
tools, and persist every session's turn-history **as-of on the kotoba Datom log** so
runs are resumable, auditable, and time-travelable. The coding agent becomes an
organism on the same substrate as the UNSPSC actors — same checkpointer, same node.

This is the native Clojure successor to the throwaway Python bake-off harness
(`_modelbake/refactor_runner.py`), built entirely on the kotoba-clj stack.

## Three pillars → one project

| Pillar | What it gives | Reused from |
|---|---|---|
| **kotoba-clj** | `openai-model` (tool-calling) → OpenRouter/Murakumo; messages; tool exec; `kotoba-db` | `langchain-clj` |
| **langgraph-clj** | `create-react-agent` loop + `datomic-checkpointer` | `langgraph-clj` |
| **kotoba-datomic** | session state persisted on the kotoba Datom log (resume / as-of / fleet-shared) | kotoba-server XRPC |

kotoba-code itself only adds: the four **tools** (`tools.cljc`, I/O injected via a
sandboxed host), the **agent** wiring (`agent.cljc`), and the **gate + rollback**
(`gate.cljc`, never leave a broken tree). `host.clj` is the JVM I/O (HTTP, shell, fs,
git rollback); `main.clj` is the CLI.

## House rules baked into the system prompt (lessons from the bake-off)

- langgraph-clj graph builders are **immutable** — thread with `->`, never `doto`.
- runtime model inference stays **Murakumo-only** (ADR-2605215000).
- kotoba persistence is `datomic-checkpointer` with `:db-api (kdb/kotoba-api …)`, never the in-process demo store.
- stop the moment tests are green; no speculative edits after green.

## Use

```bash
# tests (mock model + mock host — no network)
clojure -X:test

# drive a real task (OpenRouter / kimi)
export OR_KEY=sk-or-...
clojure -M:run "make the failing test pass" /path/to/project moonshotai/kimi-k2.7-code

# or the Murakumo gateway (no key)
clojure -M:run "…" /path/to/project murakumo:gemma3:4b

# persist the session on a kotoba Datom node (resumable)
export KOTOBA_URL=http://127.0.0.1:8077 KOTOBA_GRAPH=<cid> KOTOBA_TOKEN=<jwt> KC_SESSION=my-task
clojure -M:run "…" /path/to/project
```

Apache-2.0.
