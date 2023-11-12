(ns com.grzm.awyeah.protocols.rest-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.grzm.awyeah.protocols.rest :as protocols.rest]))

(deftest test-serialize-url
  (testing "ensures no double-slash"
    (is (= "/a/b/c/d/e/f"
           (protocols.rest/serialize-uri "/{Foo+}/{Bar+}" {} {:Foo "a/b/c" :Bar "d/e/f"})
           (protocols.rest/serialize-uri "/{Foo+}/{Bar+}" {} {:Foo "a/b/c" :Bar "/d/e/f"})
           (protocols.rest/serialize-uri "/{Foo+}/{Bar+}" {} {:Foo "/a/b/c" :Bar "/d/e/f"})
           (protocols.rest/serialize-uri "/{Foo+}/{Bar+}" {} {:Foo "/a/b/c" :Bar "d/e/f"}))))
  (testing "throws when required key is missing"
    (is (thrown-with-msg? Exception
                          #"missing"
                          (protocols.rest/serialize-uri "/{Bucket}" {:required ["Bucket"]} {})))
    (is (thrown-with-msg? Exception
                          #"missing"
                          (protocols.rest/serialize-uri "/{Bucket}" {:required ["Bucket"]} {:BucketName "wrong key"})))
    (is (thrown-with-msg? Exception
                          #"missing"
                          (protocols.rest/serialize-uri "/{Bucket}/{Key+}" {:required ["Bucket" "Key"]} {:Bucket "foo"}))))
  (testing "serializes longs"
    (is (= "/a/1/b"
           (protocols.rest/serialize-uri "/a/{count}/b" {:type "structure"
                                                         :members {:Count {:shape "__long", :location "uri", :locationName "count"}}
                                                         :required ["Count"]}
                                         {:count 1})))))
