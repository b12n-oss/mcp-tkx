#!/bin/sh
# Wrapper for the demo capture. Two things it exists for.
#
# osascript's `do script` opens a NEW shell in the user's home directory, so
# the manifest's :cwd never reaches it and a bare `bb bb:smoke` fails with
# "File does not exist: bb:smoke": babashka falls back to treating a task
# name as a script path when no bb.edn is in scope. So self-locate, which
# also means a clone anywhere works.
#
# And the pauses. screen-grab starts recording once the window appears, and
# the smoke test finishes in a couple of seconds, so without a lead-in the
# GIF would open on an already-finished prompt. Lead in, run, then hold the
# result on screen for the rest of the capture.
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)" || exit 1
clear
sleep 3
bb bb:smoke
sleep 9
