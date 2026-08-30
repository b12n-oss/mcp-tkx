(ns mcp-toolkit.protocol
  "Protocol version constants and the wire vocabulary shared by the client
   and the server.

   Two protocol families are supported. The 2025 family negotiates a version
   once, during `initialize`, and keeps it on the session. The 2026-07-28
   family is stateless: every request carries its own protocol version and
   client capabilities inside `_meta`, and there is no handshake at all."
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.string :as str]))

(def latest-protocol-version
  "The newest protocol revision this library implements."
  "2026-07-28")

(def supported-protocol-versions
  "The revisions that carry a `_meta` protocol-version field.

   This is NOT the list of revisions the library speaks, and nothing in
   `src/` reads it. A session's own supported list is built by
   `mcp-toolkit.server/create-session`, which picks one of three lists
   depending on whether the session is plain, stateless or dual-era. That
   is the list `initialize` negotiates against. In particular `2024-11-05`
   is absent here but is spoken by every handshake session."
  ["2025-03-26" "2025-06-18" "2025-11-25" "2026-07-28"])

(def stateless-protocol-versions
  "Revisions that have no initialize handshake and no protocol-level session."
  #{"2026-07-28"})

(defn stateless?
  "Returns true when the given protocol version drops the handshake and the
   session, which is what 2026-07-28 did.

   Args:
     protocol-version - A protocol version string

   Returns:
     true for a stateless revision, false otherwise."
  [protocol-version]
  (contains? stateless-protocol-versions protocol-version))

;; ---------------------------------------------------------------------------
;; _meta keys
;;
;; These are namespaced strings on the wire and they stay strings inside the
;; library too. They must NOT become keywords: camel-snake-kebab drops the
;; namespace when it re-encodes a namespaced keyword, so
;; :io.modelcontextprotocol/protocol-version would go out as "protocolVersion"
;; and no client would recognise it. See `mcp-toolkit.impl.mrtr` for the same
;; rule applied to multi round-trip correlation keys.
;; ---------------------------------------------------------------------------

(def meta-protocol-version "io.modelcontextprotocol/protocolVersion")
(def meta-client-capabilities "io.modelcontextprotocol/clientCapabilities")
(def meta-client-info "io.modelcontextprotocol/clientInfo")
(def meta-log-level "io.modelcontextprotocol/logLevel")
(def meta-server-info "io.modelcontextprotocol/serverInfo")
(def meta-subscription-id "io.modelcontextprotocol/subscriptionId")

(defn- key->string
  "Renders a key as its full wire string.

   `name` alone is not enough: it drops the namespace of a namespaced keyword,
   which is exactly the data these functions exist to protect."
  [k]
  (if (and (keyword? k) (some? (namespace k)))
    (str (namespace k) "/" (name k))
    (name k)))

(defn opaque-wire-key?
  "Returns true when a wire key must survive the JSON boundary verbatim
   rather than being converted to a kebab-case keyword.

   Two kinds of key qualify. Anything starting with an underscore is a
   protocol-defined field such as `_meta`. Anything containing a slash is a
   namespaced extension key, which covers every 2026-07-28 `_meta` key and
   every multi round-trip correlation key.

   Transports need this. A transport that converts these keys silently
   corrupts them, and the failure is invisible until a client ignores a
   field it should have understood.

   Args:
     k - A key, as a string or as something `name` accepts

   Returns:
     true when the key must be preserved verbatim."
  [k]
  (let [s (key->string k)]
    (or (str/starts-with? s "_")
        (str/includes? s "/"))))

;; ---------------------------------------------------------------------------
;; Error codes
;;
;; 2026-07-28 partitions the JSON-RPC server-error range. -32000 to -32019 is
;; implementation-defined, and -32020 to -32099 belongs to the specification.
;; The three MCP codes were renumbered into that block when it was defined.
;; ---------------------------------------------------------------------------

(def header-mismatch-code -32020)
(def missing-required-client-capability-code -32021)
(def unsupported-protocol-version-code -32022)

(def invalid-params-code
  "2026-07-28 reports a missing resource as Invalid Params, aligning with
   JSON-RPC. Earlier revisions used the MCP-specific -32002."
  -32602)

(def legacy-resource-not-found-code
  "The code earlier revisions use for a missing resource."
  -32002)

;; ---------------------------------------------------------------------------
;; JSON key conversion
;;
;; MCP is camelCase on the wire and this library is kebab-case inside, so a
;; transport has to convert in both directions. Doing that with a plain
;; camel-snake-kebab call is wrong in two ways that fail silently, which is
;; why these live here rather than in each transport.
;; ---------------------------------------------------------------------------

(defn decode-key
  "Converts one inbound JSON key.

   Ordinary protocol fields become kebab-case keywords. Two kinds of key do
   not. A leading underscore marks a protocol-defined field, so `_meta`
   becomes :_meta rather than losing its underscore. A key containing a slash
   is a namespaced extension key and stays a string, because turning it into a
   keyword would lose the namespace on the way back out.

   Args:
     k - The key as it appeared in the JSON

   Returns:
     A keyword for ordinary and underscore-prefixed keys, the original string
     for namespaced ones."
  [k]
  (let [s (key->string k)]
    (cond
      (str/starts-with? s "_") (keyword s)
      (str/includes? s "/")    s
      :else (csk/->kebab-case-keyword s))))

(defn encode-key
  "Converts one outbound key to its JSON form.

   Args:
     k - A keyword or string key

   Returns:
     The camelCase string for ordinary keys, and the key verbatim for
     underscore-prefixed and namespaced ones."
  [k]
  (let [s (key->string k)]
    (if (opaque-wire-key? s)
      s
      (csk/->camelCaseString k))))
