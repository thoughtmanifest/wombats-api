(ns wombats.controllers.socket
  (:require [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]
            [taoensso.timbre :as timbre :refer [debugf]]
            [wombats.services.mongodb :as db]))

(defn total-frames
  [{:keys [frames] :as segment}]
  (count frames))

(defn segment-over?
  [segment frame]
  (= (total-frames segment) frame))

(defn game-over?
  [segment next-segment frame]
  (and (segment-over? segment frame) (not next-segment)))

(defn get-uid
  [{:keys [access-token] :as params}]
  (let [user (db/get-player-by-auth-token access-token)]
    (str (:_id user))))

;;
;; Web Socket Server
;;

(let [packer :edn
      {:keys [ch-recv send-fn connected-uids
              ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket-server! (get-sch-adapter) {:packer packer
                                                            :user-id-fn (fn [{:keys [params] :as request}]
                                                                          (get-uid params))
                                                            :handshake-data-fn (fn [request] {})})]

  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

;; Sente event handlers
(defmulti -event-msg-handler :id) ; Dispatch on event-id

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg) ; Handle event-msgs on a single thread
  ;; (future (-event-msg-handler ev-msg)) ; Handle event-msgs on a thread pool
  )

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (debugf "Unhandled event: %s" event)
    (when ?reply-fn
      (?reply-fn {:umatched-event-as-echoed-from-from-server event}))))

(defmethod -event-msg-handler :game/play
  [{:keys [client-id event uid] :as ev-msg}]
  (let [[_ {:keys [game-id] :as params}] event
        total-segments (db/get-game-segment-count game-id)
        first-segment (db/get-game-segment game-id 0)
        second-segment (db/get-game-segment game-id 1)]

    (future
      (loop [current-segment first-segment
             next-segement second-segment
             current-frame 0]
        (cond
         ;; When the game is over break out of the loop
         (game-over? current-segment next-segement current-frame)
         (do)

         ;; When a segment of frames has been passed to the client, move
         ;; to the next segment and fetch the next segment
         (segment-over? current-segment current-frame)
         (do
           (recur
            next-segement
            @(future (db/get-game-segment game-id (inc (:segment next-segement))))
            0))

         ;; Pass the next frame and sleep the thread in between each
         :else
         (do
           (Thread/sleep 400) ;; TODO Once client side rendering has improved, adjust this value
           (chsk-send! uid [:game/display-frame (nth (:frames current-segment) current-frame)])
           (recur current-segment next-segement (inc current-frame))))))))

(defonce router_ (atom nil))
(defn  stop-router! [] (when-let [stop-fn @router_] (stop-fn)))
(defn start-router! []
  (stop-router!)
  (reset! router_
    (sente/start-server-chsk-router!
      ch-chsk event-msg-handler)))
