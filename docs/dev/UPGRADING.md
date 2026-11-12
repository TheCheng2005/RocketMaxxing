# Upgrading: versions, dependencies and what to check

Where each version lives, and what to look at when one is raised. After any upgrade run the checks at the end.

## Where versions live

| What | Where | Notes |
|---|---|---|
| RocketMaxxing (the app) | `appVersion` in `gradle.properties`, and `version` in `app/package.json` | `VersionsTest` fails when the two differ. The release tag is `v<appVersion>` (`v1.5` for 1.5.0); the release workflow checks it. |
| The engine / MCP server | `version` in `gradle.properties` | Shown by `openrocket-mcp --version` and in the Claude Desktop extension. |
| Every Java library | `gradle/libs.versions.toml` | `build.gradle` uses them as `libs.<name>`. |
| The interface's packages | `app/package.json` (exact versions) and `app/package-lock.json` | Change with `npm install <package>@<version>` in `app/`, so the lockfile follows. |
| Gradle | `gradle/wrapper/gradle-wrapper.properties` | `./gradlew wrapper --gradle-version <v>`. |
| Java | `options.release` in `build.gradle` (the language level, 17) and `java-version` in `.github/workflows/*.yml` (what CI builds with) | The installers carry the Java that built them. |
| Node (CI) | `node-version` in `.github/workflows/*.yml` | Only builds the interface. |

