(ns ^:no-doc mcp-toolkit.impl.mrtr
  "Multi Round-Trip Requests, the 2026-07-28 replacement for server-initiated
   requests.

   Under earlier revisions a server asked the client for something mid-call
   and awaited the answer. 2026-07-28 removed that. Instead a handler returns
   an `input_required` result carrying the requests it needs answered, plus an
   opaque `request-state` blob. The client fulfils the requests and re-issues
   the *original* request with `input-responses` and the same `request-state`.

   The retry may land on a different process, so `request-state` has to carry
   everything the handler needs to resume. It cannot be a parked continuation."
  (:require
   [clojure.string :as str]
   [mcp-toolkit.protocol :as protocol]))

(def ^:private correlation-key-prefix "mcp-toolkit/")

(defn ->wire-key
  "Renders a correlation key for the wire.

   Keys are namespaced deliberately. They travel to the client and come back
   untouched, so they have to survive the JSON boundary exactly. A bare key
   does not: camel-snake-kebab turns `:step1` into \"step1\" and reads it back
   as `:step-1`, and it collapses `:foo-bar`, `:foo_bar` and `:fooBar` onto a
   single wire key. Adding a namespace makes the key opaque under
   `protocol/opaque-wire-key?`, so transports pass it through verbatim.

   The caller's own namespace is preserved. `name` alone dropped it, so
   `:step/one` and `:other/one` both rendered as the same wire key, and one of
   the two input requests was silently discarded, its answer never arriving.
   A bare key is unaffected and renders exactly as before.

   Args:
     k - A keyword or string naming one request within a round trip

   Returns:
     The namespaced string key used on the wire."
  [k]
  (str correlation-key-prefix
       (when-some [k-ns (namespace k)] (str k-ns "/"))
       (name k)))

(defn <-wire-key
  "Recovers the caller's key from a wire correlation key.

   Args:
     s - A wire key produced by `->wire-key`

   Returns:
     The original key as a keyword, or the input unchanged if it carries no
     recognised prefix."
  [s]
  (let [s (name s)]
    (if (str/starts-with? s correlation-key-prefix)
      (keyword (subs s (count correlation-key-prefix)))
      (keyword s))))

(defn input-required
  "Builds the `input_required` result a handler returns when it needs the
   client to answer something before it can finish.

   Args:
     opts - Map of:
            :input-requests - Map of your own key to a request map, each
                              {:method \"...\" :params {...}}. Build these with
                              `sampling-request`, `elicit-form-request`,
                              `elicit-url-request` or `roots-request`.
            :request-state  - Optional string carried to the client and handed
                              back on the retry. It must be self-contained,
                              because the retry may reach another process.

   Returns:
     A result map with :result-type \"input_required\"."
  [{:keys [input-requests request-state]}]
  (cond-> {:result-type "input_required"
           :input-requests (into {}
                                 (map (fn [[k v]] [(->wire-key k) v]))
                                 input-requests)}
    (some? request-state) (assoc :request-state request-state)))

(defn input-required?
  "Returns true when a handler result is an `input_required` interim result.

   Args:
     result - A handler's return value

   Returns:
     true when the result asks the client for more input."
  [result]
  (and (map? result)
       (= "input_required" (:result-type result))))

(defn input-responses
  "Returns every input response on the current request, keyed the way the
   handler originally asked for them.

   Args:
     context - The handler context

   Returns:
     A map of your key to the client's response, or nil on a first attempt."
  [context]
  (when-some [responses (-> context :message :params :input-responses)]
    (into {}
          (map (fn [[k v]] [(<-wire-key k) v]))
          responses)))

(defn input-response
  "Returns one input response by the key the handler asked under.

   Args:
     context - The handler context
     k       - The key used in the matching `input-required` call

   Returns:
     The client's response, or nil when this is a first attempt."
  [context k]
  (get (-> context :message :params :input-responses) (->wire-key k)))

(defn request-state
  "Returns the opaque state this handler sent on its previous turn.

   Args:
     context - The handler context

   Returns:
     The request-state string, or nil on a first attempt."
  [context]
  (-> context :message :params :request-state))

(defn retry?
  "Returns true when the current request is a client retry carrying answers,
   rather than a first attempt.

   Args:
     context - The handler context

   Returns:
     true when input responses or request state are present."
  [context]
  (let [{:keys [input-responses request-state]} (-> context :message :params)]
    (boolean (or (seq input-responses) (some? request-state)))))

;; ---------------------------------------------------------------------------
;; Request builders
;;
;; The three InputRequest kinds 2026-07-28 defines. Sampling and roots are
;; deprecated by that revision but remain functional for at least twelve
;; months, so they are built here too.
;; ---------------------------------------------------------------------------

(defn sampling-request
  "Builds a `sampling/createMessage` input request.

   Args:
     params - Sampling params, at minimum :messages and :max-tokens

   Returns:
     An input request map."
  [params]
  {:method "sampling/createMessage"
   :params params})

(defn roots-request
  "Builds a `roots/list` input request.

   Returns:
     An input request map."
  []
  {:method "roots/list"
   :params {}})

(defn elicit-form-request
  "Builds a form-mode `elicitation/create` input request.

   Args:
     params - Map of:
              :message          - Prompt shown to the user
              :requested-schema - JSON Schema of the data being asked for

   Returns:
     An input request map."
  [{:keys [message requested-schema]}]
  {:method "elicitation/create"
   :params {:mode "form"
            :message message
            :requested-schema requested-schema}})

(defn elicit-url-request
  "Builds a URL-mode `elicitation/create` input request.

   2026-07-28 removed the `elicitation-id` field and the
   `notifications/elicitation/complete` notification that went with it. The
   client reports the outcome by retrying the original request, so anything
   needed to correlate the interaction belongs in :request-state.

   No `elicitation-id`. 2025-11-25 required one, and 2026-07-28 removed it
   along with the completion notification that used it: this revision
   correlates through the multi round-trip key instead. Checked against both
   specification sources rather than inferred, because
   `schema/UrlElicitationRequest` is the 2025-11-25 shape and does require it,
   which makes the two look like they disagree. They do not. They describe
   different revisions, and validating a 2026-07-28 request against the
   2025-11-25 schema is the mistake.

   Args:
     params - Map of:
              :message - Prompt shown to the user
              :url     - URL the user is sent to

   Returns:
     An input request map."
  [{:keys [message url]}]
  {:method "elicitation/create"
   :params {:mode "url"
            :message message
            :url url}})

(defn missing-client-capability-response
  "Builds the 2026-07-28 error returned when a handler needs a client
   capability the request did not declare.

   Args:
     id                    - The request id
     required-capabilities - The ClientCapabilities shape that was needed

   Returns:
     A full JSON-RPC error response."
  [id required-capabilities]
  {:jsonrpc "2.0"
   :id id
   :error {:code protocol/missing-required-client-capability-code
           :message "Missing required client capability"
           :data {:required-capabilities required-capabilities}}})
