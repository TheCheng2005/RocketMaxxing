## What changes for users

<!-- One or two sentences a team member would understand: what they can now ask, or what now works. -->

## How it was checked

<!-- Tests added or changed; for physics, the reference you validated against (hand calculation, paper, RASAero,
     flight data). "Ran ./gradlew test and scripts/benchmark.py" is a good start. -->

## Checklist

- [ ] `./gradlew test` passes
- [ ] `python3 scripts/benchmark.py` passes (for changes to tools)
- [ ] New or changed physics has a test against an independent reference
- [ ] `docs/REFERENCE.md` (and `docs/ROCKETMAXXING.md`, if users of the app see it) updated
- [ ] `python3 scripts/make_examples.py` re-run if the change affects the examples page
