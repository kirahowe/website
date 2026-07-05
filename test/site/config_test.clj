(ns site.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [site.config :as config]))

(deftest config-resolution
  (testing "local file wins over base"
    (is (= "/vault" (:content-path
                     (config/resolve-config {:content-path "/a" :port 8080}
                                            {:content-path "/vault"}
                                            {})))))

  (testing "overrides (task opts) win over everything"
    (let [c (config/resolve-config {:port 8080 :dev? false}
                                   {:port 9090}
                                   {:dev? true})]
      (is (= 9090 (:port c)))
      (is (true? (:dev? c)))))

  (testing "missing local file (nil) is fine"
    (is (= 8080 (:port (config/resolve-config {:port 8080} nil {})))))

  (testing "~ expands to the home directory in :content-path"
    (is (= (str (System/getProperty "user.home") "/vault")
           (:content-path (config/resolve-config {:content-path "~/vault"} nil {}))))
    (is (= "/absolute/path"
           (:content-path (config/resolve-config {:content-path "/absolute/path"} nil {}))))))
