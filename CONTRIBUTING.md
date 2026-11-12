# Contributing

Thanks for helping. Rocketry teams rely on these numbers, so the bar is simple: **every change is tested, and every
physics change is checked against an independent reference.**

## Ways to help without writing code

- **Report a result that looks wrong** ([template](https://github.com/TheCheng2005/RocketMaxxing/issues/new?template=wrong-result.yml)):
  a number that disagrees with your hand calculation, RASAero, a ground test or a flight log. These are the most
  valuable reports we get.
- **Send flight data.** An altimeter log plus the `.ork` you flew lets us measure how close the predictions are. Remove
  anything private first.
- **Add or check a rule set.** Rule sets are JSON in `src/main/resources/openrocketmcp/rules/`; see
  `launch-canada-2027.json`. Every limit carries its rule reference.
- **Share team standards** (pin strengths from your shear tests, measured fin material stiffness, packing factors)
  with their source.

## Building and testing

You need Java 17 or newer. [CalculiX](http://www.calculix.de) (`ccx`) is optional: with it, the fin FEA tests also run
the model against plate theory (`apt install calculix-ccx`; CI installs it).

```sh
./gradlew test                      # unit and end-to-end tests (~2 min)
./gradlew installDist               # build/install/openrocket-mcp/bin/openrocket-mcp
python3 scripts/benchmark.py        # realistic team requests, checked against engineering expectations
python3 scripts/make_examples.py    # rebuild docs/EXAMPLES.md and its plots
python3 scripts/make_worked_example.py  # rebuild docs/WORKED_EXAMPLE.md (a real flight) and docs/examples/ndrt-2020
node scripts/view_shots.mjs docs/examples/maple-10k-view.html docs/examples   # check the interactive view in a
                                    # headless browser and retake its screenshots (needs Playwright with Chromium)
python3 scripts/test_codex.py       # the real Codex CLI against the server (needs `npm i -g @openai/codex`)
./gradlew mcpb && python3 scripts/test_mcpb.py   # Claude Desktop extension for your computer, started like Claude Desktop does
./gradlew rocketmaxxing             # the desktop app (needs Node.js 20+ for its interface; downloads Chromium once)
python3 scripts/test_rocketmaxxing.py build/install/openrocket-mcp/bin/rocketmaxxing   # start the app and use it
(cd app && npm run check)           # type-check the app's interface
```

The desktop app's own guide, including how to run its interface with live reload, is
[docs/ROCKETMAXXING.md](docs/ROCKETMAXXING.md#development).

CI runs the tests on Linux, Windows and macOS, and the benchmark, examples, Codex check and extension builds as well.
The *RocketMaxxing installers* workflow builds the desktop app's installers on all three, starting each packaged app
and using it first.

To release the desktop app: set the new version as `appVersion` in `gradle.properties` (and in `app/package.json`; a
test fails when the two differ), merge to `main`, then make a release tagged after it, on the Releases page or with:

```sh
git tag v1.6 && git push origin v1.6
```

The *RocketMaxxing release* workflow checks the tag matches `appVersion` (`v1.6` or `v1.6.0` for 1.6.0), builds and
checks the installers on every platform, and attaches them to the release; CI attaches the Claude Desktop extension
(`.mcpb`) to the same one. A release made by pushing the tag also gets the download table and the changes since the
last release (0.x versions as pre-releases). To add the installers to a release that already exists, run the workflow
by hand (Actions > RocketMaxxing release > Run workflow) with its tag. `./gradlew thirdPartyNotices` writes the licence notices the installers carry
(`build/notices/THIRD-PARTY-NOTICES.txt`); the build includes them by itself. Installed copies find the new release by
themselves (`app/Updates.java` reads the Releases page once a day and picks the asset whose name ends in the platform
suffix `release.yml` gives it, so keep those names); `-Drocketmaxxing.updates.url=...` points the check elsewhere for
testing.

Raising a dependency, OpenRocket or the app's version: [docs/dev/UPGRADING.md](docs/dev/UPGRADING.md) says where each
version lives and what to check.

## How the code is organised

| Folder | What lives there |
|---|---|
| `mcp/` | The MCP protocol (stdio and HTTP), argument parsing, schemas |
| `tools/` | One file per area; each tool is a `ToolDef` with a description written for the model |
| `or/` | Everything that talks to OpenRocket: simulations, analyses, optimizer, dispersion |
| `calc/` | Pure calculations (recovery, charges, flutter, atmosphere) with no OpenRocket dependency |
| `report/` | Reports, flight card, SVG plots and drawings, the 3-D renderer, and the interactive view's data (`Workbench`) |
| `src/main/resources/ui/` | The interactive view (`workbench.html`): one self-contained page, plain JavaScript, WebGL and SVG, no external resources |
| `standards/` | Team standards and rule sets |
| `units/` | Unit parsing and display (inputs accept "4 in", output follows the team setting) |
| `app/` (Java) | RocketMaxxing's launcher, local server, app-only tools (`app_*`), part editors and the assistant |
| `app/` (repository root) | RocketMaxxing's interface: Svelte 5, TypeScript, three.js |

## Adding or changing a tool

1. Write the tool in the matching `tools/*Tools.java`. The description tells the model **when** to use it and **what** it
   returns; inputs accept units (`Schema.qty`) and outputs use `Units.fmt`.
2. Keep what-if tools read-only: work on copies (`Variants.of`) and leave the design alone unless the user asks to apply.
3. Give new argument names a physical range in `mcp/Limits.java` (a mass cannot be negative, a Cd must be above zero).
   `Args` applies it wherever the argument is read, and the server checks it before the tool runs. Throw
   `ToolException` with a sentence that says what to change; any other exception reaches the user as a server bug.
4. Make it discoverable: add it to `tools/Suggestions.java`, worded as a team member would ask (`FEATURES`), and after
   the tools it naturally follows (`NEXT`). Results then offer it to teams that have not used it yet. `SuggestionsTest`
   fails for a tool that is in neither the catalog nor its list of plumbing tools.
5. Test it: calculations in `src/test/java/.../calc`, OpenRocket-backed behaviour in `.../or`, and a scenario in
   `scripts/benchmark.py` if a team would use it in a workflow.
6. Document it: a bullet under its area in `docs/REFERENCE.md`, and, if users will notice it, a line in
   `docs/ROCKETMAXXING.md` (the Analysis row of its Pages table) and in `docs/MCP.md`'s "What you can ask".

The interactive view talks MCP Apps (`ui/*` messages over `postMessage`) and must work with no network: no CDN, no
external fonts. Insert names and other text with `textContent`, never as HTML. After changing it, run
`scripts/view_shots.mjs` (no script errors in any tab) and look at the screenshots in both themes.
   The Claude Desktop manifest picks up new tools automatically, and `undo` records any change a tool makes to a
   design without extra code.

## Physics and engineering changes

- Cite the method (paper, report, standard) in the code comment and in `docs/REFERENCE.md` under Methods.
- Add a test against something independent: a worked example from the reference, a hand calculation, a limiting case,
  a scaling law, or an equilibrium / conservation check (see `PhysicsInvariantsTest` and `FlutterTest`).
- Say what the method does **not** cover; the tools report their assumptions to the user.

## Style

Match the surrounding code: tabs, short methods, comments that explain *why*. User-facing text is plain language, the
way you would explain it to a new team member.

- Imports at the top, in groups: `java.*`, then `com.*` / `org.*`, then `info.openrocket` and `io.github` together.
  No fully-qualified class names in the code.
- `Locale.ROOT` for `String.format`, `toUpperCase` and `toLowerCase`; numbers shown to users go through `Units`.
- Escape any name written into SVG, KML or HTML with `Xml.esc`.
- New files a tool writes go through the workspace sandbox (`ctx.path`).

## The skill files

The design-review skill ships twice: `.claude/skills/rocket-design-review` (Claude) and `.agents/skills/rocket-design-review`
(Codex and other agents). Edit the first and copy it to the second; `SkillsTest` checks they match.

## Security

Please report security problems (for example with the team server's token or file sandbox) privately through
[GitHub security advisories](https://github.com/TheCheng2005/RocketMaxxing/security/advisories/new), not in a public issue.

## License

By contributing you agree that your contribution is licensed under the GPL-3.0-or-later, like the rest of the project
and OpenRocket.
