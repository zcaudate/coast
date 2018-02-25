(ns coast.alpha.migrations
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [coast.alpha.db :refer [defq] :as db]
            [coast.alpha.queries :as queries]
            [coast.alpha.time :as time])
  (:import (java.io File)))

(def empty-migration "-- up\n\n-- down")

(defn migrations-dir []
  (.mkdirs (File. "resources/migrations"))
  "resources/migrations")

(defq create-table "resources/sql/migrations.sql")
(defq migrations "resources/sql/migrations.sql")
(defq insert "resources/sql/migrations.sql")
(defq delete "resources/sql/migrations.sql")

(defn completed-migrations []
  (let [_ (create-table)]
    (->> (migrations)
         (map :id))))

(defn migration-files []
  (->> (migrations-dir)
       (io/file)
       (file-seq)
       (filter #(.isFile %))
       (map #(.getName %))
       (filter #(.endsWith % ".sql"))))

(defn pending []
  (let [all (set (migration-files))
        completed (set (completed-migrations))]
    (sort (vec (clojure.set/difference all completed)))))

(defn parse [s name]
  (when (not (nil? name))
    (-> (str (migrations-dir) "/" name)
        (slurp)
        (queries/parse)
        (get-in [s :sql]))))

(defn migrate []
  (let [migrations (pending)]
    (doseq [migration migrations]
      (db/query (db/connection) (parse "up" migration))
      (insert {:id migration})
      (println (format "%s migrated successfully" (string/replace migration #"\.sql" ""))))))

(defn rollback []
  (let [migration (-> (completed-migrations) (last))
        _ (->> (parse "down" migration)
               (db/query (db/connection)))]
    (delete {:id migration})
    (println (format "%s rolled back successfully" (string/replace migration #"\.sql" "")))))

(defn timestamp []
  (-> (time/now)
      (time/fmt "yyyyMMddHHmmss")))

(defn filename [name]
  (when (and
          ((comp not nil?) name)
          ((comp not empty?) name)
          (string? name))
    (str (timestamp) "_" (string/replace name #"\s+|-+|_+" "_") ".sql")))

(defn column [arg]
  (let [[name type] (string/split arg #":")]
    (str name " " type)))

(defn column-name [arg]
  (-> arg
      (string/split #":")
      (first)))

(defn parse-table-name [s]
  (->> (string/split s #"[-_]")
       (drop 1)
       (string/join "_")))

(defn create-table-contents [name args]
  (let [table (parse-table-name name)
        sql (str "create table " table " (\n")
        columns (map column args)
        columns (-> columns
                    (conj "  id serial primary key")
                    vec
                    (conj "created_at timestamp without time zone default (now() at time zone 'utc')"))
        column-string (string/join ",\n  " columns)]
    (str sql column-string "\n)")))

(defn drop-table-contents [s]
  (str "drop table " (parse-table-name s)))

(defn add-column-contents [s args]
  (let [table (->> (re-seq #"add-columns-to-([\w-_]+)" s)
                   (first)
                   (last))
        columns (map column args)]
    (->> (map #(str "alter table " table  " add column " % ";") columns)
         (string/join "\n"))))

(defn add-column-contents-down [s args]
  (let [table (->> (re-seq #"add-columns-to-([\w-_]+)" s)
                   (first)
                   (last))
        columns (map column args)]
    (->> (map #(str "alter table " table  " drop " % ";") columns)
         (string/join "\n"))))

(defn drop-column-contents [s args]
  (let [table (->> (re-seq #"drop-columns-from-([\w-_]+)" s)
                   (first)
                   (last))
        columns (map column-name args)]
    (->> (map #(str "alter table " table  " drop " % ";") columns)
         (string/join "\n"))))

(defn drop-column-contents-down [s args]
  (let [table (->> (re-seq #"drop-columns-from-([\w-_]+)" s)
                   (first)
                   (last))
        columns (map column-name args)]
    (->> (map #(str "alter table " table  " add column " % ";") columns)
         (string/join "\n"))))

(defn contents [s args]
  (cond
    (string/starts-with? s "create-") (str "-- up\n" (create-table-contents s args) "\n\n"
                                           "-- down\n" (drop-table-contents s))
    (string/starts-with? s "drop-") (str "-- up\n\n"
                                         "-- down\n" (drop-table-contents s))
    (string/starts-with? s "add-columns-to-") (str "-- up\n" (add-column-contents s args) "\n\n"
                                                   "-- down\n" (add-column-contents-down s args))
    (string/starts-with? s "drop-columns-from-") (str "-- up\n" (drop-column-contents s args) "\n\n"
                                                      "-- down\n" (drop-column-contents-down s args))
    :else empty-migration))

(defn create [name & args]
  (let [migration (filename name)
        dir (migrations-dir)]
    (spit (str dir "/" migration) (contents name args))
    (println (str dir "/" migration " created"))
    migration))