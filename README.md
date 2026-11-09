# RocketMaxxing

Rocket design and engineering checks for student competition teams, on [OpenRocket](https://openrocket.info)'s own
physics. Two ways to use it:

- **[RocketMaxxing, the desktop app](docs/ROCKETMAXXING.md)** (Mac, Windows, Linux): design the rocket in 3-D, see the
  competition rules re-checked after every edit, and run 50 engineering analyses (recovery loads, fin flutter, shear
  pins, ejection charges, parachute sizing and more) without writing code or keeping spreadsheets.
- **OpenRocket MCP, for AI assistants:** talk to Claude (or Codex) about your rocket, and it answers with real
  OpenRocket simulations. The rest of this page is about this part.

Built for student competition teams and amateur rocketeers, and set up for **Launch Canada 2027** out of the box. Ask
in plain words, in feet or metres; Claude runs the numbers, checks them against the competition rules and your team's
standards, and explains what it found.

```
You:    Our main needs to land under 20 ft/s. Which chute, and will four 4-40 shear pins hold when the drogue opens?
Claude: A 36 in chute lands the 2.84 lb section at 19.9 ft/s. The drogue opens at 15 ft/s with a 12.6 N load,
        so four pins hold with plenty of margin — even if the drogue fires up to 4 s late.
```

**Contents:** [See it in action](#see-it-in-action) · [What you can ask](#what-you-can-ask) ·
[Getting started](#getting-started) · [Make it your team's](#make-it-your-teams) ·
[RocketMaxxing, the desktop app](#rocketmaxxing-the-desktop-app) · [Good to know](#good-to-know)

## See it in action

[**Examples**](docs/EXAMPLES.md) follows one 10,000 ft rocket from a blank page to launch day: the prompts a team types,
the answers, and the plots. Everything on that page is real output.

[**Worked example: a real flight**](docs/WORKED_EXAMPLE.md) rebuilds the Notre Dame Rocket Team's 2020 L1395 rocket
from its published dimensions and weigh-in, runs the checks a team does before launch day in the day's real weather,
and compares the simulation with the altimeter and accelerometer log of its flight. Stock OpenRocket came out 9% high;
the accelerometer shows why (the motor delivered about 9% less than its curve, and drag was close), and with that the
simulation follows the measured climb to within 5 m. A pattern to follow for your own rocket.

Say *"show us the rocket"* and an interactive view opens right in the chat: turn the rocket in 3-D, cut it open or pull
it apart, click a part to see and change it, scrub through the flight and watch CG and CP move, fly it like the
flight video (chase camera, flame and smoke, the parachutes opening, a dropped booster on its own camera) and render
the video from there, and read every plot with one time cursor. Outside Claude Desktop and claude.ai it is an HTML file:
[try the Maple 10K one](docs/examples/maple-10k-view.html) (download it and open it in a browser).

<p>
<img src="docs/examples/view-rocket.png" alt="The interactive design view: exploded cut-away 3-D model with the main parachute selected and its properties" width="49%">
<img src="docs/examples/view-flight.png" alt="Linked flight plots with one time cursor and the events marked" width="49%">
</p>
<p>
<img src="docs/examples/flight.gif" alt="3-D animation of the simulated flight: liftoff, burnout, apogee, drogue and main, touchdown" width="100%">
</p>
<p>
<img src="docs/examples/exploded.png" alt="Exploded 3-D view with every part and its mass" width="48%">
<img src="docs/examples/landing.svg" alt="200 simulated landings around the pad with the 2-sigma ellipse" width="48%">
</p>

## What you can ask

**Working with Claude**
- "Show us the rocket" — the interactive view above, in the chat: 3-D with a cut-away that shows the walls in section
  and an exploded view that lays every part out with its name, a side view to scale with CG, CP and a ruler, a
  metric / imperial switch, the flight replayed like the video (and *Make video* to render it), linked flight plots, the trajectory and
  ground track, and the rule checks. Click a part to change it (re-simulated at once, and undoable), or ask Claude about
  it or how to fix a failing check.
- "Where do we stand?" — the rule check in short, what is not set up yet, and the next steps in order with the tool for
  each. A good first question in any session.
- "What can you do?" — or nothing at all: Claude offers related things as you work ("I can also plot descent rate
  against parachute size: want that?"), once each and only when they fit. In Claude Desktop, the `what_can_you_do`
  prompt gives a tour fitted to your design. Teams that prefer quiet can set `"suggestions": false` in their standards.
- Plots and drawings appear right in the chat (Claude Desktop, claude.ai), and are saved as files too.
- Long runs (optimizers, Monte Carlo, motor ranking) show their progress, and you can stop them.
- "Undo that" — every change to a design in the session can be undone and redone; `history` lists them. Nothing is
  written to your `.ork` until you ask Claude to save.

**Design and stability**
- Build a rocket from scratch, or open your team's `.ork` file and change it. Older designs work too: `.ork` files
  from any OpenRocket version and RockSim `.rkt` files, with a list of anything that didn't carry over.
- "Is it stable all the way up, including in 30 km/h wind?" — checked at every moment of the flight, for every stage.
- "How much nose weight do I need?" or "How big should the fins be?" — the smallest fins that meet the stability rules
  without fluttering.
- "Optimize our fin shape" — root chord, tip chord, span and sweep searched together (and the thickness from the sheets
  you can buy) for the least drag or the lightest fins, meeting the stability rules and staying clear of flutter. Only
  buildable shapes are tried.
- "Which nose cone and fin shape should we build?" — every common shape flown and compared.
- Pick real parts from OpenRocket's catalogue (tubes, nose cones, couplers, rail buttons, parachutes).
- "Here's our mass budget" or "here are today's weigh-ins": Claude compares every part with the model, lists the parts
  your budget forgets, and projects the launch mass against your target, with contingency. When you say so, it writes
  the weighed numbers into the design so every simulation flies the rocket as built (and `undo` takes them back). No
  budget yet? Claude writes the model's breakdown as a spreadsheet to fill in.
- "Lay out the avionics bay": two independent altimeters, each with its own battery and switch, a GPS tracker, the
  ejection charges and static ports, placed by the Launch Canada electronics rules, with the parachutes packed against
  it and a warning if a bay is too short.
- "Make test data for our flight computer" — what its accelerometers, gyro, barometer and GPS would log on the
  simulated flight, with noise and range limits, plus the true event times to score your launch, burnout, apogee
  and main detection against.
- "Will our sensors cope?" — every sensor's range against the flight. Typical findings: a ±16 g accelerometer that
  clips at motor start, a barometer that runs out of range at apogee, a GPS that loses lock above 515 m/s. It also
  says when the barometer can't be trusted near Mach 1.
- "What do we set the altimeters to?" — primary and backup settings for every parachute, the Mach lockout and the
  static port size, as a one-page card with a pre-flight checklist.
- "Do our batteries last a two-hour pad wait?" — every battery circuit's capacity against the pad wait, the flight
  and the search, plus the current each e-match gets and how far the voltage sags while firing.
- "Will we hear the tracker all the way down?" — the radio link margin at the farthest point of the flight and after
  landing, where antennas near the ground lose signal much faster, with the range the team can still hear it at.
  Electronics problems also show up on the flight card, in `design_status` and in the review report.
- Get a cut-away drawing: parachutes, shock cords, electronics, batteries, charges, motor, rings and bulkheads where
  they really sit, where the airframe separates, and the CG and CP.
- "Show me an exploded view" — the rocket in 3-D, pulled apart, with every part inside laid out where it goes and a
  numbered parts list with masses. On a two-stage rocket each stage is bracketed with its mass. Also available as a
  cut-away or assembled view.

**Motors**
- "Find a motor for 10,000 ft on an L2 certification" — candidates are flown, not just filtered.
- Use your own liquid or hybrid engine, or data from a static fire.

**Flight**
- Apogee, speed, Mach number, rail exit speed, thrust-to-weight, staging and landing distance.
- "Where will it land?" — up to a thousand possible flights with varied wind, launch angle, mass, drag and thrust,
  drawn on a landing map.
- A landing map for Google Earth: the pad, every simulated landing and the landing ellipse on the real terrain.
- Use a wind forecast with winds at altitude, not just the ground wind.
- "Where does our drag come from?" — drag of every part, and how it changes with speed.
- "Is the sustainer still stable when it goes supersonic on its own?" — the sustainer alone after separation: its
  margin through Mach and at its own top speed, beside your RASAero run of it.
- "Animate the flight" — a short 3-D video of the simulated flight. It shows the flame and smoke, both parachutes
  opening, and a live readout of time, altitude, speed, Mach and distance from the pad. Captions call out burnout,
  apogee and each deployment. You get an MP4 for slides and sharing, a GIF for chat, and stills of the key moments.
- Two-stage rockets, end to end:
  - staging is simulated: booster burnout, separation and sustainer ignition;
  - the air-start rules are checked: tilt and altitude at ignition, and thrust-to-weight per stage;
  - each stage's recovery and landing zone is covered;
  - the animation names each stage's events, and a booster camera follows the booster down under its own chute.

**Recovery**
- Choose parachutes for a target descent rate.
- "Plot our main's size against descent rate for Cd 0.85, 1.5 and 2.2" — a chart of descent rate against canopy
  diameter for the drag coefficients you pick, with the rule limit and target, every catalog parachute at its own Cd,
  the size each Cd needs, and where your parachute sits now. It is calibrated to your simulation (the airframe and an
  attached drogue are counted), and "size it" sets the parachute and re-flies the rocket.
- "Here's the link to the chute we want" — paste a product link (or the page's text) and the part goes into the
  design: a parachute with its diameter, Cd, weight and packed size (gaps filled from the matching catalog
  parachute), a motor (also new ones, from ThrustCurve.org), a catalog tube or nose cone by part number, or an
  altimeter or tracker by its weight. Every value says where it came from, and the rocket is re-flown.
- Opening shock loads, shear pin count, black powder charge size, and whether it all fits in the bay.
- "How late can the drogue fire before the pins break?"
- Landing energy of every piece of the rocket, and what happens if the main fails.

**Structures**
- "Will our fins flutter?" — and how thick they need to be if they will.
- "Should our fins, airframe or nose cone be fiberglass, carbon or aluminum?" — each material (and stock thickness)
  flown on the rocket: mass, apogee and stability, flutter, wall strength, shell buckling and heating, side by side.
- "Will it get too hot?" — nose tip, fin edges and body temperatures at speed against your materials' limits.
- "How straight do the fins need to be?" — roll rate from misaligned fins, and whether it risks a roll-pitch resonance.
- Loads at every airframe joint during boost and at maximum speed, to size couplers and fasteners (also as a CSV for
  your FEA).
- "Check the fins in FEA" — a CalculiX finite-element model of the fin under its worst flight load: deflection, stress
  margin and natural frequencies (bending and torsion). CalculiX is free; without it you still get the model file.

**CFD, CAD and manufacturing**
- "Export it for CFD" — the rocket as STL (a region per part), and the list of cases to run taken from your flight
  (Mach, altitude, air, Reynolds number). Send the CFD drag and centre of pressure back and every simulation uses them.
- Fin cutting patterns as DXF for a waterjet, laser or CNC router.

**Competition and reviews**
- A full check against the Launch Canada rules, with the rule numbers and a checklist of what to verify by hand.
- A design-review package: report, stability plots and flight data, ready for your review board.
- "What changed since our last review?" — compare two versions of the design (another file, or an earlier commit)
  side by side: mass, stability, apogee, rule checks and every part that was added, removed or edited.
- Pressure vessel margins and probation level for liquid and hybrid programmes.
- Use RASAero data where Launch Canada asks for it, one run per stage stack on a two-stage rocket. It is saved with
  your design.
- Meeting notes and status updates checked against the design, and a record of progress from review to review (see
  *Project manager and chief engineer* below).

**Launch day**
- "What will the winds be at our site on Saturday at 10 am?" — give the launch site's GPS coordinates and Claude pulls
  the forecast, including winds up at altitude, and flies the rocket in it. No internet? Just tell Claude the winds.
- "Where should the rail buttons go?" — the aft button as low as the airframe allows (every cm above the aft end is
  rail the rocket never uses), and the forward one where tip-off and slop at rail exit are least. You get the real
  rail exit speed, button loads, and the gain over your current layout. Claude can move the buttons in the design.
- A one-page flight card: predictions, motor delay, deployment settings, drift in each wind, and sign-off lines.

**After you fly**
- Load your altimeter file: Claude compares it with the prediction and tunes the model for next time.

**Project manager and chief engineer**

Connect Claude to your team's Slack and Notion (or Google Drive) as well as this server, and it can follow the project,
not just the rocket:
- "Go through Tuesday's meeting notes" — Claude reads the page, lists the decisions, the action items (who, by when) and
  the open questions, and checks every number and choice in it against the design: "the notes say ~30k ft, the design
  flies to 31.7k ft"; "Slack says the fins are aluminium, the design still has balsa". New decisions are checked with
  the right analysis (flutter, stability, the rule check) before Claude offers to change the design.
- "What changed this week?" — a dated record of the design (headline numbers, rule failures, what is still to set up)
  kept next to your .ork. Each update says what improved, what got worse, what was fixed and what is new, since last
  week or since a milestone ("since PDR"), as a short post ready for your channel or Notion page.
- A weekly update on a schedule: a Claude scheduled task reads the channels and the notes, records the progress and
  drafts the update, every Monday morning. Claude drafts; it posts or edits only when you say so (or when your team
  agreed the scheduled update may post on its own).

See [docs/TEAM.md](docs/TEAM.md) for the set-up.

**Your team's history**
- Point Claude at the folder of past rockets and flight logs, and ask "our 4 in rockets on M motors", "which past
  rocket is closest to this one?" or "how did predicted apogee compare with actual over the years?". Each design is
  summarised (size, mass, motors, predicted apogee, materials, recovery), and each altimeter log is matched to its
  design. You get the prediction error for every flight, by year, and the overall bias.

## Getting started

Works with **Claude Desktop**, **claude.ai**, **Claude Code** and **Codex**.

**Claude Desktop (easiest).** Download the extension for your computer from the
[Releases page](https://github.com/TheCheng2005/RocketMaxxing/releases) — `win32` for Windows, `darwin-arm64` for
Apple-silicon Macs, `darwin-x64` for Intel Macs, `linux` for Linux — and double-click it. Claude Desktop asks for your
rocket folder (where your `.ork` files live) and you are done. Nothing else to install: Java and OpenRocket come
inside it. (If your team runs a team server, Claude Desktop can use that instead: see below.)

**The whole team, from claude.ai or Claude Desktop.** One person runs the team server on a computer or cloud machine everyone can reach:

```sh
docker build -t openrocket-mcp . && docker run -p 8765:8765 -v "$PWD/rockets:/workspace" openrocket-mcp
# or, with Java 17+:  ./gradlew installDist && build/install/openrocket-mcp/bin/openrocket-mcp --http --host 0.0.0.0
```

It prints a private link. Put it in claude.ai under Settings → Connectors → Add custom connector (the same connector
then shows up in Claude Desktop), and everyone on the team can use it. The server also prints a files page where people
drop their `.ork` files and download reports.

claude.ai needs an `https://` address: host it somewhere with a certificate, or put a tunnel such as
`cloudflared tunnel --url http://localhost:8765` in front of it. Keep the link private — anyone with it can use the
server.

**Claude Code, or building it yourself.** You need **Java 17 or newer** ([Temurin](https://adoptium.net)).

```sh
git clone https://github.com/thecheng2005/openrocket_mcp.git
cd openrocket_mcp
./gradlew installDist        # Windows: gradlew.bat installDist
```

Run `claude` inside the folder and it is ready. To use it from any folder:

```sh
claude mcp add openrocket -- /path/to/openrocket_mcp/scripts/openrocket-mcp
# Windows:
claude mcp add openrocket -- C:\path\to\openrocket_mcp\scripts\openrocket-mcp.cmd
# Or connect to your team server:
claude mcp add --transport http openrocket https://your-server/mcp/YOUR-TOKEN
```

`./gradlew mcpb` builds the Claude Desktop extension for your own computer.

**Codex (CLI, IDE extension or app).** Build it as above, then add this to `~/.codex/config.toml`:

```toml
[mcp_servers.openrocket]
command = "/path/to/openrocket_mcp/scripts/openrocket-mcp"   # Windows: 'C:\path\to\openrocket_mcp\scripts\openrocket-mcp.cmd'
startup_timeout_sec = 60
tool_timeout_sec = 300        # dispersion and motor ranking can take a few minutes

[mcp_servers.openrocket.env]
OPENROCKET_MCP_WORKSPACE = "/path/to/your/rockets"          # optional: the folder with your .ork files
```

Or connect Codex to your team server:

```toml
[mcp_servers.openrocket]
url = "https://your-server/mcp"
bearer_token_env_var = "OPENROCKET_MCP_TOKEN"   # set this environment variable to the server's token
tool_timeout_sec = 300
```

For Claude's design-review routine in Codex too, copy [`.agents/skills/rocket-design-review`](.agents/skills/rocket-design-review)
into `~/.codex/skills/` (or into `.agents/skills/` in your team's repository).

Then just ask — for example: *"Open the two-stage example and check it against Launch Canada rules."*

## Make it your team's

Copy [`openrocket-mcp.example.json`](openrocket-mcp.example.json) to `openrocket-mcp.json` and commit it, so everyone
on the team gets the same answers. It holds your units, launch site altitude, safety factors, shear pin ratings and fin
materials. You can also just tell Claude ("our pins are 2-56 nylon, 35 lbf each") and it will update the file.

## RocketMaxxing, the desktop app

RocketMaxxing is a desktop app on the same engine, for Mac, Windows and Linux, that works offline and needs no AI
subscription. It reads and writes the same `.ork` files as OpenRocket.

- **Design:** the parts list, a 3-D view (side, back, free 3-D, cut-away and exploded) and the selected part's
  properties side by side. Drag parts in the list to move them into another part, slide a part along the rocket and
  it lines up with its neighbours, copy and paste parts, and see stability, a quick flight and the competition rule
  check update after every edit.
- **Motors, Flights:** configurations, the motor database with thrust curves, your own motors from static-fire data,
  simulations with plots and a flight video.
- **Analysis:** all 50 of the engine's analyses as forms, each with what it needs and gives, and a Start here page by
  question. **Targets** with live margins, **Standards** for the team's numbers, **Flight data** for altimeter logs
  and past rockets.
- Search (Ctrl+K / ⌘K), right-click menus everywhere, a Guide and a tour of the Design page.
- An optional assistant: Claude or ChatGPT (through Claude Code or Codex) or an API key, working inside the app with
  the same tools.

Download it from the [Releases page](https://github.com/TheCheng2005/RocketMaxxing/releases) (Windows, Mac and Linux
installers with Java included; the latest build of every push is also under Actions › RocketMaxxing installers). To
build it yourself:
`./gradlew rocketmaxxing`, then `build/install/openrocket-mcp/bin/rocketmaxxing`. The full guide is
[docs/ROCKETMAXXING.md](docs/ROCKETMAXXING.md).

![RocketMaxxing](docs/rocketmaxxing/design.png)

## Good to know

- Every number is an engineering estimate from OpenRocket and standard calculations. Claude says where each number
  comes from. It does **not** replace ground tests, flight tests, your mentors or the range safety officer.
- Nothing is saved to your design file until you ask Claude to save it.
- Simulations run about three times faster than OpenRocket's own engine (more on rockets with many internal parts),
  with the same numbers to the last digit: the server skips work OpenRocket repeats at every step (the airframe's mass,
  the motor's after burnout, the geometry check). Studies that fly many simulations (optimizers, Monte Carlo, shape
  trades) finish 4 to 8 times sooner than before.
- Inputs are checked: a value no rocket can have (a negative mass, a zero Cd, a 10^30 m/s wind) is refused with the range
  that is allowed, and a flight that cannot happen (no lift-off, tumbling under thrust, beyond Mach 3) is flagged
  before its numbers.
- On a team server, everyone connected shares the open designs, so say which one you mean if several are open.
- Details on every tool, the methods behind them and their limits: [docs/REFERENCE.md](docs/REFERENCE.md).

## Contributing

Found a number that looks wrong, or want a new capability? [Open an issue](https://github.com/TheCheng2005/RocketMaxxing/issues/new/choose).
Code, rule sets and flight data are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

GPL-3.0-or-later, like OpenRocket. See [LICENSE](LICENSE).
