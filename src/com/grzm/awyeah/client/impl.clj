;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki com.grzm.awyeah.client.impl
  "Impl, don't call directly."
  (:require
   [clojure.core.async :as a]
   [com.grzm.awyeah.client.protocol :as client.protocol]
   [com.grzm.awyeah.client.shared :as shared]
   [com.grzm.awyeah.client.validation :as validation]
   [com.grzm.awyeah.credentials :as credentials]
   [com.grzm.awyeah.endpoint :as endpoint]
   [com.grzm.awyeah.http :as http]
   [com.grzm.awyeah.interceptors :as interceptors]
   [com.grzm.awyeah.protocols :as aws.protocols]
   [com.grzm.awyeah.region :as region]
   [com.grzm.awyeah.retry :as retry]
   [com.grzm.awyeah.signers :as signers]
   [com.grzm.awyeah.util :as util]))

(set! *warn-on-reflection* true)

;; TODO convey throwable back from impl
(defn ^:private handle-http-response
  [service op-map {:keys [status] :as http-response}]
  (try
    (if (:cognitect.anomalies/category http-response)
      http-response
      (if (< status 400)
        (aws.protocols/parse-http-response service op-map http-response)
        (aws.protocols/parse-http-error-response http-response)))
    (catch Throwable t
      {:cognitect.anomalies/category :cognitect.anomalies/fault
       ::throwable t})))

(defn ^:private with-endpoint [req {:keys [protocol hostname port path]}]
  (cond-> (-> req
              (assoc-in [:headers "host"] hostname)
              (assoc :server-name hostname))
    protocol (assoc :scheme protocol)
    port (assoc :server-port port)
    path (assoc :uri path)))

(defn ^:private put-throwable [result-ch t response-meta op-map]
  (a/put! result-ch (with-meta
                      {:cognitect.anomalies/category :cognitect.anomalies/fault
                       ::throwable t}
                      (swap! response-meta
                             assoc :op-map op-map))))

(defn ^:private send-request
  [client op-map]
  (let [{:keys [service http-client region-provider credentials-provider endpoint-provider]}
        (client.protocol/-get-info client)
        response-meta (atom {})
        region-ch (region/fetch-async region-provider)
        creds-ch (credentials/fetch-async credentials-provider)
        response-ch (a/chan 1)
        result-ch (a/promise-chan)]
    (a/go
      (let [region (a/<! region-ch)
            creds (a/<! creds-ch)
            endpoint (endpoint/fetch endpoint-provider region)]
        (cond
          (:cognitect.anomalies/category region)
          (a/>! result-ch region)
          (:cognitect.anomalies/category creds)
          (a/>! result-ch creds)
          (:cognitect.anomalies/category endpoint)
          (a/>! result-ch endpoint)
          :else
          (try
            (let [http-request (signers/sign-http-request service endpoint
                                                          creds
                                                          (-> (aws.protocols/build-http-request service op-map)
                                                              (with-endpoint endpoint)
                                                              (update :body util/->bbuf)
                                                              ((partial interceptors/modify-http-request service op-map))))]
              (swap! response-meta assoc :http-request http-request)
              (http/submit http-client http-request response-ch))
            (catch Throwable t
              (put-throwable result-ch t response-meta op-map))))))
    (a/go
      (try
        (let [response (a/<! response-ch)]
          (a/>! result-ch (with-meta
                            (handle-http-response service op-map response)
                            (swap! response-meta assoc
                                   :http-response (update response :body util/bbuf->input-stream)))))
        (catch Throwable t
          (put-throwable result-ch t response-meta op-map))))
    result-ch))

(defrecord Client [info]
  client.protocol/Client
  (-get-info [_] info)

  (-invoke [client op-map]
    (a/<!! (client.protocol/-invoke-async client op-map)))

  (-invoke-async [client {:keys [op request] :as op-map}]
    (let [result-chan (or (:ch op-map) (a/promise-chan))
          {:keys [service retriable? backoff]} (client.protocol/-get-info client)
          spec (and (validation/validate-requests? client) (validation/request-spec service op))]
      (cond
        (not (contains? (:operations service) (:op op-map)))
        (a/put! result-chan (validation/unsupported-op-anomaly service op))

        (and spec (not (validation/valid? spec request)))
        (a/put! result-chan (validation/invalid-request-anomaly spec request))

        :else
        (retry/with-retry
          #(send-request client op-map)
          result-chan
          (or (:retriable? op-map) retriable?)
          (or (:backoff op-map) backoff)))

      result-chan))

  (-stop [aws-client]
    (let [{:keys [http-client]} (client.protocol/-get-info aws-client)]
      (when-not (#'shared/shared-http-client? http-client)
        (http/stop http-client)))))

;; ->Client is intended for internal use
(alter-meta! #'->Client assoc :skip-wiki true)

(defn client [client-meta info]
  (let [region (some-> info :region-provider region/fetch)]
    (-> (with-meta (->Client info) @client-meta)
        (assoc :region region
               :endpoint (some-> info :endpoint-provider (endpoint/fetch region))
               :credentials (some-> info :credentials-provider credentials/fetch)
               :service (some-> info :service (select-keys [:metadata]))
               :http-client (:http-client info)))))
