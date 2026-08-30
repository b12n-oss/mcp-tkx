(ns ^:no-doc mcp-toolkit.impl.client.handler-2026
  "Client request handling for protocol revision 2026-07-28.

   The table is much smaller than the handshake one, because the server no
   longer initiates requests. Sampling, roots and elicitation used to arrive
   here as inbound calls. They now come back as `input_required` results on
   the client's own requests, and `mcp-toolkit.client` answers them there.

   What is left is notifications. Progress and log messages are
   request-scoped and arrive on the response stream of the request they
   belong to. The list-changed notifications need `subscriptions/listen`,
   which this library does not implement yet, so they are routed but will not
   currently arrive."
  (:require
   [mcp-toolkit.impl.common :refer [user-callback]]))

(def handler-by-method
  "The dispatch table for 2026-07-28.

   No ping, since it went away with the session. No roots/list and no
   sampling/createMessage, since the server cannot initiate a request any
   more."
  {"notifications/subscriptions/acknowledged" (user-callback :on-subscription-acknowledged)
   "notifications/progress" (user-callback :on-server-progress)
   "notifications/message" (user-callback :on-server-log)
   "notifications/prompts/list_changed" (user-callback :on-server-prompt-list-changed)
   "notifications/resources/updated" (user-callback :on-server-resource-changed)
   "notifications/resources/list_changed" (user-callback :on-server-resource-list-changed)
   "notifications/tools/list_changed" (user-callback :on-server-tool-list-changed)})
