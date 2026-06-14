(ns kotoba-code.tools
  "The coding-agent tool surface, shaped for langchain.tool / create-react-agent.
  Pure + portable (.cljc): all real I/O is injected via a `host` capability map,
  so the same tools run on the JVM, in the browser, or against a mock in tests.

  host = {:read-file   (fn [path] -> string)
          :write-file  (fn [path content] -> string)   ; records touched paths for rollback
          :run-clojure (fn [forms] -> string)            ; stdout+stderr of `clojure -M -e <forms>`
          :run-tests   (fn [] -> string)                 ; stdout+stderr of the test runner
          :list-dir    (fn [path] -> string)             ; names under a project dir
          :search      (fn [pattern] -> string)}         ; regex grep across the project")

(defn coding-tools
  "The four ReAct coding tools (read/write/run-clojure/run-tests) bound to `host`.
  Returns a vector of langchain tool maps {:name :description :schema :fn}."
  [host]
  [{:name "read_file"
    :description "Read a source file from the project (or an allowed read root)."
    :schema {:type "object"
             :properties {:path {:type "string" :description "project-relative or absolute path"}}
             :required ["path"]}
    :fn (fn [{:keys [path]}] ((:read-file host) path))}

   {:name "write_file"
    :description "Overwrite a file with the COMPLETE new content (always send the whole file)."
    :schema {:type "object"
             :properties {:path    {:type "string"}
                          :content {:type "string"}}
             :required ["path" "content"]}
    :fn (fn [{:keys [path content]}] ((:write-file host) path content))}

   {:name "run_clojure"
    :description "Evaluate Clojure forms in the project via `clojure -M -e <forms>`; returns stdout+stderr."
    :schema {:type "object"
             :properties {:forms {:type "string"}}
             :required ["forms"]}
    :fn (fn [{:keys [forms]}] ((:run-clojure host) forms))}

   {:name "run_tests"
    :description "Run the project test suite; returns the runner output (look for '0 failures, 0 errors')."
    :schema {:type "object" :properties {} :required []}
    :fn (fn [_] ((:run-tests host)))}

   {:name "list_dir"
    :description "List the entries under a project-relative directory (defaults to the project root)."
    :schema {:type "object"
             :properties {:path {:type "string"}}
             :required []}
    :fn (fn [{:keys [path]}] ((:list-dir host) (or path ".")))}

   {:name "search"
    :description "Regex-search the project's source files; returns matching path:line snippets (capped)."
    :schema {:type "object"
             :properties {:pattern {:type "string"}}
             :required ["pattern"]}
    :fn (fn [{:keys [pattern]}] ((:search host) pattern))}])
