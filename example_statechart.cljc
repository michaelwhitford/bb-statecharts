(ns example-statechart
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.pprint :refer [pprint] :as pp]
   [clojure.walk :as walk]
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
   [taoensso.timbre :as log]
   #?@(:clj [[dorothy.core :as dot]
             [dorothy.jvm :refer [render save! show!]]])))

#?(:clj (set! *warn-on-reflection* true))

(log/merge-config! {:min-level :warn})

(def cli-spec
  {:debug  {:coerce :boolean
            :alias :d
            :desc "enable debug mode"}})

(def cli-opts {:spec  cli-spec})
               ;:args->opts [:arguments]

(def app
  (statechart {:name "application"}
              (state {:id       :app
                      :initial :app/start}
                     (state {:id :app/start}
                            (on-entry {:id :app.start/assign}
                                      ; assign into local memory return a vector of operations here
                                      (script-fn [{:keys [opts] :as env} data]
                                                 [(ops/assign :debug (:debug opts))]))
                            (on-exit {:id :app.start/tap}
                                     ; tap after assignment
                                     (script-fn [env data]
                                                (tap> {:from :app/start :env env :data data})))
                            (transition {:target :app/middle
                                         :type :internal}))
                     (state {:id :app/middle}
                            (on-entry {:id :app.middle/assign}
                                      ; assign into local memory return a vector of operations here
                                      (script-fn [{:keys [args] :as env} data]
                                                 [(ops/assign :arguments args)]))
                            (on-exit {:id :app.middle/tap}
                                     ; tap after assignment
                                     (script-fn [env data]
                                                (tap> {:from :app/middle :env env :data data})))
                            (transition {:target :app/end
                                         :type :internal}))
                     (final {:id :app/end}
                            (on-entry {:id :app.end/print}
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
      (when problems
        (println "statechart problems:")
        (pprint problems))
      (def p (p/open))
      (add-tap #'p/submit)
      (p/clear)
      (println "portal enabled")
      (log/merge-config! {:min-level :debug})
      (tap> {:from :main :opts opts :env env :problems problems}))
    (simple/register! env ::app app)
    (let [running? (loop/run-event-loop! env 100)
          processor (env ::sc/processor)
          start (sp/start! processor env ::app {::sc/session-id session-id})]
      (tap> {:from ::app :app app})
      (Thread/sleep 1000)
      #_(reset! running? false)
      #_(System/exit 0))))

#?(:bb (when (= *file* (System/getProperty "babashka.file")
                ; guard against repl
                (apply -main *command-line-args*)))
   :clj (apply -main *command-line-args*))

#?(:clj (defn depth-first-search
          "Perform a depth-first search on a map as a tree, starting at the root node."
          [{::sc/keys [elements-by-id] :as tree}]
          (letfn [(dfs [node]
                    (when node
                      (println (:id node))
                      (doseq [child (:children node)]
                        (dfs (elements-by-id child)))))]
            (dfs tree))))

#?(:clj (defn dfs2
          "Perform a depth-first search on a map as a tree, starting at the root node."
          [{::sc/keys [elements-by-id] :as tree}]
          (loop [nodes [tree]]
            (tap> {:from ::dfs2 :nodes nodes})
            (when-let [node (first nodes)]
              (println (:id node))
              (recur (into (rest nodes) (map elements-by-id (:children node))))))))

(comment
  (depth-first-search app)
  (dfs2 app)
  ())

#?(:clj (defn extract-transitions
          [{::sc/keys [elements-by-id] :as stc}]
          (let [result (atom [])]
            (letfn [(dfs [node]
                      (let [node-type (:node-type node)]
                        (tap> {:from ::extract-transitions :stc stc :node node})
                        (cond
                          (= node-type :statechart) (doseq [child (:children node)]
                                                      (tap> {:from ::statechart-child :child child})
                                                      (dfs (elements-by-id child)))
                          (= node-type :state) (doseq [child (:children node)]
                                                 (tap> {:from ::child :child child})
                                                 (dfs (elements-by-id child)))
                          (= node-type :transition) (swap! result conj [(:parent node) (:target node)]))))]
              (dfs stc))
            @result)))

(comment
  (extract-transitions app))

#?(:clj (defn get-all-children
          "get all children from a fulcro statechart"
          [{::sc/keys [elements-by-id] :as stc}]
          (or elements-by-id {})))

#?(:clj (defn stateorfinal?
          "return boolean true for :state :and :final node-types"
          [[idx {:keys [node-type] :as m}]]
          #_(tap> {:from ::stateorfinal? :m m})
          (or (= :state node-type)
              (= :final node-type))))

#?(:clj (defn transition?
          "return boolean true for :transition node-types"
          [[idx {:keys [node-type] :as m}]]
          #_(tap> {:from ::transition? :m m})
          (= :transition node-type)))

#?(:clj (defn makesubgraph
          "create a subgraph for a state"
          [{:keys [id parent children target] :as state}]
          (let [id (or id "unnamed")
                n (if (keyword? id)
                    (subs (str id) 1)
                    (str id))
                kw (keyword (str "cluster_" n))]
            (tap> {:from ::makesubgraph :id id :state state :n n :kw kw})
            (dot/subgraph kw (vec (remove nil? (concat [(dot/node-attrs {:style :rounded :shape :box})
                                                        n
                                                        #_(dot/edge-attrs {:style :solid :color :darkgray})
                                                        (when target (dot/edge-attrs {:style :solid :color :blue}))]
                                                       (mapv #(vec [n %]) (or target children))
                                                       #_(extract-transitions app))))))))

#?(:clj (defn makegraph
          "create a graph for a statechart"
          [{:keys    [children id name node-type]
            ::sc/keys [elements-by-id ids-in-document-order id-ordinals]
            :as      s}]
          (let [scname (clojure.core/name (or name "Anonymous"))
                ords (sort-by second id-ordinals)
                elements (mapv (fn [[idx _]]
                                 (let [r (vec [idx (elements-by-id idx)])]
                                   #_(tap> {:from ::makegraph :r r})
                                   r)) ords)]
            (tap> {:from ::makegraph :ords ords :scname scname :elements elements :s s})
            (dot/digraph {:fontsize 20.0
                          ;:rankdir :LR    ; :TB :LR :BT :RL
                          :rankdir :TB
                          :compound true
                          ;:concentrate true
                          ;:newrank true
                          ;:pack true
                          :splines true
                          :ordering "out"
                          :overlap "scale"
                          :label scname
                          :id scname} #_(mapv (fn [[idx m]]
                                                #_(tap> {:from ::makegraph :idx idx :m m})
                                                (vec (flatten (extract-transitions app)))) elements)
                         (mapv (fn [[idx m]] (makesubgraph m)) elements-by-id)))))

(comment
  (pprint *file*)
  (v/problems app)
  (apply -main '("-d" "thing1" "thing2"))
  (-> (makegraph app)
      dot/dot
      (save! "out.png" {:format :png
                        :layout :dot}))
  (get-all-children app)
  (walk/postwalk-demo app)
  (seq app)
  (extract-transitions app)
  (tap> (makegraph app))
  (tap> app)
  (tap> (::sc/elements-by-id app))
  (tap> (filter stateorfinal? (::sc/elements-by-id app))))
