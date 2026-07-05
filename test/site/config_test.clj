(ns site.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [site.config :as config]))

(deftest config-resolution
  (testing "local file wins over base"
    (is (= "/vault" (:content-path
                     (config/resolve-config {:content-path "/a" :port 8080}
                                            {:content-path "/vault"}
                                            {} {})))))

  (testing "env (secrets) wins over files, overrides win over everything"
    (let [c (config/resolve-config {:port 8080}
                                   {:port 9090}
                                   {:admin-token "s" :port 1111}
                                   {:port 2222})]
      (is (= "s" (:admin-token c)))
      (is (= 2222 (:port c)))))

  (testing "missing local file (nil) is fine"
    (is (= 8080 (:port (config/resolve-config {:port 8080} nil {} {})))))

  (testing "~ expands to the home directory in :content-path"
    (is (= (str (System/getProperty "user.home") "/vault")
           (:content-path (config/resolve-config {:content-path "~/vault"} nil {} {}))))
    (is (= "/absolute/path"
           (:content-path (config/resolve-config {:content-path "/absolute/path"} nil {} {}))))))