In the code, versions are read, never typed: `Versions.ENGINE`, `Versions.APP` and `Versions.openRocket()` (the
OpenRocket library's own answer). Nothing else should spell a version out; the documents' "OpenRocket 24.12" are text
to update by hand (search for it) and by re-running `scripts/make_examples.py` and `scripts/make_worked_example.py`.

Dependabot (`.github/dependabot.yml`) opens one pull request a month per area (Java libraries, interface packages,
GitHub Actions). OpenRocket and the window (JCEF) are left out of it: raise those by hand with the steps below.

## OpenRocket

The engine leans on OpenRocket's internals in a few places, so a new OpenRocket is the upgrade to take most care over.

1. Raise `openrocket` in `gradle/libs.versions.toml`, then `./gradlew test`.
2. **Plugins.** `or/KnownPlugins.java` lists OpenRocket's plugins so the app need not scan every jar at start.
   `KnownPluginsTest` runs the real scan and compares: add or remove what it reports. (With a class missing, the
   engine falls back to the scan: slower to start, still right.)
3. **Private fields read by reflection.** A renamed field shows as a failing test or a logged fallback, not a compile
   error. They are:
   - `or/FastSim.java`: `Simulation.simulationEngineClass` and the steppers of `BasicEventSimulationEngine`
     (the caching that makes flights about three times faster; `FastSimTest`, `RepeatableTest` and `ReuseTest` compare
     it with OpenRocket's own engine to the last digit);
   - `or/Variants.java`: `SimulationOptions.averageWindModel`;
   - `or/Winds.java`: `MultiLevelPinkNoiseWindModel.LevelWindModel.model`;
   - `or/Sims.java`: `Simulation.document`;
   - `or/Components.java`: component classes by name (`info.openrocket.core.rocketcomponent.*`).
4. **Behaviour the engine corrects or relies on.** Comments that name the OpenRocket version say what was true of
   it: search the sources for the old version number (for example `or/RailButtons.java` on rail length and launch
   lugs, `or/Winds.java` on the wind getters) and check each still holds.
5. **Numbers.** `python3 scripts/benchmark.py` checks realistic requests against engineering expectations, and
   `python3 scripts/make_examples.py` and `scripts/make_worked_example.py` regenerate the example pages: read the
   diff of `docs/EXAMPLES.md` and `docs/WORKED_EXAMPLE.md` for numbers that moved, and say why in the pull request.
6. `.ork` files: open and save the designs in `docs/examples/` and one from an older OpenRocket (`DesignImportTest`).

## The window: JCEF and Chromium

`jcefmaven` (the Java side) and `jcefNatives` (the Chromium build) in `gradle/libs.versions.toml` move together: the
natives version is the `jcef-api` version in that jcefmaven release's pom. `app/Chromium.java` checks the unpacked
bundle against the Java side before starting it and says what is wrong when they differ.

After raising them: `./gradlew rocketmaxxing`, then `xvfb-run -a python3 scripts/test_rocketmaxxing.py --window`
(Linux), and the *RocketMaxxing installers* workflow, which starts each packaged app in its window on every
platform. Check by hand on a Mac: the menus, Quit with unsaved changes, and opening a design from Finder.
`app/Signals.java` is the one use of `sun.misc.Signal` (no public replacement exists); the compiler's warning about it
is expected.

## The interface

- **three.js**: `three` and `@types/three` are raised together. The 3-D view (`app/src/lib/design/RocketView.svelte`,
  `viewRenderer.ts`, `meshes.ts`) uses the renderer, PMREM lighting, OrbitControls and clipping planes; after an
  upgrade look at the side, 3-D, cut-away and exploded views in both colour themes.
- **Svelte**, **Vite**, **TypeScript**: `npm run check` (types) and `npm run build` in `app/`, then
  `python3 scripts/test_rocketmaxxing.py`.
- **uPlot** (plots) and **@lucide/svelte** (icons): look at the Flights page's plot and the icons in the rail.

## Other Java libraries

- **JCodec** (the MP4 made without ffmpeg): `report/Mp4Encoder.java` encodes groups of frames side by side.
  `FlightAnimationTest.parallelMp4IsTheSameFileAsTheSequentialOne` compares it byte for byte with JCodec's own
  sequential encoder; if a new JCodec changes its key-frame interval, set `Mp4Encoder.GROUP` to match.
- **Anthropic SDK** (the optional assistant): the model ids are listed in `app/Assistant.java`; `AssistantTest` runs
  the assistant against a stand-in for the API.
- **Batik** (SVG to PNG), **Gson**, **Guice** (OpenRocket's injector; keep the major version OpenRocket uses):
  the tests cover them.
- **Java itself**: the extension and the installers carry a trimmed runtime. A library that needs another Java
  module fails at start in the packaged app only, so add the module to `runtimeModules` in `build.gradle` and let
  the *installers* workflow and `scripts/test_mcpb.py` confirm it.

`./gradlew thirdPartyNotices` rewrites the licence notices from whatever the build now contains; a new library's
licence appears there by itself. Read the notices once after adding a library with an unusual licence.

## Checks after any upgrade

```sh
./gradlew test                                    # unit and end-to-end tests
python3 scripts/benchmark.py                      # realistic requests against engineering expectations
(cd app && npm run check && npm run build)        # the interface
./gradlew rocketmaxxing && python3 scripts/test_rocketmaxxing.py     # the app, started and used
```

CI runs these on Linux, Windows and macOS, and the installers workflow builds and starts the packaged app on each.

## Where the app's engine-side code is

`src/main/java/io/github/openrocketmcp/app/`, one class per concern:

| Class | What |
|---|---|
| `App`, `AppWindow`, `Chromium`, `MacDesktop`, `Signals`, `Instance` | Starting up, the window, one running app per designs folder |
| `AppServer` | The local server the window talks to |
| `AppTools` | Registers the app-only tools (`app_*`): the session, files, preferences, updates, the assistant |
| `AppDesign`, `AppMotors`, `AppFlights`, `AppTeam` | The tools behind the Design, Motors, Flights and the Standards / Targets / Flight data pages |
| `Editors` | A part's editable settings |
| `Assistant`, `AgentCli` | The optional assistant (API key, Claude Code, Codex) |
| `UiPrefs`, `Updates`, `Downloads`, `FileDialogs` | Preferences kept between launches, the update check, where downloads go, file dialogs |
