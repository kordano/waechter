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


(def eval-fn {'(fn replace [old params] params) (fn replace [old params] params)})

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

  (atom (read-string "{\"eve@waechter.net\" {#uuid \"5e2dd6ce-8edb-417b-8ede-39f5314fce17\" {:causal-order {#uuid \"21d50fd8-d812-58f9-816f-46087302dc0a\" []}, :last-update #inst \"2014-07-04T21:07:57.183-00:00\", :id #uuid \"5e2dd6ce-8edb-417b-8ede-39f5314fce17\", :description \"Security Service.\", :schema {:type \"http://github.com/ghubber/geschichte\", :version 1}, :head \"master\", :branches {\"master\" #{#uuid \"21d50fd8-d812-58f9-816f-46087302dc0a\"}}, :public false, :pull-requests {}}}, #uuid \"21d50fd8-d812-58f9-816f-46087302dc0a\" {:transactions [[#uuid \"267cb59b-3168-512e-a787-b84372ab0756\" #uuid \"123ed64b-1e25-59fc-8c5b-038636ae6c3d\"]], :parents [], :ts #inst \"2014-07-04T21:07:57.183-00:00\", :author \"eve@waechter.net\"}, #uuid \"267cb59b-3168-512e-a787-b84372ab0756\" #{}, #uuid \"123ed64b-1e25-59fc-8c5b-038636ae6c3d\" (fn replace [old params] params), datascript.Datom #<database$eval21452$fn__21453 waechter.database$eval21452$fn__21453@208aa623>}"))


  (let [store (<!! (new-mem-store
                    (atom (read-string "{\"eve@waechter.net\" {#uuid \"5e2dd6ce-8edb-417b-8ede-39f5314fce17\" {:causal-order {#uuid \"21d50fd8-d812-58f9-816f-46087302dc0a\" []}, :last-update #inst \"2014-07-04T21:07:57.183-00:00\", :id #uuid \"5e2dd6ce-8edb-417b-8ede-39f5314fce17\", :description \"Security Service.\", :schema {:type \"http://github.com/ghubber/geschichte\", :version 1}, :head \"master\", :branches {\"master\" #{#uuid \"21d50fd8-d812-58f9-816f-46087302dc0a\"}}, :public false, :pull-requests {}}}, #uuid \"21d50fd8-d812-58f9-816f-46087302dc0a\" {:transactions [[#uuid \"267cb59b-3168-512e-a787-b84372ab0756\" #uuid \"123ed64b-1e25-59fc-8c5b-038636ae6c3d\"]], :parents [], :ts #inst \"2014-07-04T21:07:57.183-00:00\", :author \"eve@waechter.net\"}, #uuid \"267cb59b-3168-512e-a787-b84372ab0756\" #{}, #uuid \"123ed64b-1e25-59fc-8c5b-038636ae6c3d\" (fn replace [old params] params), datascript.Datom #<database$eval21452$fn__21453 waechter.database$eval21452$fn__21453@208aa623>}"))
                    (atom {'datascript.Datom
                            (fn [val] (info "DATASCRIPT-DATOM:" val)
                              (konserve.literals.TaggedLiteral. 'datascript.Datom val))})))
        peer (client-peer "CLIENT" store (partial auth store auth-fn (fn [creds] nil) (:trusted-hosts @test-state)))
        stage (<!! (s/create-stage! "eve@waechter.net" peer eval-fn ))]
    (-> (get-in @stage [:volatile :peer])
        deref
        :volatile
        :store
        :state
        deref))


  )
