(ns site.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [site.config :as config]))

(deftest config-resolution
  (testing "env file wins over base"
    (is (= "/vault" (:content-path
                     (config/resolve-config {:content-path "/a" :port 8080}
                                            {:content-path "/vault"}
                                            :dev)))))

  (testing "env is stamped and :dev? derives from it"
    (let [dev (config/resolve-config {:port 8080} nil :dev)
          prod (config/resolve-config {:port 8080} {:port 9090} :prod)]
      (is (= :dev (:env dev)))
      (is (true? (:dev? dev)))
      (is (= :prod (:env prod)))
      (is (false? (:dev? prod)))
      (is (= 9090 (:port prod)))))

  (testing "missing env file (nil) is fine"
    (is (= 8080 (:port (config/resolve-config {:port 8080} nil :prod)))))

  (testing "~ expands to the home directory in :content-path"
    (is (= (str (System/getProperty "user.home") "/vault")
           (:content-path (config/resolve-config {:content-path "~/vault"} nil :dev))))
    (is (= "/absolute/path"
           (:content-path (config/resolve-config {:content-path "/absolute/path"} nil :dev))))))
