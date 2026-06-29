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

## Inference backend status

`kotoba-code` itself does **not** call Ollama directly. The CLI currently chooses:

- OpenRouter by default, through `langchain.model/openai-model`
- Murakumo when the model id starts with `murakumo:`, through the local OpenAI-compatible gateway at `http://127.0.0.1:4000/v1/chat/completions`

Ollama can still sit behind an OpenAI-compatible gateway, but it is not a first-class
dependency here. In this workspace the direct `ollama` mention is in the yukkuri
asset scorer, not in `kotoba-code`.

`src/kotoba_code/inference.cljc` adds the CLJC boundary for local/web inference:

```clojure
(require '[kotoba-code.inference :as infer])

(def plan
  (infer/select-plan {:target :web
                      :model "cid:your-model"
                      :format :kotoba-fp8}))

(-> (infer/load-weights! "/models/tiny.kotoba.fp8")
    ;; CLJS returns a Promise. CLJ returns the map immediately.
    )

(def backend
  (infer/kotoba-wasm-backend
   {:infer (fn [{:keys [plan weights prompt]}]
             ;; call the loaded Kotoba WASM module here
             {:text (str "model=" (:model plan) " prompt=" prompt)})}))

(infer/infer! {:backend backend
               :plan plan
               :weights {:byte-length 123}
               :prompt "hello"})
```

The target profiles are intentionally runtime-neutral:

| Target | Inference preference | Training preference |
|---|---|---|
| Web | Kotoba WASM, ONNX Runtime WebGPU, ONNX Runtime WASM | ONNX Runtime Web training, remote Murakumo |
| macOS / Apple Silicon | Candle Metal, llama.cpp Metal, Kotoba WASM, Murakumo HTTP | MLX, PyTorch MPS, remote Murakumo |
| Windows + Intel Arc | ONNX Runtime DirectML, OpenVINO, llama.cpp SYCL/Vulkan, Kotoba WASM | OpenVINO/PyTorch XPU, remote Murakumo |
| Linux + CUDA | vLLM CUDA, llama.cpp CUDA, Candle CUDA, Murakumo HTTP, Kotoba WASM | PyTorch CUDA, Burn CUDA, remote Murakumo |

Kotoba WASM is AOT for the program: `kotoba-clj` compiles safe Clojure/EDN-subset
source into WASM component bytes ahead of execution. Model weights are not AOT;
they are runtime data loaded by CID, URL, file path, or browser `fetch`.

WebGPU/wgpu can optimize per GPU family, but the practical split is:

- browser: use WebGPU through ONNX Runtime Web or a Kotoba WASM host that delegates compute to WebGPU
- native Rust: use `wgpu` for portable kernels, or vendor stacks when they are clearly faster
- CUDA production: prefer vLLM/llama.cpp/Candle CUDA behind Murakumo
- Intel Arc production: prefer DirectML/OpenVINO/SYCL or Vulkan before assuming browser WebGPU is enough

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
