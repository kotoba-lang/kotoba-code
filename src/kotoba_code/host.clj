(ns kotoba-code.host
  "JVM host capabilities — the real I/O behind the injected tool/model seams.
  Filesystem access is sandboxed to a project root (+ optional read roots); writes
  are recorded so a failed gate can roll the working tree back via git."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.data.json :as json])
  (:import [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.net URI]
           [java.io File]))

;; ── HTTP + JSON host-caps (for ChatModel + kotoba-db) ───────────────────────

(def ^:private client (delay (HttpClient/newHttpClient)))

(defn http-fn
  "host :http-fn — a real HTTP client over java.net.http."
  [{:keys [url method headers body]}]
  (let [b (HttpRequest/newBuilder (URI/create url))]
    (doseq [[k v] headers] (.header b (name k) (str v)))
    (.method b (str/upper-case (name (or method :post)))
             (HttpRequest$BodyPublishers/ofString (or body "")))
    (let [resp (.send @client (.build b) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp) :body (.body resp)})))

(def json-caps
  {:json-write json/write-str
   :json-read  (fn [s] (json/read-str s :key-fn keyword))})

(def http+json (assoc json-caps :http-fn http-fn))

;; ── sandboxed filesystem ────────────────────────────────────────────────────

(defn- canon ^File [f] (.getCanonicalFile (io/file f)))

(defn- resolve-in
  "Canonical File for `path` if it falls within any of `roots`, else nil."
  [roots path]
  (let [f (io/file path)
        roots* (map canon roots)
        cand (if (.isAbsolute f) (canon f) (canon (io/file (first roots*) path)))
        cp   (.getPath cand)]
    (when (some (fn [r] (let [rp (.getPath r)]
                          (or (= cp rp) (str/starts-with? cp (str rp File/separator)))))
                roots*)
      cand)))

(defn fs-host
  "I/O host bound to `root` (the project the agent edits).
   :read-roots — extra directories the agent may READ (e.g. sibling libs).
   write-file is confined to `root` and records touched paths for rollback."
  [root & {:keys [read-roots]}]
  (let [root*    (canon root)
        read-set (into [root*] (map canon read-roots))
        touched  (atom #{})]
    {:read-file
     (fn [path]
       (if-let [f (resolve-in read-set path)]
         (if (.isFile f) (slurp f) (str "NOT A FILE: " path))
         (str "DENIED (outside read roots): " path)))

     :write-file
     (fn [path content]
       (if-let [f (resolve-in [root*] path)]
         (do (io/make-parents f)
             (spit f content)
             (swap! touched conj (str (.relativize (.toPath root*) (.toPath f))))
             (str "written " path " (" (count content) " bytes)"))
         (str "DENIED (writes confined to project root): " path)))

     :run-clojure
     (fn [forms]
       (let [{:keys [out err]} (sh/sh "clojure" "-M" "-e" forms :dir (.getPath root*))]
         (subs (str out err) 0 (min 6000 (count (str out err))))))

     :run-tests
     (fn []
       ;; KC_TEST_CMD env override — run an arbitrary test command in the project root
       ;; (e.g. a babashka sweep for bb-based projects). Default: clojure -X:test.
       (let [cmd (System/getenv "KC_TEST_CMD")
             {:keys [out err]} (if (and cmd (not (str/blank? cmd)))
                                 (sh/sh "bash" "-lc" cmd :dir (.getPath root*))
                                 (sh/sh "clojure" "-X:test" :dir (.getPath root*)))]
         (str out err)))

     :list-dir
     (fn [path]
       (if-let [d (resolve-in read-set path)]
         (if (.isDirectory d)
           (->> (.listFiles d) (map #(str (.getName ^File %) (when (.isDirectory ^File %) "/")))
                sort (str/join "\n"))
           (str "NOT A DIRECTORY: " path))
         (str "DENIED (outside read roots): " path)))

     :search
     (fn [pattern]
       (let [re (re-pattern pattern)
             src? #(re-find #"\.(clj|cljc|cljs|edn)$" (.getName ^File %))
             hits (for [^File f (file-seq root*)
                        :when (and (.isFile f) (src? f))
                        :let  [rel (str (.relativize (.toPath root*) (.toPath f)))]
                        [i line] (map-indexed vector (str/split-lines (slurp f)))
                        :when (re-find re line)]
                    (str rel ":" (inc i) ": " (str/trim line)))]
         (if (seq hits)
           (str/join "\n" (take 100 hits))
           "(no matches)")))

     :rollback
     (fn []
       (let [t @touched]
         (when (seq t)
           (apply sh/sh "git" "-C" (.getPath root*) "checkout" "--" (vec t)))
         t))

     :touched touched}))
