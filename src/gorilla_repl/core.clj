;;;; This file is part of gorilla-repl. Copyright (C) 2014-, Jony Hudson.
;;;;
;;;; gorilla-repl is licenced to you under the MIT licence. See the file LICENCE.txt for full details.

(ns gorilla-repl.core
  (:use compojure.core)
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            [org.httpkit.server :as server]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.params :as params]
            [ring.middleware.json :as json]
            [ring.util.response :as res]
            [gorilla-repl.nrepl :as nrepl]
            [gorilla-repl.websocket-relay :as ws-relay]
            [gorilla-repl.renderer :as renderer] ;; this is needed to bring the render implementations into scope
            [gorilla-repl.files :as files]
            [clojure.set :as set]
            [clojure.java.io :as io])
  (:gen-class))

;; useful for debugging the nREPL requests
(defn print-req
  [handler]
  (fn [request]
    (println (:params request))
    (handler request)))

;; a wrapper for JSON API calls
(defn wrap-api-handler
  [handler]
  (-> handler
      (keyword-params/wrap-keyword-params)
      (params/wrap-params)
      (json/wrap-json-response)))

;; the worksheet load handler
(defn load-worksheet
  [req]
  ;; TODO: S'pose some error handling here wouldn't be such a bad thing
  (when-let [ws-file (:worksheet-filename (:params req))]
    (let [_ (print (str "Loading: " ws-file " ... "))
          ws-data (slurp (str ws-file) :encoding "UTF-8")
          _ (println "done.")]
      (res/response {:worksheet-data ws-data}))))

;; A hook so another system can capture the
;; saving of the file
(def capture-save (atom (fn [& _])))

;; the client can post a request to have the worksheet saved, handled by the following
(defn save
  [req]
  ;; TODO: error handling!
  (when-let [ws-data (:worksheet-data (:params req))]
    (when-let [ws-file (:worksheet-filename (:params req))]
      (print (str "Saving: " ws-file " ... "))
      (@capture-save ws-data ws-file)
      (spit ws-file ws-data)
      (println (str "done. [" (java.util.Date.) "]"))
      (res/response {:status "ok"}))))


;; More ugly atom usage to support defroutes
(def excludes (atom #{".git"}))
;; API endpoint for getting the list of worksheets in the project
(defn gorilla-files [req]
  (let [excludes @excludes]
  (res/response {:files (files/gorilla-filepaths-in-current-directory excludes)})))

;; configuration information that will be made available to the webapp
(def conf (atom {}))
(defn set-config [k v] (swap! conf assoc k v))
;; API endpoint for getting webapp configuration information
(defn config [req] (res/response @conf))


;; the combined routes - we serve up everything in the "public" directory of resources under "/".
;; The REPL traffic is handled in the websocket-transport ns.
(defroutes app-routes
           (GET "/load" [] (wrap-api-handler load-worksheet))
           (POST "/save" [] (wrap-api-handler save))
           (GET "/gorilla-files" [] (wrap-api-handler gorilla-files))
           (GET "/config" [] (wrap-api-handler config))
           (GET "/repl" [] ws-relay/ring-handler)
           (route/resources "/")
           (route/files "/project-files" [:root "."]))

(defn fix-prefix
  [{:keys [uri] :as  request}]
  (if-let [info (re-find #"^/visi/[^/]*(/.*)$" uri)]
    (do
      (println "Rewriting " uri " to " (get info 1))
      (assoc request :uri (get info 1)))
    request
    )
  )

(defn fix-slash
  [{:keys [uri] :as  request}]
  (if (.endsWith uri "/")
    (assoc request :uri (str uri "index.html"))
    request)
  )

(defn rw-app-routes
  [request]
  (-> request fix-prefix fix-slash app-routes)
  )

(defn- mk-number
  [x]
  (if (string? x) (read-string x) x))


(defn run-gorilla-server
  [conf]
  (let [version (or (:version conf) "develop")

        webapp-requested-port (mk-number (or (:port conf) 0))
        ip (or (:ip conf) "127.0.0.1")
        nrepl-requested-port (or (:nrepl-port conf) 0)  ;; auto-select port if none requested
        project (or (:project conf) "no project")
        to-load (or (:load-file conf) "/worksheet.html")
        keymap (or (:keymap (:gorilla-options conf)) {})
        _ (swap! excludes (fn [x] (set/union x (:load-scan-exclude (:gorilla-options conf)))))]
    ;; app startup
    (println "Gorilla-REPL:" version)
    ;; build config information for client
    (set-config :project project)
    (set-config :keymap keymap)
    (set-config :worksheetName to-load)
    (set-config :params (:client-params conf))
    (set-config :strings (:client-strings conf))
    (set-config :autosave true)
    (set-config :recalcAllOnLoad true)

    ;; first startup nREPL
    (nrepl/start-and-connect nrepl-requested-port)
    ;; and then the webserver
    (let [s (server/run-server #'rw-app-routes {:port webapp-requested-port :join? false :ip ip})
          webapp-port (:local-port (meta s))]
      (spit (doto (io/file ".gorilla-port") .deleteOnExit) webapp-port)
      (println (str "Running at http://" ip ":" webapp-port to-load))

      (println "Ctrl+C to exit."))))

(defn -main
  [& args]
  (run-gorilla-server {:port 8990}))
