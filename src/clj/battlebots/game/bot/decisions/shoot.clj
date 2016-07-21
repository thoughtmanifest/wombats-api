(ns battlebots.game.bot.decisions.shoot
  (:require [battlebots.constants.arena :as ac]
            [battlebots.arena.utils :as au]
            [battlebots.game.utils :as gu]
            [battlebots.game.messages :refer [log-shoot-event]]))

(defn- add-shot-metadata
  [uuid]
  (fn [cell]
    (assoc-in cell [:md (keyword uuid)] {:type :shot
                                         :decay 1})))

(defn- add-shot-damage
  "Add damage to cells that contain the energy prop"
  [damage]
  (fn [cell]
    (if (:energy cell)
      (assoc cell :energy (- (:energy cell) damage))
      cell)))

(defn- replace-destroyed-cell
  [shot-uuid]
  (fn [cell]
    (let [destructible? (ac/destructible? (:type cell) ac/shot-settings)
          destroyed? (if destructible?
                       (<= (:energy cell) 0)
                       false)
          updated-md (when destroyed?
                       (assoc (:md cell) (keyword shot-uuid) {:type :destroyed
                                                              :decay 1}))]
      (if destroyed?
        (assoc (:open ac/arena-key) :md updated-md)
        cell))))

(defn- resolve-shot-cell
  "Aggregation pipeline for resolving what should happen to a cell when a shot enters it's space"
  [cell-at-point damage shot-uuid]
  (reduce (fn [cell update-func]
            (update-func cell)) cell-at-point [(add-shot-damage damage)
                                               (add-shot-metadata shot-uuid)
                                               (replace-destroyed-cell shot-uuid)]))

(defn- shot-should-progress?
  "Returns a boolean indicating if a shot should continue down it's path"
  [should-progress? cell-at-point energy]
  (boolean (and should-progress?
               (> energy 0)
               (ac/can-occupy? (:type cell-at-point) ac/shot-settings))))

(defn- update-victim-energy
  "Updates a victim's energy when shoot"
  [cell damage]
  (fn [{:keys [players] :as game-state}]
    (if (gu/is-player? cell)
      (assoc game-state :players (gu/modify-player-stats
                                  (:_id cell)
                                  {:energy #(- % damage)}
                                  players))
      game-state)))

(defn- reward-shooter
  "Shooters get rewarded for hitting a cell with energy. How much depends on the cell type."
  [shooter-id cell damage]
  (fn [{:keys [players] :as game-state}]
    (let [hit-reward (get-in ac/shot-settings [:hit-reward (keyword (:type cell))] nil)
          update-function (when hit-reward
                            (hit-reward damage))
          updated-players (if update-function
                            (gu/modify-player-stats shooter-id {:energy update-function} players)
                            players)]
      (assoc game-state :players updated-players))))

(defn- update-arena
  [cell-at-point damage shot-uuid point]
  (fn [{:keys [dirty-arena] :as game-state}]
    (let [updated-cell (resolve-shot-cell cell-at-point damage shot-uuid)
          updated-dirty-arena (au/update-cell dirty-arena point updated-cell)]
      (assoc game-state :dirty-arena updated-dirty-arena))))

(defn- update-players
  [cell-at-point damage shooter-id]
  (fn [game-state]
    (reduce #(%2 %1) game-state [(reward-shooter shooter-id cell-at-point damage)
                                 (update-victim-energy cell-at-point damage)])))

(defn- process-shot
  "Process a cell that a shot passes through"
  [{:keys [game-state energy should-progress?
           shot-uuid shooter-id] :as shoot-state} point]
  (let [{:keys [dirty-arena players]} game-state
        cell-at-point (au/get-item point dirty-arena)]
    (if (shot-should-progress? should-progress? cell-at-point energy)
      (let [cell-energy (:energy cell-at-point)
            remaining-energy (Math/max 0 (- energy (or cell-energy 0)))
            damage (- energy remaining-energy)
            updated-game-state (reduce #(%2 %1) game-state [(update-arena cell-at-point damage shot-uuid point)
                                                            (update-players cell-at-point damage shooter-id)
                                                            (log-shoot-event cell-at-point damage shooter-id)])]
        {:game-state updated-game-state
         :energy remaining-energy
         :should-progress? true
         :shot-uuid shot-uuid
         :shooter-id shooter-id})
      (assoc shoot-state :should-progress? false))))

(defn shoot
  "Main shoot function"
  [player-id
   {:keys [direction energy] :as metadata}
   {:keys [dirty-arena players] :as game-state}]
  (let [player-coords (gu/get-player-coords player-id dirty-arena)
        shoot-coords (au/draw-line-from-point dirty-arena
                                              player-coords
                                              direction
                                              (:distance ac/shot-settings))
        players-update-shooter-energy (gu/modify-player-stats
                                       player-id
                                       {:energy #(- % energy)}
                                       players)]
    (:game-state (reduce
                  process-shot
                  {:game-state (assoc game-state
                                 :players players-update-shooter-energy)
                   :energy energy
                   :should-progress? true
                   :shot-uuid (au/uuid)
                   :shooter-id player-id} shoot-coords))))
