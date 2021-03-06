(ns dojo.jobs.match-email
  (:require
    [clojure.string :as string]
    [clojure.set :as set]
    [chime.core :as chime]
    [pairing-scheduler.core :as ps]
    [dojo.email :as email]
    [dojo.db :as db])
  (:import
    (java.time Period DayOfWeek ZonedDateTime ZoneId LocalTime)
    (java.time.format DateTimeFormatter)
    (java.time.temporal TemporalAdjusters)))

(def ->java-day-of-week
  {:monday DayOfWeek/MONDAY
   :tuesday DayOfWeek/TUESDAY
   :wednesday DayOfWeek/WEDNESDAY
   :thursday DayOfWeek/THURSDAY
   :friday DayOfWeek/FRIDAY
   :saturday DayOfWeek/SATURDAY
   :sunday DayOfWeek/SUNDAY})

(defn generate-schedule
  "Returns a list of maps, with :guest-ids, :day-of-week and :Time-of-day,
    ex.
    [{:guest-ids #{123 456}
      :day-of-week :monday
      :time-of-day 1200} ...]"
  [users]
  (if (empty? users)
   []
   (->> {:max-events-per-day (->> users
                                  (map (fn [user]
                                        [(:user/id user) 1]))
                                  (into {}))
         :availabilities (->> users
                              (map (fn [user]
                                    [(:user/id user)
                                     ;; stored as {[:monday 10] :available
                                     ;;            [:tuesday 10] :preferred
                                     ;;            [:wednesday 10] nil)
                                     ;; but need #{[:monday 10 :available]
                                     ;;            [:tuesday 10 :preferred]}
                                     ;; also, remove when value is nil
                                     (->> (:user/availability user)
                                          (filter (fn [[k v]] v))
                                          (map (fn [[k v]]
                                                 (conj k v)))
                                          set)]))
                              (into {}))}
        (ps/generate-initial-schedule 1)
        ps/optimize-schedule
        :schedule)))

(defn group-by-guests
  [schedule]
  (reduce (fn [memo event]
           (-> memo
               (update (first (:guest-ids event)) (fnil conj #{}) event)
               (update (last (:guest-ids event)) (fnil conj #{}) event)))
     {} schedule))


(defn unmatched-email-template
  [user-id]
  (let [user (db/get-user user-id)]
    {:to (:user/email user)
     :subject "ClojoDojo - Your Matches for this Week"
     :body [:div
            [:p "Hi " (:user/name user) ","]
            [:p "Unfortunately, we couldn't match you with anyone this week. :("]
            [:p "- DojoBot"]]}))

(defn matched-email-template
  [user-id events]
  (let [get-user (memoize db/get-user)
        user (db/get-user user-id)
        events (->> events
                    (map (fn [event]
                           (assoc event :date
                             (.adjustInto
                               (LocalTime/of (:time-of-day event) 0)
                               (.with (ZonedDateTime/now (ZoneId/of "America/Toronto"))
                                 (TemporalAdjusters/next (->java-day-of-week (:day-of-week event)))))))))]
   {:to (:user/email user)
    :subject "ClojoDojo - Your Matches for this Week"
    :body [:div
           [:p "Hi " (:user/name user) ","]
           [:p "Here are your pairing sessions for next week:"]
           (for [event (sort-by :date events)
                 :let [partner (get-user (first (disj (:guest-ids event) user-id)))]]
            [:div.event
             [:span (.format (:date event)
                     (DateTimeFormatter/ofPattern "eee MMM dd 'at' HH:mm"))]
             " with "
             [:span.guest
              (:user/name partner)
              " (" (:user/email partner) ")"]])
           [:p "If you can't make a session, be sure to let your partner know!"]
           [:p "- DojoBot"]]}))

#_(let [[user-id events] (first (group-by-guests (generate-schedule (db/get-users))))]
   (email/send! (sunday-email-template user-id events)))

(defn reset-opt-in! [user-id]
  (-> (db/get-user user-id)
      (assoc :user/pair-next-week? false)
      db/save-user!))

(defn send-sunday-emails! []
  (let [users-to-match (filter :user/pair-next-week? (db/get-users))
        user-id->events (->> users-to-match
                             generate-schedule
                             group-by-guests)
        opted-in-user-ids (set (map :user/id users-to-match))
        matched-user-ids (set (keys user-id->events))
        unmatched-user-ids (set/difference opted-in-user-ids
                                           matched-user-ids)]
   (doseq [user-id unmatched-user-ids]
     (email/send! (unmatched-email-template user-id))
     (reset-opt-in! user-id))

   (doseq [[user-id events] user-id->events]
     (email/send! (matched-email-template user-id events))
     (reset-opt-in! user-id))))

#_(send-sunday-emails!)
(defn schedule-email-job! []
  (chime/chime-at
    (->> (chime/periodic-seq
           (.adjustInto (LocalTime/of 18 0)
                        (ZonedDateTime/now (ZoneId/of "America/Toronto")))
           (Period/ofDays 1))
         (filter (fn [instant]
                   (= DayOfWeek/SUNDAY (.getDayOfWeek instant)))))
    (fn [_]
     (send-sunday-emails!))))
