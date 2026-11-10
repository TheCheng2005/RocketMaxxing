# RocketMaxxing

A desktop app for designing, simulating and checking competition rockets, on [OpenRocket](https://openrocket.info)'s
own physics. Design the rocket in 3-D, see the competition rules re-checked after every edit, and run 50 engineering
analyses (recovery loads, fin flutter, shear pins, ejection charges, parachute sizing and more) without writing code or
keeping spreadsheets.

For Mac, Windows and Linux. Free and open source; it works offline and needs no account or AI subscription. It opens
and saves ordinary OpenRocket `.ork` files (and reads RockSim `.rkt`), so a design can go back and forth with
OpenRocket. Built for student competition teams and amateur rocketeers, and set up for **Launch Canada 2027** out of
the box.

**[Download RocketMaxxing](https://github.com/TheCheng2005/RocketMaxxing/releases)** ·
[User guide](docs/ROCKETMAXXING.md) · [A real flight, start to finish](docs/WORKED_EXAMPLE.md)

![RocketMaxxing's Design page: the parts list, the rocket with CG and CP, the summary, the rule check and the selected part's properties](docs/rocketmaxxing/design.png)

**Contents:** [Download](#download) · [What it does](#what-it-does) · [See it in action](#see-it-in-action) ·
[Make it your team's](#make-it-your-teams) · [The assistant (optional)](#the-assistant-optional) ·
[For AI assistants: OpenRocket MCP](#for-ai-assistants-openrocket-mcp) · [Good to know](#good-to-know) ·
[Build it yourself](#build-it-yourself)

## Download

Get the installer for your computer from the [Releases page](https://github.com/TheCheng2005/RocketMaxxing/releases):

| Computer | File |
|---|---|
| Windows | `RocketMaxxing-<version>-windows-x64.msi` |
| Mac with Apple Silicon (M1 and later) | `RocketMaxxing-<version>-macos-apple-silicon.dmg` |
| Mac with Intel | `RocketMaxxing-<version>-macos-intel.dmg` |
| Linux (Debian, Ubuntu) | `RocketMaxxing-<version>-linux-x64.deb` |

Each installer carries Java and everything else the app needs (about 240 to 300 MB), so there is nothing else to
install. The installers are not signed yet, so the first launch needs one extra click:

- **Mac:** open the `.dmg`, drag RocketMaxxing into Applications and open it. macOS blocks it the first time: in
  **System Settings > Privacy & Security**, click **Open Anyway**.
- **Windows:** run the `.msi`. If Windows says it protected your PC, click **More info**, then **Run anyway**.
- **Linux:** `sudo apt install ./RocketMaxxing-<version>-linux-x64.deb`, then start RocketMaxxing from the
  applications menu.

The [install guide](docs/ROCKETMAXXING.md#install) has the details. Once a day RocketMaxxing checks whether a newer
version is out and offers it; it never downloads anything by itself, and the check can be turned off in Settings. The
newest build of every change is also under **Actions > RocketMaxxing installers**.

## What it does

- **Design.** The parts list, the rocket in 3-D (side, back, free 3-D, cut-away and exploded) and the selected part's
  properties, side by side. Drag parts into one another, slide them along the rocket and they line up with their
  neighbours, copy and paste them, pick real parts from OpenRocket's catalogue, draw freeform fins, and lay out a whole
  avionics bay (two altimeters, batteries, switches, a tracker, the charges) in one go. Stability, a quick flight and
  the competition rule check update after every edit, and every edit can be undone.
- **Motors.** OpenRocket's motor database with thrust curves, offline, filtered to what fits your mount. Import `.eng`
  and `.rse` files, or make your own motor from static-fire data.
- **Flights.** Simulations with their launch conditions and plots, two-stage flights end to end, and a flight video:
  a chase camera, the flame and smoke, the parachutes opening and the numbers on screen, saved as an MP4 in your
  Downloads folder for slides and sharing.
- **Analysis.** 50 analyses, each a form that says what it needs and what it gives, grouped by the question they
  answer (start at *Start here*):
  - *Is it ready, and does it pass?* The rule check against Launch Canada with the rule numbers, what is still to set
    up, and whether your meeting notes match the design.
  - *Is it stable?* Stability through the whole flight and through Mach, in wind, for every stage; the nose weight it
    needs; nose and fin shapes compared; a fin optimizer that finds the least drag or the lightest fins clear of
    flutter.
  - *Will recovery work?* Parachute sizes for a target descent rate (and a chart of descent rate against size for the
    Cd values you pick), opening loads, shear pins, ejection charges, what happens if a charge fires late, whether it
    all fits in the bay, and the landing energy of every section.
  - *Will it hold together?* Fin flutter, fin stress in FEA (with the free CalculiX), loads at every airframe joint,
    heating at speed, fiberglass against carbon against aluminium, and pressure vessels for liquid and hybrid engines.
  - *Which motor, and can it do better?* Every motor that fits, flown and ranked; sweeps; an optimizer; Monte Carlo
    landing areas with a map for Google Earth.
  - *Launch day and electronics.* The forecast winds aloft at your site, where the rail buttons go, a one-page flight
    card, altimeter settings, sensor ranges, a battery budget for the pad wait, the tracker's radio range, and
    simulated sensor data to test your flight computer with.
  - *Reports and data.* The design review report, what changed since the last review, CFD export (STL and the cases to
    run), DXF fin patterns for cutting, and RASAero data where Launch Canada asks for it.
- **Targets.** Your team's minimum, goal and maximum for apogee, speeds, stability, flutter margin, mass, descent
  rates and more, with the margin to each, checked again after every edit, beside the competition rules.
- **Standards.** The team's numbers that every check uses: the rule set, the launch site, safety factors, shear pin
  ratings, materials.
- **Flight data.** After a flight, the altimeter log against the simulation in the day's conditions, and how well
  your past rockets' apogees were predicted over the years.
- **Finding your way.** Search for anything by what it is for (Ctrl+K, or ⌘K on a Mac), right-click menus everywhere,
  a Guide, a tour of the Design page, and an ⓘ on every analysis and input.

<p>
<img src="docs/rocketmaxxing/start-here.png" alt="Analysis, Start here: the analyses grouped by the question they answer" width="49%">
<img src="docs/rocketmaxxing/targets.png" alt="Targets with live margins beside the competition rules" width="49%">
</p>
<p>
<img src="docs/rocketmaxxing/flights.png" alt="Flights: a simulation's launch conditions and its plot" width="49%">
<img src="docs/rocketmaxxing/exploded.png" alt="The exploded 3-D view with every part laid out" width="49%">
</p>
<p>
<img src="docs/examples/flight.gif" alt="The flight video: liftoff, burnout, apogee, drogue and main, touchdown" width="100%">
</p>

The [user guide](docs/ROCKETMAXXING.md) covers every page.

## See it in action

[**Worked example: a real flight**](docs/WORKED_EXAMPLE.md) rebuilds the Notre Dame Rocket Team's 2020 L1395 rocket
from its published dimensions and weigh-in, runs the checks a team does before launch day in the day's real weather,
and compares the simulation with the altimeter and accelerometer log of its flight. Stock OpenRocket came out 9% high;
the accelerometer shows why (the motor delivered about 9% less than its curve, and drag was close), and with that the
simulation follows the measured climb to within 5 m. Open
[its design](docs/examples/ndrt-2020/ndrt-2020.ork) in RocketMaxxing and follow along: a pattern for your own rocket.

[**Examples**](docs/EXAMPLES.md) follows one 10,000 ft rocket, the *Maple 10K*, from a blank page to launch day. It is
written as questions to an AI assistant, but every step is an analysis on the Analysis page too: open
[`maple-10k.ork`](docs/examples/maple-10k.ork) and run them yourself.

## Make it your team's

Set your team's numbers on the **Standards** page (units, launch site, safety factors, shear pin ratings, fin
materials) and RocketMaxxing keeps them in `openrocket-mcp.json` in your designs folder. Commit that file next to your
designs and everyone on the team gets the same answers. [`openrocket-mcp.example.json`](openrocket-mcp.example.json)
shows what it can hold.

## The assistant (optional)

RocketMaxxing has an assistant beside the design, off until you turn it on. Connect it with your Claude subscription
(through Claude Code), your ChatGPT subscription (through Codex) or an Anthropic API key, and ask in plain words:
*"size the main for 20 ft/s and check the shear pins"*. It works through the same tools as the app, so what it changes
shows at once and undoes like any other edit. With it on, your messages and the design's data go to Anthropic or
OpenAI; everything else in the app stays on your computer. See [the guide](docs/ROCKETMAXXING.md#the-assistant-optional).

## For AI assistants: OpenRocket MCP

The engine inside RocketMaxxing also runs on its own as an MCP server, for teams who would rather work by talking to
Claude Desktop, claude.ai, Claude Code or Codex. The assistant runs real OpenRocket simulations and the same analyses,
shows the rocket in 3-D right in the chat, and can follow the project from your team's Slack and Notion. It uses the
same `.ork` files and standards file as the app. Set-up and everything it can do: [docs/MCP.md](docs/MCP.md).

## Good to know

- Every number is an engineering estimate from OpenRocket and standard calculations, and each result says where it
  comes from. It does **not** replace ground tests, flight tests, your mentors or the range safety officer.
- Nothing is written to your design file until you save.
- Simulations run about three times faster than OpenRocket's own engine (more on rockets with many internal parts),
  with the same numbers to the last digit: the engine skips work OpenRocket repeats at every step (the airframe's mass,
  the motor's after burnout, the geometry check). Studies that fly many simulations (optimizers, Monte Carlo, shape
  trades) finish 4 to 8 times sooner.
- Inputs are checked: a value no rocket can have (a negative mass, a zero Cd, a 10^30 m/s wind) is refused with the
  range that is allowed, and a flight that cannot happen (no lift-off, tumbling under thrust, beyond Mach 3) is flagged
  before its numbers.
- Every analysis, the methods behind it and its limits: [docs/REFERENCE.md](docs/REFERENCE.md).

## Build it yourself

You need Java 17 or newer ([Temurin](https://adoptium.net)) and, the first time, Node.js 20 or newer for the interface.

```sh
git clone https://github.com/TheCheng2005/RocketMaxxing.git
cd RocketMaxxing
./gradlew rocketmaxxing
build/install/openrocket-mcp/bin/rocketmaxxing        # Windows: build\install\openrocket-mcp\bin\rocketmaxxing.bat
```

`./gradlew rocketmaxxingInstaller` makes an installer for your own computer. See
[the guide](docs/ROCKETMAXXING.md#running-from-source) for the options and [CONTRIBUTING.md](CONTRIBUTING.md) for the
tests.

## Contributing

Found a number that looks wrong, or want a new capability? [Open an issue](https://github.com/TheCheng2005/RocketMaxxing/issues/new/choose).
Code, rule sets and flight data are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

GPL-3.0-or-later, like OpenRocket. See [LICENSE](LICENSE). The third-party software inside the app and its licences
are listed in Help > About.
