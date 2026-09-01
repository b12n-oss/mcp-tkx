(ns ^:no-doc mcp-toolkit.impl.subscriptions
  "Subscription bookkeeping for `subscriptions/listen`, the 2026-07-28
   replacement for `resources/subscribe` and the HTTP GET endpoint.

   A client opens a stream by sending `subscriptions/listen` with a filter
   naming the notification types it wants. The request itself stays open and
   is the stream. Every notification delivered on it carries the JSON-RPC id
   of that request, so a client holding several streams can tell them apart.

   Nothing here touches the transport. On stdio every subscription shares one
   channel and the id is what separates them, which is exactly the shape the
   `send-message` abstraction already has."
  (:require
   [clojure.string :as str]
   [mcp-toolkit.protocol :as protocol]))

(def topic->filter-key
  "Maps a notification topic to the filter field that opts in to it."
  {"tools/list_changed"     :tools-list-changed
   "prompts/list_changed"   :prompts-list-changed
   "resources/list_changed" :resources-list-changed
   "resources/updated"      :resource-subscriptions})

(def ^:private filter-key->capability
  "Which server capability has to be present for a filter field to be
   honourable. A server with no prompts cannot promise prompt list changes."
  {:tools-list-changed     :tools
   :prompts-list-changed   :prompts
   :resources-list-changed :resources
   :resource-subscriptions :resources})

(defn honoured-filter
  "Narrows a client's requested filter to what this server can actually
   deliver.

   The acknowledgement has to report the subset the server agreed to honour,
   with unsupported types omitted, so a client can tell the difference between
   a quiet stream and one it was never going to get anything on.

   Args:
     capabilities - The server capabilities, as reported by server/discover
     requested    - The filter from the subscriptions/listen params

   Returns:
     The filter the server commits to."
  [capabilities requested]
  (let [supported? (fn [filter-key]
                     (contains? capabilities (filter-key->capability filter-key)))]
    (cond-> {}
      (and (:tools-list-changed requested)
           (supported? :tools-list-changed))
      (assoc :tools-list-changed true)

      (and (:prompts-list-changed requested)
           (supported? :prompts-list-changed))
      (assoc :prompts-list-changed true)

      (and (:resources-list-changed requested)
           (supported? :resources-list-changed))
      (assoc :resources-list-changed true)

      (and (seq (:resource-subscriptions requested))
           (supported? :resource-subscriptions))
      (assoc :resource-subscriptions (vec (:resource-subscriptions requested))))))

(defn requested-filter
  "Normalises the filter a client asked for, without narrowing it to what the
   server can serve right now.

   This is what gets STORED, while `honoured-filter` is what gets
   acknowledged. The two differ deliberately. Capability is derived from the
   registries `add-tool`, `add-prompt` and `add-resource` exist to mutate, so
   narrowing at subscribe time meant a client that subscribed before those ran
   held a permanently dead stream: the filter had already dropped the key and
   nothing later could put it back.

   Storing the request is safe because a notification only ever originates
   when the server actually has the thing to report. The subscribe-time check
   was redundant for delivery and destructive for anything registered later."
  [requested]
  (cond-> {}
    (:tools-list-changed requested)     (assoc :tools-list-changed true)
    (:prompts-list-changed requested)   (assoc :prompts-list-changed true)
    (:resources-list-changed requested) (assoc :resources-list-changed true)
    (seq (:resource-subscriptions requested))
    (assoc :resource-subscriptions (vec (:resource-subscriptions requested)))))

(defn uri-covered-by?
  "True when a subscription to `subscribed` should receive an update about
   `uri`.

   An exact match always counts. The spec also allows a notification to name a
   sub-resource of what was subscribed to, so a subscription to a URI ending
   in a slash covers everything beneath it. Requiring the trailing slash keeps
   `file:///proj` from silently capturing `file:///project`.

   Args:
     subscribed - A URI from a subscription's resource-subscriptions
     uri        - The URI being reported

   Returns:
     true when this subscription should hear about it."
  [subscribed uri]
  (or (= subscribed uri)
      (and (str/ends-with? subscribed "/")
           (str/starts-with? uri subscribed))))

(defn wants?
  "True when a subscription's filter opts in to this notification.

   Args:
     subscription-filter - The stored filter for one subscription, which is
                           what the client requested rather than the subset
                           acknowledged at subscribe time
     topic               - A notification topic, without the notifications/ prefix
     uri                 - The resource URI, for resources/updated only

   Returns:
     true when the notification belongs on this subscription's stream."
  [subscription-filter topic uri]
  (if-some [filter-key (topic->filter-key topic)]
    (if (= :resource-subscriptions filter-key)
      (boolean (some (fn [subscribed] (uri-covered-by? subscribed uri))
                     (:resource-subscriptions subscription-filter)))
      (true? (get subscription-filter filter-key)))
    false))

(defn compare-subscription-ids
  "Orders two JSON-RPC ids, which may be numbers or strings.

   `sort` alone throws a ClassCastException the moment one client picks a
   numeric id and another picks a string, and JSON-RPC allows both. Numbers
   sort before strings, and within a type the natural order applies.

   Public because `server/close-all-subscriptions!` needs it too. It sorted
   the same ids with a bare `sort` and threw on the same mixture, which is
   what fixing only `subscriber-ids` missed."
  [a b]
  (let [rank (fn [id] (if (number? id) 0 1))
        ra   (rank a)
        rb   (rank b)]
    (if (= ra rb)
      (compare a b)
      (compare ra rb))))

(defn subscriber-ids
  "Returns the ids of every subscription that opted in to this notification.

   The order is deterministic but is not the order the subscriptions were
   opened, because `:subscription-by-id` is an unordered map. Ids are sorted,
   with numbers before strings, so that a fan-out is reproducible whatever
   mixture of id types the connected clients happen to have chosen.

   Args:
     session-value - A dereferenced server session
     topic         - A notification topic, without the notifications/ prefix
     uri           - The resource URI, for resources/updated only

   Returns:
     A sequence of subscription ids."
  [session-value topic uri]
  (->> (:subscription-by-id session-value)
       (filter (fn [[_ subscription-filter]] (wants? subscription-filter topic uri)))
       (map key)
       (sort compare-subscription-ids)))

(defn tag
  "Stamps a message's params with the subscription it belongs to.

   Every message on a subscription stream carries this, including the response
   that closes it. A client on stdio has no other way to tell which stream a
   notification came from.

   Args:
     message         - A JSON-RPC notification or response
     subscription-id - The id of the subscriptions/listen request

   Returns:
     The message with the subscription id in its _meta."
  [message subscription-id]
  (assoc-in message [:params :_meta protocol/meta-subscription-id] subscription-id))

(defn close-response
  "Builds the response that ends a subscription gracefully.

   A stream that stops without this looks to the client like a dropped
   transport, which it may treat as a reason to reconnect. Sending it says the
   subscription ended on purpose.

   Args:
     subscription-id - The id of the subscriptions/listen request

   Returns:
     A full JSON-RPC response."
  [subscription-id]
  {:jsonrpc "2.0"
   :id subscription-id
   :result {:result-type "complete"
            :_meta {protocol/meta-subscription-id subscription-id}}})
