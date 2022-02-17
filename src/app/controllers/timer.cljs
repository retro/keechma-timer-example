(ns app.controllers.timer
  (:require [keechma.next.controller :as ctrl]
            [keechma.next.controllers.router :refer [redirect!]]
            [keechma.next.controllers.pipelines :as pipelines]
            [keechma.next.toolbox.protocols :refer [IAbortable]]
            [keechma.pipelines.core :as pp :refer-macros [pipeline!]]
            [promesa.core :as p]))

(derive :timer ::pipelines/controller)

#_{:keechma.on/start (pipeline! [_ ctrl]
                       (let [every-token (timer/every
                                          timer/main-thread
                                          1000
                                          (fn []
                                            (dispatch-self ctrl :tick-timer)))
                             in-token (timer/in
                                       timer/main-thread
                                       (* 300 1000)
                                       (fn [] (dispatch-self ctrl :end-timer)))]
                         (pipeline! [_ {:keys [state*]}]
                           (pp/swap! state* assoc :in-token in-token :every-token every-token :time-left 300))))
   :tick-timer (pipeline! [_ {:keys [state*]}]
                 (let [time-left (:time-left @state*)]
                   (pipeline! [_ {:keys [state*] :as ctrl}]
                     (if (> time-left 0)
                       (pp/swap! state* assoc :time-left (dec (:time-left @state*)))
                       (dispatch-self ctrl :end-timer)))))
   :end-timer (pipeline! [_ {:keys [state*] :as ctrl}]
                (let [in-token (:in-token @state*)
                      every-token (:every-token @state*)]
                  (timer/cancel timer/main-thread in-token)
                  (timer/cancel timer/main-thread every-token))
                (pp/swap! state* assoc :time-left :init)
                (redirect! ctrl :router {:page "drops"}))}

(defn start-timer! [{:keys [state*] :as ctrl} timeout-msec]
  (let [start-timestamp (js/Date.now)
        end-timestamp (+ start-timestamp timeout-msec)
        timeout-id (-> state* deref :timeout-id)]
    (js/clearTimeout timeout-id)
    (swap! state* assoc :time-left timeout-msec)
    (p/create
     (fn [resolve _]
       (let [updater (fn updater []
                       (let [time-left (max 0 (- end-timestamp (js/Date.now)))]
                         (if (zero? time-left)
                           (resolve)
                           (let [new-state {:time-left time-left
                                            :timeout-id (js/setTimeout updater 100)}]
                             (ctrl/transact ctrl #(swap! state* merge new-state))))))]
         (updater))))))

(defn stop-timer! [{:keys [state*]}]
  (js/clearTimeout (-> state* deref :timeout-id)))

(def pipelines
  {:keechma.on/start (pipeline! [value ctrl]
                       (start-timer! ctrl 10000)
                       (redirect! ctrl :router {:page "drops"}))
   :keechma.on/stop (pipeline! [value ctrl]
                      (stop-timer! ctrl))})

(defmethod ctrl/prep :timer [ctrl]
  (pipelines/register ctrl pipelines))

(defmethod ctrl/derive-state :timer [_ state]
  (:time-left state))