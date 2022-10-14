;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns com.grzm.awyeah.config-test
  (:require
   [clojure.test :refer [deftest is]]
   [clojure.java.io :as io]
   [com.grzm.awyeah.config :as config]))

(deftest read-config
  (let [config (config/parse (io/resource ".aws/config"))]
    (is (= {"region" "us-west-1"}
           (get config "tardigrade")))
    (is (= {"s3" {"max_concurrent_requests" "10"
                  "max_queue_size" "1000"
                  "s3_key" "s3_val"}
            "region" "eu-west-1"
            "foo.bar" "baz"}
           (get config "nested")))
    (is (re-matches
          #"^awsprocesscreds.*specialness$"
          (get-in config ["waterbear" "credential_process"])))
    (is (= "FQoG/Ehj40mh/xf0TR+xLl+cp/xGWC+haIy+fJh6/fD+LFW="
           (get-in config ["temp-credentials" "aws_session_token"])))))
