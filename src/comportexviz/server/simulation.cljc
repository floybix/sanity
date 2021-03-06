(ns comportexviz.server.simulation
  (:require #?(:clj [clojure.core.async :as async :refer [put! <! go go-loop]]
               :cljs [cljs.core.async :as async :refer [put! <!]])
            [org.nfrac.comportex.core :as core]
            [org.nfrac.comportex.protocols :as p]
            [org.nfrac.comportex.util :as util])
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]])))

#?(:clj
   (defn random-uuid []
     (java.util.UUID/randomUUID)))

(defn should-go?! [options]
  (let [{:keys [go? force-n-steps step-ms]} @options]
    (cond
      go? step-ms
      (pos? force-n-steps) (do
                             (swap! options update :force-n-steps dec)
                             0)
      :else false)))

(defn simulation-loop [model world out options sim-closed? htm-step]
  (go
    (if (loop []
          (when (not @sim-closed?)
            (if-let [t (should-go?! options)]
              (when-let [in-value (<! world)]
                (->> (swap! model htm-step in-value)
                     (put! out))
                (<! (async/timeout t))
                (recur))
              true)))
      (add-watch options :run-sim
                 (fn [_ _ _ _]
                   (remove-watch options :run-sim)
                   (simulation-loop model world out options sim-closed? htm-step)))
      (reset! sim-closed? true))))

(defn handle-commands [commands model options sim-closed?]
  (let [status (atom (:go? @options))
        status-subscribers (atom #{})
        client-infos (atom {})
        all-client-infos (atom #{})]
    (add-watch options ::extract-status-change
               (fn [_ _ oldv newv]
                 (let [{:keys [go?]} newv]
                   (when (not= go? (:go? oldv))
                     (reset! status go?)))))
    (add-watch status ::push-to-subscribers
               (fn [_ _ _ v]
                 (doseq [ch @status-subscribers]
                   (put! ch [v]))))
    (go-loop []
      (if-not @sim-closed?
        (if-let [c (<! commands)]
          (let [[[command & xs] client-id] c
                client-info (or (get @client-infos client-id)
                                (let [v (atom {})]
                                  (swap! client-infos assoc client-id v)
                                  v))]
            (case command
              :client-disconnect (do
                                   (println "SIMULATION: Client disconnected.")
                                   (swap! status-subscribers disj
                                          (::status-subscriber @client-info)))
              :connect (let [[old-client-info subscriber-c] xs]
                         (add-watch client-info ::push-to-client
                                    (fn [_ _ _ v]
                                      (put! subscriber-c v)))
                         (when-let [subscriber-c (::status-subscriber
                                                  old-client-info)]
                           (println "SIMULATION: Client resubscribed"
                                    "to status.")
                           (swap! status-subscribers conj subscriber-c)
                           (swap! client-info assoc ::status-subscriber
                                  subscriber-c)))
              :step (swap! options update :force-n-steps inc)
              :set-spec (let [[path v] xs]
                          (swap! model assoc-in path v))
              :restart (let [[response-c] xs]
                         (swap! model p/restart)
                         (put! response-c :done))
              :toggle (->> (swap! options update :go? not)
                           (println "SIMULATION TOGGLE. Current timestep:"
                                    (p/timestep @model)))
              :pause (do
                       (println "SIMULATION PAUSE. Current timestep:"
                                (p/timestep @model))
                       (swap! options assoc :go? false))
              :run (do
                     (println "SIMULATION RUN. Current timestep:"
                              (p/timestep @model))
                     (swap! options assoc :go? true))
              :set-step-ms (let [[t] xs]
                             (swap! options assoc :step-ms t))
              :subscribe-to-status (let [[subscriber-c] xs]
                                     (println (str "SIMULATION: Client "
                                                   "subscribed to status."))
                                     (swap! status-subscribers conj
                                            subscriber-c)
                                     (swap! client-info assoc
                                            ::status-subscriber subscriber-c)
                                     (put! subscriber-c [@status])))
            (recur))
          (reset! sim-closed? true))))))

;; To end the simulation, close `world-c` and/or `commands-c`. If only one is
;; closed, the simulation may consume another value from the other before
;; closing.
(defn start
  [steps-c model-atom world-c commands-c htm-step]
  (let [options (atom {:go? false
                       :step-ms 20
                       :force-n-steps 0})
        sim-closed? (atom false)]
    (when commands-c
      (handle-commands commands-c model-atom options sim-closed?))
    (simulation-loop model-atom world-c steps-c options sim-closed? htm-step))
  nil)
