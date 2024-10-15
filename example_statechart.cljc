(ns example-statechart
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.pprint :refer [pprint] :as pp]
   [portal.api :as p]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :refer [statechart]]
   [com.fulcrologic.statecharts.algorithms.v20150901-validation :as v]
   [com.fulcrologic.statecharts.elements :refer [state transition assign log parallel on-entry on-exit data-model script-fn Send final]]
   [com.fulcrologic.statecharts.environment :refer [assign!] :as env]
   [com.fulcrologic.statecharts.events :refer [new-event]]
   [com.fulcrologic.statecharts.event-queue.core-async-event-loop :as loop]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.working-memory-store.local-memory-store :as lms]
   [com.fulcrologic.statecharts.convenience :refer [on]]
   [com.fulcrologic.statecharts.simple :as simple]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [taoensso.timbre :as log]))

#?(:clj (set! *warn-on-reflection* true))

(log/set-min-level! :warn)

(def cli-spec
  {:debug  {:coerce :boolean
            :alias :d
            :desc "enable debug mode"}})

(def cli-opts {:spec  cli-spec})
               ;:args->opts [:arguments]

(def app
  (statechart {}
              (state {:id       :app
                      :initial  :app/start}
                     (state {:id :app/start}
                            (on-entry {}
                                      ; assign into local memory return a vector of operations here
                                      (script-fn [{:keys [opts] :as env} data]
                                                 [(ops/assign :debug (:debug opts))]))
                            (on-exit {}
                                     ; tap after assignment
                                     (script-fn [env data]
                                                (tap> {:from :app/start :env env :data data})))
                            (transition {:target :app/middle}))
                     (state {:id :app/middle}
                            (on-entry {}
                                      ; assign into local memory return a vector of operations here
                                      (script-fn [{:keys [args] :as env} data]
                                                 [(ops/assign :arguments args)]))
                            (on-exit {}
                                     ; tap after assignment
                                     (script-fn [env data]
                                                (tap> {:from :app/middle :env env :data data})))
                            (transition {:target :app/end}))
                     (final {:id :app/end}
                            (on-entry {}
                                      ; print output here
                                      (script-fn [{:keys [opts] :as env} {:keys [arguments] :as data}]
                                                 (tap> {:from :app/end :env env :data data})
                                                 (println "arguments:")
                                                 (println "")
                                                 (pprint arguments)
                                                 (println "")))))))

(defn -main [& args]
  (let [opts (cli/parse-args args cli-opts)
        session-id 1
        env (simple/simple-env opts)
        problems (v/problems app)]
    ; do debug stuff
    (when (get-in opts [:opts :debug] false)
      #_(println "debug from main started")
      (when (seq problems)
        (println "statechart problems:")
        (pprint problems))
      (def p (p/open))
      (add-tap #'p/submit)
      (p/clear)
      (println "portal enabled")
      (log/set-min-level! :debug)
      (tap> {:from :main :opts opts :env env :problems problems}))
    (simple/register! env ::app app)
    (let [running? (loop/run-event-loop! env 100)
          processor (env ::sc/processor)
          start (sp/start! processor env ::app {::sc/session-id session-id})]
      (Thread/sleep 1000)
      (reset! running? false)
      #_(System/exit 0))))

#?(:bb (when (= *file* (System/getProperty "babashka.file")
                ; guard against repl
                (apply -main *command-line-args*)))
   :clj (apply -main *command-line-args*))

(comment
  (pprint *file*)
  (v/problems app)
  (apply -main '("thing1" "thing2" "-d")))
