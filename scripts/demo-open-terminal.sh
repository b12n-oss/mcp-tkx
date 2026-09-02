#!/bin/sh
# Opens a Terminal window running the smoke demo, for screen-grab to capture.
# Self-locating so the manifest's :run stays a plain relative command and
# avoids escaping a path through EDN, then sh, then AppleScript.
here="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
osascript -e "tell application \"Terminal\" to do script \"$here/demo-bb-smoke.sh\"" \
          -e 'tell application "Terminal" to activate' >/dev/null
