(ns battlebots.game.frame.player
  (:require [battlebots.arena.partial :refer [get-arena-area]]
            [battlebots.arena.occlusion :refer [occluded-arena]]
            [battlebots.constants.game :refer [command-map
                                               initial-time-unit-count
                                               partial-arena-radius]]
            [battlebots.game.utils :as gu]
            [battlebots.game.bot.decisions.move :refer [move]]
            [battlebots.game.bot.decisions.save-state :refer [save-state]]
            [battlebots.game.bot.decisions.shoot :refer [shoot]]))

(defn- randomize-players
  "Randomizes player ids"
  [players]
  (shuffle (map #(:_id %) players)))

(defn- sort-decisions
  "Sorts player decisions based of of a provided execution-order"
  [decisions execution-order]
  (map #(gu/get-player % decisions) execution-order))

(defn- process-command
  "Processes a single command for a given player if the player has enough time for that command."
  [player-id command-map]
  (fn [{:keys [game-state remaining-time]} command]
    (let [{:keys [cmd metadata]} command
          time-cost (or (get-in command-map [(keyword cmd) :tu]) 0)
          updated-time (- remaining-time time-cost)
          should-update? (>= updated-time 0)
          updated-game-state (if should-update?
                               (cond
                                (= cmd "MOVE")
                                (move player-id metadata game-state)

                                (= cmd "SHOOT")
                                (shoot player-id metadata game-state)

                                (= cmd "SET_STATE")
                                (save-state player-id metadata game-state)

                                :else game-state)
                               game-state)]
      {:game-state updated-game-state
       :remaining-time (if should-update?
                         updated-time
                         remaining-time)})))

(defn- apply-decisions
  "Applies player decision to the current state of the game"
  [command-map]
  (fn [game-state {:keys [decision _id] :as player}]
    (let [{:keys [commands]} decision]
      (:game-state (reduce (process-command _id command-map)
                           {:game-state game-state
                            :remaining-time initial-time-unit-count}
                           commands)))))

(defn- resolve-player-decisions
  "Returns a vecor of player decisions based off of the logic provided by
  their bots and an identical clean version of the arena"
  [players clean-arena]
  (map (fn [{:keys [_id bot saved-state energy] :as player}]
         (let [partial-arena (get-arena-area
                              clean-arena
                              (gu/get-player-coords _id clean-arena)
                              partial-arena-radius)]
           {:decision ((load-string bot)
                       {:arena (occluded-arena
                                partial-arena
                                (gu/get-player-coords _id partial-arena))
                        :saved-state saved-state
                        :bot-id _id
                        :energy energy
                        :spawn-bot? false})
            :_id _id})) players))

(defn resolve-player-turns
  "Updates the arena by applying each players' movement logic"
  [{:keys [players clean-arena] :as game-state}]
  (let [execution-order (randomize-players players)
        player-decisions (resolve-player-decisions players clean-arena)
        sorted-player-decisions (sort-decisions player-decisions execution-order)
        updated-game-state (reduce (apply-decisions command-map)
                                   game-state
                                   sorted-player-decisions)]
    updated-game-state))