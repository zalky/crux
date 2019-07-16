(ns crux.jdbc
  (:require [next.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [crux.codec :as c]
            [crux.db :as db]
            crux.api
            [crux.tx :as tx]
            [taoensso.nippy :as nippy]
            [clojure.tools.logging :as log]
            [crux.tx.polling :as p]
            crux.tx.consumer
            [clojure.core.reducers :as r]
            [next.jdbc.result-set :as jdbcr])
  (:import java.io.Closeable
           java.util.Date
           java.util.concurrent.LinkedBlockingQueue
           java.util.concurrent.TimeUnit
           crux.tx.consumer.Message))

(deftype Tx [^Date time ^long id])

(defn- ->date [^java.sql.Timestamp t]
  (java.util.Date. (.getTime t)))

(defn- tx-result->tx-data [ds dbtype tx-result]
  (let [tx-id (:event_offset tx-result)
        tx-time (:tx_time tx-result)]
    (Tx. (->date tx-time) tx-id)))

(defn- insert-event! [ds event-key v topic]
  (let [b (nippy/freeze v)]
    (jdbc/execute-one! ds ["INSERT INTO TX_EVENTS (EVENT_KEY, V, TOPIC) VALUES (?,?,?)" event-key b topic]
                       {:return-keys true :builder-fn jdbcr/as-unqualified-lower-maps})))

(defrecord JdbcLogQueryContext [running?]
  Closeable
  (close [_]
    (reset! running? false)))

(defrecord JdbcTxLog [ds dbtype]
  db/TxLog
  (submit-doc [this content-hash doc]
    (let [id (str content-hash)
          ^Tx result (tx-result->tx-data ds dbtype (insert-event! ds id  doc "docs"))]
      (jdbc/execute! ds ["DELETE FROM TX_EVENTS WHERE TOPIC = 'docs' AND EVENT_KEY = ? AND EVENT_OFFSET < ?" id (.id result)])))

  (submit-tx [this tx-ops]
    (s/assert :crux.api/tx-ops tx-ops)
    (doseq [doc (tx/tx-ops->docs tx-ops)]
      (db/submit-doc this (str (c/new-id doc)) doc))
    (let [tx-events (tx/tx-ops->tx-events tx-ops)
          ^Tx tx (tx-result->tx-data ds dbtype (insert-event! ds nil tx-events "txs"))]
      (delay {:crux.tx/tx-id (.id tx)
              :crux.tx/tx-time (.time tx)})))

  (new-tx-log-context [this]
    (JdbcLogQueryContext. (atom true)))

  (tx-log [this tx-log-context from-tx-id]
    (let [^LinkedBlockingQueue q (LinkedBlockingQueue. 1000)
          running? (:running? tx-log-context)]
      (future
        (try
          (r/reduce (fn [_ y]
                      (.put q {:crux.tx/tx-id (:event_offset y)
                               :crux.tx/tx-time (->date (:tx_time y))
                               :crux.api/tx-ops (nippy/thaw (:v y))})
                      (if @running?
                        nil
                        (reduced nil)))
                    nil
                    (jdbc/plan ds ["SELECT EVENT_OFFSET, TX_TIME, V, TOPIC FROM TX_EVENTS WHERE TOPIC = 'txs' and EVENT_OFFSET >= ?"
                                   (or from-tx-id 0)]
                               {:builder-fn jdbcr/as-unqualified-lower-maps}))
          (catch Throwable t
            (log/error t "Exception occured reading event log")))
        (.put q -1))
      ((fn step []
         (lazy-seq
          (when-let [x (.poll q 5000 TimeUnit/MILLISECONDS)]
            (when (not= -1 x)
              (cons x (step))))))))))

(defrecord JDBCEventLogConsumer [ds]
  crux.tx.consumer/PolledEventLog

  (new-event-log-context [this]
    (reify Closeable
      (close [_])))

  (next-events [this context next-offset]
    (map (fn [result]
           (Message. (nippy/thaw
                      (:v result))
                     nil
                     (:event_offset result)
                     (->date (:tx_time result))
                     (:event_key result)
                     {:crux.tx/sub-topic (keyword (:topic result))}))
         (jdbc/execute! ds ["SELECT EVENT_OFFSET, EVENT_KEY, TX_TIME, V, TOPIC FROM TX_EVENTS WHERE EVENT_OFFSET >= ?" next-offset]
                        {:max-rows 10 :builder-fn jdbcr/as-unqualified-lower-maps})))

  (end-offset [this]
    (inc (val (first (jdbc/execute-one! ds ["SELECT max(EVENT_OFFSET) FROM TX_EVENTS"]))))))