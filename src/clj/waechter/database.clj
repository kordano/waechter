(ns waechter.database
  (:require [geschichte.repo :as repo]
            [geschichte.stage :as s]
            [geschichte.meta :refer [update]]
            [geschichte.sync :refer [server-peer client-peer wire]]
            [geschichte.platform :refer [create-http-kit-handler!]]
            [geschichte.auth :refer [auth]]
            [konserve.store :refer [new-mem-store]]
            [konserve.platform :refer [new-couch-store]]
            [cemerick.friend :as friend]
            [cemerick.friend.workflows :as workflows]
            [cemerick.friend.credentials :as creds]
            [clojure.tools.logging :refer [info warn error]]
            [clojure.core.async :refer [timeout sub chan <!! >!! <! >! go go-loop] :as async]))


(def eval-fn {'(fn replace [old params] params) (fn replace [old params] params)
              '(fn [old params] (clojure.set/union old params)) (fn [old params] (clojure.set/union old params))})

(defn create-store
  "Creates a konserve store"
  [state]
  (swap!
   state
   (fn [old new] (assoc-in old [:store] new))
   (<!! (new-mem-store))
   #_(<!! (new-couch-store
             (couch (utils/url (:couchdb-url @state) "waechter"))
             (:tag-table @state))))
  state)

(defn- cred-fn [creds]
  (creds/bcrypt-credential-fn {"eve@polyc0l0r.net" {:username "eve@waechter.net"
                                                    :password (creds/hash-bcrypt "lisp")
                                                    :roles #{::user}}}
                              creds))

(defn- auth-fn [users]
  (go (println "AUTH-REQUIRED: " users)
      {}))

(defn create-peer
  "Creates geschichte server peer"
  [state]
  (let [{:keys [proto host port build tag-table store trusted-hosts]} @state]
    (swap! state
           (fn [old new] (assoc-in old [:peer] new))
           (server-peer (create-http-kit-handler!
                         (str (if (= proto "https") "wss" "ws") ;; should always be wss with auth
                              "://" host
                              (when (= :dev build)
                                (str ":" port))
                              "/geschichte/ws")
                         tag-table)
                        store
                        (partial auth store auth-fn cred-fn (atom (or (:trusted-hosts @state)
                                                                      #{}))))))
  state)


(comment


  (def test-state
    (atom {:build :dev
           :behind-proxy false
           :proto "http"
           :port 8086
           :host "localhost" ;; adjust hostname
           :couchdb-url (str "http://"
                             (or (System/getenv "DB_PORT_5984_TCP_ADDR") "localhost")
                             ":5984")
           :trusted-hosts #{}
           }))

  (def store (<!! (new-mem-store)))

  (def peer (client-peer "CLIENT" store (partial auth store auth-fn (fn [creds] nil) (:trusted-hosts @test-state))))

  (def stage (<!! (s/create-stage! "eve@waechter.net" peer eval-fn )))

  (def repo-id (<!! (s/create-repo! stage "eve@waechter.net" "Synched secured stuff." #{{:username "eve@waechter.net" :account "topiq.es" :pw "1234"}} "master")))

(<!! (s/subscribe-repos! stage
                          {"eve@polyc0l0r.net"
                           {repo-id
                            #{"master"}}}))

  (go (<! (s/transact
           stage
           ["eve@waechter.net"
            repo-id
            "master"]
           #{{:username "eve@waechter.net" :account "friendface" :pw "4321"}}

           '(fn [old params] (clojure.set/union old params))))
    (<! (s/commit! stage {"eve@waechter.net" {repo-id #{"master"}}})))

  (-> @stage :volatile)


  (clojure.set/union #{123} #{234})

  (atom
   {"eve@waechter.net" {#uuid "b7e2c3e4-d4c5-4d75-a092-b7b1cc9731dc" {:causal-order {#uuid "3305b822-01e7-58fd-a387-505f9a8252f5" []}, :last-update #inst "2014-07-05T16:51:20.812-00:00", :id #uuid "b7e2c3e4-d4c5-4d75-a092-b7b1cc9731dc", :description "Synched secured stuff.", :schema {:type "http://github.com/ghubber/geschichte", :version 1}, :head "master", :branches {"master" #{#uuid "3305b822-01e7-58fd-a387-505f9a8252f5"}}, :public false, :pull-requests {}}}, #uuid "3305b822-01e7-58fd-a387-505f9a8252f5" {:transactions [[#uuid "1ff2e0a8-fbce-51d3-b4eb-eca611e1d165" #uuid "123ed64b-1e25-59fc-8c5b-038636ae6c3d"]], :parents [], :ts #inst "2014-07-05T16:51:20.812-00:00", :author "eve@waechter.net"}, #uuid "123ed64b-1e25-59fc-8c5b-038636ae6c3d" (fn replace [old params] params), #uuid "1ff2e0a8-fbce-51d3-b4eb-eca611e1d165" #{{:username "eve@waechter.net", :account "topiq.es", :pw "1234"}}})





  (atom (read-string "{\"eve@waechter.net\" {#uuid \"b7e2c3e4-d4c5-4d75-a092-b7b1cc9731dc\" {:causal-order {#uuid \"3305b822-01e7-58fd-a387-505f9a8252f5\" []}, :last-update #inst \"2014-07-05T16:51:20.812-00:00\", :id #uuid \"b7e2c3e4-d4c5-4d75-a092-b7b1cc9731dc\", :description \"Synched secured stuff.\", :schema {:type \"http://github.com/ghubber/geschichte\", :version 1}, :head \"master\", :branches {\"master\" #{#uuid \"3305b822-01e7-58fd-a387-505f9a8252f5\"}}, :public false, :pull-requests {}}}, #uuid \"3305b822-01e7-58fd-a387-505f9a8252f5\" {:transactions [[#uuid \"1ff2e0a8-fbce-51d3-b4eb-eca611e1d165\" #uuid \"123ed64b-1e25-59fc-8c5b-038636ae6c3d\"]], :parents [], :ts #inst \"2014-07-05T16:51:20.812-00:00\", :author \"eve@waechter.net\"}, #uuid \"123ed64b-1e25-59fc-8c5b-038636ae6c3d\" (fn replace [old params] params), #uuid \"1ff2e0a8-fbce-51d3-b4eb-eca611e1d165\" #{{:username \"eve@waechter.net\", :account \"topiq.es\", :pw \"1234\"}}}"))

  )
