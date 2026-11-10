# RocketMaxxing

A desktop app for designing, simulating and checking competition rockets, on [OpenRocket](https://openrocket.info)'s
own physics. Design the rocket in 3-D, see the competition rules re-checked after every edit, and run 50 engineering
analyses (recovery loads, fin flutter, shear pins, ejection charges, parachute sizing and more) without writing code
or keeping spreadsheets.

Free and open source, for Mac, Windows and Linux. It works offline, needs no account, and opens and saves ordinary
OpenRocket `.ork` files. Built for student competition teams and amateur rocketeers, and set up for **Launch Canada
2027** out of the box.

![RocketMaxxing's Design page: the parts list, the rocket with CG and CP, the summary, the rule check and the selected part's properties](docs/rocketmaxxing/design.png)

## Download

Get the installer for your computer from the **[Releases page](https://github.com/TheCheng2005/RocketMaxxing/releases)**:

| Computer | File |
|---|---|
| Windows | `RocketMaxxing-<version>-windows-x64.msi` |
| Mac with Apple Silicon (M1 and later) | `RocketMaxxing-<version>-macos-apple-silicon.dmg` |
| Mac with Intel | `RocketMaxxing-<version>-macos-intel.dmg` |
| Linux (Debian, Ubuntu) | `RocketMaxxing-<version>-linux-x64.deb` |

Java and everything else it needs come inside. The installers are not signed yet, so the first launch needs one extra
click: on a Mac, **System Settings > Privacy & Security > Open Anyway**; on Windows, **More info > Run anyway**. Step by
step: [Install](docs/ROCKETMAXXING.md#install).

## Where to go

| I want to... | Go to |
|---|---|
| Learn the app: every page, the analyses, the optional assistant | **[The RocketMaxxing guide](docs/ROCKETMAXXING.md)** |
| See it on a real rocket, checked against the log of its flight | [Worked example: NDRT 2020](docs/WORKED_EXAMPLE.md) |
| Follow one rocket from a blank page to launch day | [Examples: the Maple 10K](docs/EXAMPLES.md) |
| Use the engine from Claude or Codex instead (MCP server) | [RocketMaxxing for AI assistants](docs/MCP.md) |
| Know how a number is worked out, and its limits | [Technical reference](docs/REFERENCE.md) |
| Report a wrong number or a bug, or ask for something | [Issues](https://github.com/TheCheng2005/RocketMaxxing/issues/new/choose) |
| Build it from source or contribute | [Running from source](docs/ROCKETMAXXING.md#running-from-source) · [CONTRIBUTING.md](CONTRIBUTING.md) |

Every number is an engineering estimate from OpenRocket and standard calculations. It does **not** replace ground
tests, flight tests, your mentors or the range safety officer.

## License

GPL-3.0-or-later, like OpenRocket. See [LICENSE](LICENSE). The third-party software inside the app and its licences
are listed in Help > About.
