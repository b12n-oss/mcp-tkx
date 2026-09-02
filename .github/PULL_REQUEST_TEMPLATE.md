## What this changes, and why

<!-- The diff already shows what. Explain why it should exist. -->

## Checks

- [ ] `bb ci` passes locally. There is no CI, so this is the gate.
- [ ] The suite is still at **236 tests, 0 failures**, or the count moved
      and I say below why.
- [ ] A regression fix has a test that fails without the fix, and I
      checked it fails by reverting the fix rather than assuming.
- [ ] `bb docs:links` and `bb docs:blocks` pass, if I touched docs.
- [ ] No em-dashes in prose, per the house style in `CONTRIBUTING.md`.

## Anything you had to work around

<!-- Optional, and the most useful box here. A trap you hit, a thing that
     looked wrong but was deliberate, a claim in the docs that turned out
     not to hold. -->
