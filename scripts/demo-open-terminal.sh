#!/bin/sh
# Opens a terminal window running the smoke demo, for screen-grab to capture.
#
# iTerm2 rather than Terminal, and that is the whole point. cgevent's --app
# resolves to a pid, and a pid that owns several windows leaves which one
# gets captured up to ordering: with four Terminal windows open, the capture
# came back showing an unrelated one at a "Last login" prompt. Using a
# terminal that is not the operator's daily driver keeps exactly one window
# in play, so the target is unambiguous.
#
# Deliberately NO resize here, though an earlier version did it. gifski
# rejects a recording whose frames differ in size, and screen-grab starts
# capturing the moment the window appears -- so resizing afterwards is
# itself the thing that breaks it ("Frame 3 has wrong size"). Leave the
# window at whatever size it opens, and just do not let anything retile it
# mid-capture. If a window manager moves it, that is what breaks a run.
here="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
osascript \
  -e 'tell application "iTerm2" to create window with default profile' \
  -e "tell application \"iTerm2\" to tell current session of current window to write text \"$here/demo-bb-smoke.sh\"" \
  -e 'tell application "iTerm2" to activate' \
  >/dev/null
