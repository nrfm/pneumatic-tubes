(ns pneumatic-tubes.core
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.core.async :refer [close! chan <! put!]]
            [clojure.string :as str]
            [cognitect.transit :as t]))

(def ^:private instances (atom {}))

(def r (t/reader :json))
(def w (t/writer :json))

(defn log [& msg] (.log js/console (apply str msg)))
(defn error [& msg] (.error js/console (apply str msg)))
(defn warn [& msg] (.warn js/console (apply str msg)))

(defn increasing-random-timeout [min-timout]
  (fn [retries] (rand (max (* retries 1000) min-timout))))

(def default-config
  {:web-socket-impl  js/WebSocket
   :out-queue-size   10
   :backoff-strategy (increasing-random-timeout 5000)})
(defn- noop [])

(defn tube
  "Creates the spec of a tube."
  ([url on-receive]
   (tube url on-receive noop noop default-config))
  ([url on-receive config]
   (tube url on-receive noop noop config))
  ([url on-receive on-connect on-disconnect]
   (tube url on-receive on-connect on-disconnect default-config))
  ([url on-receive on-connect on-disconnect config]
   {:url           url
    :on-receive    on-receive
    :on-disconnect on-disconnect
    :on-connect    on-connect
    :config        (merge default-config config)}))

(defn- tube-id [tube-spec]
  (:url tube-spec))

(defn- get-tube-instance
  ([tube-spec]
   (get-tube-instance @instances tube-spec))
  ([all-instances tube-spec]
   (get all-instances (tube-id tube-spec))))

(defn- init-tube-instance!
  "Creates new instance if not exist or updates existing"
  [tube-spec socket out-queue]
  (swap! instances
         #(let [inst (get-tube-instance % tube-spec)]
           (assoc % (tube-id tube-spec)
                    {:socket    socket
                     :out-queue out-queue
                     :retries   (if inst (inc (:retries inst)) 0)
                     :connected false
                     :destroyed (if inst (:destroyed inst) false)}))))

(defn- rm-tube-instance! [tube-spec]
  (swap! instances dissoc (tube-id tube-spec)))

(defn- mark-tube-destroyed! [tube-spec]
  (swap! instances assoc-in [(tube-id tube-spec) :destroyed] true))

(defn- mark-tube-connected! [tube-spec]
  (swap! instances assoc-in [(tube-id tube-spec) :connected] true))

(defn dispatch [tube event-v]
  "Sends the event to some tube"
  (let [ch (:out-queue (get-tube-instance tube))]
    (if ch
      (put! ch event-v)
      (throw (js/Error. (str "Tube for " (:url tube) " is not started!"))))))

(defn- start-send-loop [socket out-queue]
  (go-loop
    [event (<! out-queue)]
    (when event
      (.send socket (t/write w event))
      (recur (<! out-queue)))))

(defn create!
  "Creates a tube. On server side this will trigger :tube/on-create event"
  ([tube]
   (create! tube nil))
  ([tube params]
   (let [param-str (str/join "&" (for [[k v] params] (str (name k) "=" v)))
         base-url (:url tube)
         {:keys [on-receive on-disconnect on-connect config]} tube
         {ws-impl :web-socket-impl queue-size :out-queue-size backoff :backoff-strategy} config
         url (if (empty? param-str) base-url (str base-url "?" param-str))
         out-queue (chan queue-size)]
     (if-let [socket (ws-impl. url)]
       (do
         (set! (.-onopen socket) #(do
                                   (log "Created tube on " url)
                                   (mark-tube-connected! tube)
                                   (start-send-loop socket out-queue)
                                   (on-connect)))
         (set! (.-onclose socket) #(let [instance (get-tube-instance tube)
                                         {:keys [out-queue retries connected destroyed]} instance]
                                    (close! out-queue)
                                    (when connected
                                      (on-disconnect))
                                    (if destroyed
                                      (do
                                        (rm-tube-instance! tube)
                                        (log "Destroyed tube on " url)))
                                    (js/setTimeout (fn []
                                                     (log "Reconnect " retries " : " url)
                                                     (create! tube params)) (backoff retries))))
         (set! (.-onmessage socket)
               #(let [event-v (t/read r (-> % .-data))]
                 (on-receive event-v)))
         (init-tube-instance! tube socket out-queue))
       (error "WebSocket connection failed. url: " url)))))

(defn destroy! [tube]
  "Destroys tube. On server this will trigger :tube/on-destroy event."
  (let [socket (:socket (get-tube-instance tube))]
    (mark-tube-destroyed! tube)
    (.close socket)))

(defn send-to-tube-middleware [tube]
  "Middleware to send an event to server 'as-is'"
  (fn [handler]
    (fn [db v]
      (let [new-db (handler db v)]
        (dispatch tube v)
        new-db))))