# Recording the demo video

A 100-second screen recording of the real Claude Desktop app: install, then six questions about the *Maple 10K* rocket
from [EXAMPLES.md](EXAMPLES.md). Record it once in about 10 minutes; speed up the waits when editing.

## Before recording

- [ ] Install the extension from the [Releases page](https://github.com/TheCheng2005/RocketMaxxing/releases) *on camera*
      (shot 1), or beforehand if you want a shorter video.
- [ ] Make a clean rocket folder, e.g. `Documents/Rockets`, and put
      [`maple-10k-pdr.ork`](examples/maple-10k-pdr.ork) in it. Choose it as the rocket folder when installing.
      (Paths show in Claude's answers: a neutral folder name keeps your username off screen.)
- [ ] Claude Desktop: start a new chat, window about 1280 × 800, zoom in once (Ctrl/Cmd +) so text is readable on phones.
      Close other windows and notifications (Do Not Disturb).
- [ ] Recorder: macOS Cmd-Shift-5 (record selected portion), Windows Win-Alt-R (Game Bar) or Clipchamp, Linux OBS.
- [ ] Do one practice run first: the first question of a session loads OpenRocket's databases and is slower.

## Shot list (paste each prompt; do not wait on camera, cut or speed up)

| # | Show | Prompt to paste | What should appear | Final length |
|---|---|---|---|---|
| 1 | Double-click the `.mcpb`, pick the rocket folder, Install | — | Claude Desktop lists "OpenRocket" as an extension | 8 s |
| 2 | Open the design and draw it | `Open maple-10k-pdr.ork and show me the rocket.` | Summary of the rocket, then the side drawing with CG and CP (Claude may attach it as a file / artifact) | 15 s |
| 3 | The flutter problem | `It goes supersonic. Will our 1/8 in fiberglass fins flutter?` | **FAIL**, margin about 0.4, and the thickness needed | 15 s |
| 4 | The fix | `We'll make the fins from 1/4 in quasi-isotropic carbon; our coupon test gave a shear modulus of 16 GPa. Save that to our standards, then make the fins as small as possible while meeting the stability rules and staying clear of flutter, and pick the motor for 10,000 ft again.` | Claude updates the standards, runs the optimizer and motor ranking; fin span and root chord, a motor, about 10,000 ft, flutter PASS | 20 s |
| 5 | Where it lands | `Where will it land in a 15 km/h west wind? Include our build and motor uncertainty, and draw the landing map.` | 200 simulated flights, distances, and the landing map | 15 s |
| 6 | Does it pass | `Does it pass Launch Canada? Then make us the launch-day flight card.` | "No failures", the warnings with rule numbers, and the flight card | 15 s |
| 7 | Show it off | `Animate the flight for our team's social media.` | The key-moment stills in the chat; the GIF (and MP4 with ffmpeg) saved in the rocket folder. Play the GIF over the end of the clip | 10 s |
| — | End card | — | Title + "github.com/TheCheng2005/RocketMaxxing" | 5 s |

Tip: if an answer is long, scroll to the one line that matters (FAIL / PASS, the apogee, "No failures") and hold for
two seconds. Zoom into it in editing if your editor can.

## Voice-over / captions

1. *"This is OpenRocket MCP: Claude, running real OpenRocket simulations for your team's rocket. Installing takes one
   click; Java and OpenRocket come with it."*
2. *"Here's our 10,000-foot competition rocket. Claude opens the actual OpenRocket file and draws it, with the centre of
   gravity and centre of pressure."*
3. *"It goes supersonic, so we ask about fin flutter. The answer: the thin fiberglass fins would flutter, and here's the
   thickness they'd need."*
4. *"We switch to carbon fins, give it the stiffness from our own coupon test, and ask for the smallest fins that are
   still stable and flutter-free. Claude runs the optimizer and re-picks the motor: ten thousand feet."*
5. *"Two hundred simulated flights, with wind, build and motor uncertainty, and a landing map for the range safety
   officer."*
6. *"And the Launch Canada rule check, with rule numbers, plus the flight card for launch day. Free and open source:
   link below."*

## Afterwards

- Upload (YouTube unlisted/public, or attach the MP4 to the GitHub release) and add the link to
  [MCP.md](MCP.md), under "Seeing the rocket in the chat".
- Keep the numbers honest: if an answer differs from EXAMPLES.md (Claude's wording varies, the physics does not), keep
  what the app actually said.
