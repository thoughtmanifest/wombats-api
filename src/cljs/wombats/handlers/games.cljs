(ns wombats.handlers.games
    (:require [re-frame.core :as re-frame]
              [wombats.db :as db]
              [wombats.utils.collection :refer [update-or-insert]]
              [wombats.services.wombats :refer [get-games
                                                      post-game
                                                      del-game
                                                      post-game-user
                                                      post-game-initialize
                                                      post-game-start]]))

(defn update-games
  "updates all games in state"
  [db [_ games]]
  (assoc db :games games))

(defn update-game
  "updates a single game in state"
  [db [_ game]]
  (assoc db :games (update-or-insert (:games db) game)))

(defn add-game
  "adds new game"
  [db [_ game]]
  (assoc db :games (conj (:games db) game)))

(defn set-active-game
  "sets active game"
  [db [_ game]]
  (assoc db :active-game game :active-frame nil))

(defn filter-game
  "filters a game out of state"
  [db [_ game-id]]
  (let [games (:games db)]
    (assoc db :games (remove #(= game-id (:_id %)) games))))

(defn play-game
  "plays the game"
  [db [_ game-id]]
  (let [{:keys [frames] :as game} (first (filter #(= game-id (:_id %)) (:games db)))
        {:keys [chsk-send!]} (:socket-connection db)]
    (chsk-send! [:game/play {:game-id game-id}]))
  db)

(defn display-frame
  "updates the displayed frame"
  [db [_ frame]]
  (assoc db :active-frame frame))

(defn create-game
  "creates a new game"
  [db _]
  (post-game
    #(re-frame/dispatch [:add-game %])
    #(re-frame/dispatch [:update-errors %]))
  db)

(defn remove-game
  "removes a selected game"
  [db [_ game-id]]
  (del-game game-id
    #(re-frame/dispatch [:filter-game game-id])
    #(re-frame/dispatch [:update-errors %]))
  db)

(defn fetch-games
  "fetch all games"
  [db _]
  (get-games
    #(re-frame/dispatch [:update-games %])
    #(re-frame/dispatch [:update-errors %]))
  db)

(defn register-user-in-game
  "registers a user in a game"
  [db [_ game-id user-id repo]]
  (re-frame/dispatch [:clear-modal])
  (post-game-user game-id user-id repo
    #(re-frame/dispatch [:update-game %])
    #(re-frame/dispatch [:update-errors %]))
  db)

(defn initialize-game
  "starts a game"
  [db [_ game-id]]
  (post-game-initialize game-id
    #(re-frame/dispatch [:update-game %])
    #(re-frame/dispatch [:update-errors %]))
  db)

(defn start-game
  "starts a game"
  [db [_ game-id]]
  (post-game-start game-id
    #(re-frame/dispatch [:update-game %])
    #(re-frame/dispatch [:update-errors %]))
  db)

(re-frame/register-handler :update-games update-games)
(re-frame/register-handler :update-game update-game)
(re-frame/register-handler :create-game create-game)
(re-frame/register-handler :add-game add-game)
(re-frame/register-handler :set-active-game set-active-game)
(re-frame/register-handler :remove-game remove-game)
(re-frame/register-handler :filter-game filter-game)
(re-frame/register-handler :fetch-games fetch-games)
(re-frame/register-handler :register-user-in-game register-user-in-game)
(re-frame/register-handler :initialize-game initialize-game)
(re-frame/register-handler :start-game start-game)
(re-frame/register-handler :play-game play-game)
(re-frame/register-handler :game/display-frame display-frame)
