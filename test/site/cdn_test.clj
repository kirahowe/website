(ns site.cdn-test
  (:require [clojure.test :refer [deftest is testing]]
            [site.cdn :as cdn]))

(deftest purge-home-uses-only-canonical-homepage
  (let [request (atom nil)]
    (is (= :purged
           (cdn/purge-home! (fn [request-map]
                              (reset! request request-map)
                              {:status 200 :body "{\"success\":true}"})
                            {:base-url "https://example.com/"
                             :cloudflare-zone-id "zone-id"
                             :cloudflare-api-token "secret"})))
    (is (= "https://api.cloudflare.com/client/v4/zones/zone-id/purge_cache"
           (:uri @request)))
    (is (= 5000 (:timeout @request)))
    (is (false? (:throw @request)))
    (is (= "Bearer secret" (get-in @request [:headers "Authorization"])))
    (is (= "{\"files\":[\"https://example.com/\"]}" (:body @request)))))

(deftest purge-home-failure-is-best-effort
  (testing "HTTP failures do not throw"
    (is (= :failed
           (cdn/purge-home! (fn [_] {:status 500 :body "failed"})
                            {:base-url "https://example.com"
                             :cloudflare-zone-id "zone-id"
                             :cloudflare-api-token "secret"}))))
  (testing "a successful HTTP response with an API failure is not a purge"
    (is (= :failed
           (cdn/purge-home! (fn [_] {:status 200 :body "{\"success\":false}"})
                            {:base-url "https://example.com"
                             :cloudflare-zone-id "zone-id"
                             :cloudflare-api-token "secret"}))))
  (testing "request exceptions do not throw"
    (is (= :failed
           (cdn/purge-home! (fn [_] (throw (ex-info "network down" {})))
                            {:base-url "https://example.com"
                             :cloudflare-zone-id "zone-id"
                             :cloudflare-api-token "secret"}))))
  (testing "without production secrets no request is made"
    (is (= :skipped (cdn/purge-home! {:base-url "https://example.com"})))))
