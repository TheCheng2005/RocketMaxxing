# Examples: what you ask, what you get

One rocket, from a blank page to launch day. Each step shows what a team member types, what Claude answers, and the
numbers and plots behind the answer. **Everything below is real output from the server** (OpenRocket 24.12), generated
by [`scripts/make_examples.py`](../scripts/make_examples.py); the one-line answers are written from those numbers.
Units follow the team setting (here metric with imperial in brackets). In Claude Desktop the plots and drawings
appear right in the chat, and long runs (optimizers, Monte Carlo) show their progress and can be stopped. Not sure what
to ask? Ask Claude what it can do: it also offers related things as you work.

Using [RocketMaxxing](ROCKETMAXXING.md), the desktop app? Every step below is an analysis on its Analysis page too:
open the same design and run them there, no assistant needed.

The Maple 10K is a made-up rocket. For a real one, checked against the log of its flight, see the
[worked example](WORKED_EXAMPLE.md).

**Steps:** [Design](#design) · [Recovery](#recovery) · [Flight and rules](#flight-and-rules) ·
[Design studies](#design-studies) · [Structures and CFD](#structures-and-cfd) · [Build](#build) · [Reviews](#reviews) ·
[Launch day](#launch-day) · [Show it off](#show-it-off) · [After the flight](#after-the-flight) ·
[Team history](#team-history) · [Electronics](#electronics) · [Parachutes and product links](#parachutes-and-product-links) ·
[Explore it in the chat](#explore-it-in-the-chat)

The rocket: *Maple 10K*, a 4 in fiberglass, dual-deploy, single-stage rocket for the 10,000 ft category of Launch
Canada 2027. Try it yourself: open [`examples/maple-10k-pdr.ork`](examples/maple-10k-pdr.ork) (the early version, before
step 3) or [`examples/maple-10k.ork`](examples/maple-10k.ork) (the finished design) and ask the same questions.

## Design

### 1. "Design a 4 inch fiberglass rocket with a 75 mm motor mount, dual deploy: 18 in drogue at apogee, 60 in main at 1000 ft. 18 in ogive nose, 64 in of airframe, four trapezoidal fins."

<sub>Tools Claude uses: `open_design`, `add_component`, `set_deployment`</sub>

Claude builds the rocket part by part in OpenRocket, inside as well as out: nose bulkhead, motor mount with three centering rings, rail buttons, both parachutes (with realistic packed sizes) and their shock cords. It opens in the OpenRocket app too.

### 2. "Lay out the avionics bay between the two airframes: two independent altimeters, a GPS tracker, and the ejection charges."

<sub>Tools Claude uses: `add_avionics_bay`</sub>

> Av-bay: 25.4 mm (1 in) switch band, 304.8 mm (12 in) coupler between Upper airframe and Lower airframe: 2 independent altimeter circuits (each: altimeter + own battery + own physical switch), GPS tracker on its own battery in the nose; main primary + backup on the forward bulkhead, drogue primary + backup on the aft bulkhead. Bay mass 893.9 g (1.971 lb). Static ports: bay volume 124 in3: 4 ports of 0.139 in (3.5 mm) evenly around the switch band (same area as 1.2 x 1/4 in).

<details><summary>Bay layout</summary>

| Part | Position | Mass |
|---|---|---|
| Main charge (primary) | 901.7 mm from the nose tip | 12 g |
| Main charge (backup) | 901.7 mm from the nose tip | 12 g |
| U-bolt (main harness) | 906.8 mm from the nose tip | 30 g |
| Sled, threaded rods and nuts | 939.3 mm from the nose tip | 120 g |
| 9 V battery (primary altimeter) | 943.5 mm from the nose tip | 46 g |
| Primary altimeter | 995.1 mm from the nose tip | 25 g |
| Screw switch (primary altimeter) | 1067 mm from the nose tip | 10 g |
| Screw switch (backup altimeter) | 1067 mm from the nose tip | 10 g |
| Backup altimeter | 1079 mm from the nose tip | 25 g |
| 9 V battery (backup altimeter) | 1168 mm from the nose tip | 46 g |
| Drogue charge (primary) | 1232 mm from the nose tip | 12 g |
| Drogue charge (backup) | 1232 mm from the nose tip | 12 g |
| U-bolt (drogue harness) | 1232 mm from the nose tip | 30 g |

</details>

> ⚠️ not enough room in Upper airframe: the charges, shock cord and parachute need 520.1 mm (20.48 in) from the av-bay but only 508 mm (20 in) is free before the nose shoulder. Lengthen the airframe, shorten the coupler, or use a smaller-packing parachute.

> ⚠️ not enough room in Lower airframe: the charges, shock cord and parachute need 298 mm (11.73 in) from the av-bay but only 254 mm (10 in) is free before the motor mount. Lengthen the airframe, shorten the coupler, or use a smaller-packing parachute.

*"Lengthen the upper airframe to 26 in and the lower airframe to 43 in."* The bay packs the parachutes against itself and the motor mount, fins and rail buttons are positioned from the aft end, so everything keeps its place and the parachutes get the room.

The layout follows the Launch Canada electronics edicts: one altimeter per circuit, each with its own battery and physical switch; the tracker on its own battery; charges on the bulkhead facing the bay they open. Masses are typical defaults: give Claude your actual altimeters, batteries and tracker (name, mass, length) and it uses them.

### 3. "Which motor gets us closest to 10,000 ft?"

<sub>Tools Claude uses: `rank_motors`, `set_motor`</sub>

> **AeroTech L1250DM**, 10,050 ft predicted. 68 distinct motors fit (diameter, length + 50 mm (1.969 in) overhang, filters); 60 simulated; 55 meet the rules: each one was flown in the simulation, not just looked up, and motors that break a rule (rail exit speed, thrust-to-weight, stability) are ranked last.

| Motor | Impulse | Apogee (vs 10,000 ft) | Rail exit | Max Mach | Min stability | Meets rules |
|---|---|---|---|---|---|---|
| AeroTech L1250DM | L, 4400 Ns (989.2 lbf-s) | 3063 m (10050 ft) (+15.34 m (50.31 ft)) | 39.39 m/s (129.2 ft/s) | 1.133 | 2.08 cal | yes |
| AeroTech L1170FJ | L, 4214 Ns (947.4 lbf-s) | 2969 m (9741 ft) (-79.07 m (-259.4 ft)) | 40.07 m/s (131.5 ft/s) | 1.172 | 1.919 cal | yes |
| AeroTech L1940X | L, 4317 Ns (970.5 lbf-s) | 3143 m (10313 ft) (+95.31 m (312.7 ft)) | 55.02 m/s (180.5 ft/s) | 1.409 | 2.236 cal | yes |
| Cesaroni Technology 4263L1350-P | L, 4280 Ns (962.2 lbf-s) | 3154 m (10348 ft) (+106 m (347.6 ft)) | 45.17 m/s (148.2 ft/s) | 1.341 | 2.109 cal | yes |
| Cesaroni Technology 3757L800-P | L, 3723 Ns (836.9 lbf-s) | 2928 m (9606 ft) (-120 m (-393.6 ft)) | 38.11 m/s (125 ft/s) | 1.031 | 2.045 cal | yes |
| AeroTech L1390G | L, 3946 Ns (887.2 lbf-s) | 2908 m (9540 ft) (-140.2 m (-460 ft)) | 44.4 m/s (145.7 ft/s) | 1.224 | 2.08 cal | yes |

### 4. "It goes supersonic. Will our 1/8 in fins flutter?"

<sub>Tools Claude uses: `fin_flutter`</sub>

> **FAIL.** At 382.7 m/s (1255 ft/s) (Mach 1.133) and 640.6 m the estimated flutter speed is only 163 m/s: margin 0.4259 against the team's required 1.5. Fins at least **7.407 mm (0.2916 in)** thick, a stiffer material or a lower aspect ratio fix it.

### 5. "We'll make the fins from 1/4 in quasi-isotropic carbon. Our coupon test gave a shear modulus of 16 GPa: save that to our standards and check again."

<sub>Tools Claude uses: `update_standards`, `edit_components`, `fin_flutter`</sub>

> **PASS**, margin 2.973 (shear modulus 16 GPa from your standards file, so every later check and the whole team use the measured value).

### 6. "It has to meet the stability rules (also in 30 km/h wind): add the nose weight it needs, then optimize the fin shape for the least drag while staying clear of flutter (span no more than 8 in), and pick the motor again."

<sub>Tools Claude uses: `ballast`, `add_component`, `optimize_fins`, `rank_motors`, `fin_flutter`</sub>

> **293 g of nose ballast**, then the whole fin shape at once: root chord **130.3 mm**, tip chord **97.72 mm**, span **177.5 mm**, sweep **25.91 mm** (meets every constraint; launch mass +39.97 g (1.41 oz)): stability 2.215 cal to 5.168 cal in flight, flutter margin 2.889. Best motor now: **AeroTech M1315W**, 9,829 ft, Mach 1.173. Final flutter check: **PASS**, margin 2.45.

![Current and optimized fin planforms, root on the body line](examples/fins.svg)

<sub>Only buildable fins are tried: tip chord at least 0.5 in, no tip trailing edge behind the root (it would take the landing), leading-edge sweep at most 65 deg, span at most 8 in as asked. Why ballast: with the real avionics bay modelled, the electronics sit further aft than a single lump would, so the CG moves back. Limits used: minStability 2.189 cal = max(1.5 cal, 10% of body length at L:D 21.89); maxStability 6 cal (over-stable); flutter margin ≥ 1.5 from the team standards.</sub>

### 7. "Show me the rocket."

<sub>Tools Claude uses: `draw_rocket`</sub>

![Maple 10K cut-away: nose with GPS tracker, main and shock cord, the avionics bay with both altimeter circuits and charges, drogue, motor mount and motor, with the separation points, CG and CP marked](examples/rocket.svg)

A cut-away from the OpenRocket model itself: every part is drawn where it is, electronics coloured by role, and the red dashes show where the rocket separates (main at the nose, drogue below the avionics bay).

88 in long, 10.73 kg (23.65 lb) on the pad, stability 3.908 cal at launch and 5.09 cal at burnout.

### 8. "What if we used 1/8 in carbon for the fins to save weight?"

<sub>Tools Claude uses: `edit_components`, `fin_flutter`</sub>

> Claude makes the change and checks it: flutter **FAIL**, margin 0.7563 (flutter speed / airspeed; the team wants 1.5). Thinner fins would need 5.012 mm to be safe.

### 9. "Undo that."

<sub>Tools Claude uses: `undo`, `history`</sub>

> Undone: *edit_components* (the thickness change). Every edit in the session can be undone or redone, newest first; `history` lists them (21 so far in this session). Nothing touches the `.ork` file until you ask Claude to save.

## Recovery

### 10. "What main do we need to land at 20 ft/s? And a drogue for about 85 ft/s."

<sub>Tools Claude uses: `size_parachute`, `edit_components`</sub>

> The descending mass is 7.276 kg (16.04 lb), so the main needs a drag area of 3.135 m2 (33.74 ft2) (a 2234 mm flat canopy at Cd 0.8). Parachutes from OpenRocket's catalogue that do it:

| Parachute | Diameter | Cd | Descent | Packed size | Mass |
|---|---|---|---|---|---|
| Rocketman DG-07 | 2134 mm (84 in) | 0.85 | 6.191 m/s (20.31 ft/s) | 101.6 mm (4 in) dia x 185.4 mm (7.3 in) = 1503 cm3 (91.73 in3) | 442.8 g (15.62 oz) |
| Rocketman LA-07 | 2134 mm (84 in) | 0.85 | 6.191 m/s (20.31 ft/s) | 76.2 mm (3 in) dia x 188 mm (7.4 in) = 857.2 cm3 (52.31 in3) | 252.3 g (8.9 oz) |
| Rocketman EL-060 | 1524 mm (60 in) | 1.6 | 6.318 m/s (20.73 ft/s) | 101.6 mm (4 in) dia x 101.9 mm (4.01 in) = 825.8 cm3 (50.39 in3) | 280.7 g (9.9 oz) |
| b2 Rocketry Company CL-60 | 1524 mm (60 in) | 1.89 | 5.813 m/s (19.07 ft/s) | 99.06 mm (3.9 in) dia x 279.4 mm (11 in) = 2153 cm3 (131.4 in3) | 283.5 g (10 oz) |

*"Use the Rocketman DG-07 and a 21 in drogue."*

### 11. "What loads do the chutes see when they open, and how many 4-40 nylon shear pins do we need?"

<sub>Tools Claude uses: `recovery_analysis`, `ejection_charge`</sub>

| device | opens at | design load | harness rating | descent |
|---|---|---|---|---|
| Drogue | 2998 m, 19.95 m/s | 72.54 N (16.31 lbf) | 145.1 N | 26.9 m/s (88.27 ft/s) |
| Main | 302.8 m, 26.14 m/s | 1730 N (388.8 lbf) | 3459 N | 6.081 m/s (19.95 ft/s) |

> Shear pins on the main bay: **3 x 4-40 nylon** (they must hold while the drogue opens). Black powder for a 4 × 12 in bay at 15 psi: **1.75 g** primary, 2.187 g backup. The harness rating is twice the opening load, for shock cord, quick links and eye bolts.

## Flight and rules

### 12. "Does it pass Launch Canada?"

<sub>Tools Claude uses: `check_requirements`</sub>

> **No failures; 5 warning(s).** Every check cites its rule, and there is a checklist of 15 things to verify by hand (electronics, radio, structures, operations).

|  | Check | Value | Rule |
|---|---|---|---|
| WARN | Within the models' range | OpenRocket warns: Body calculations may not be entirely accurate at supersonic speeds. |  |
| WARN | Launch site altitude | 0 m (0 ft) used by this simulation |  |
| WARN | Ascent stability (maximum, over-stability) | 5.723 cal | R10.3.1, R10.4.1 |
| WARN | Ascent stability in 8.333 m/s (27.34 ft/s) wind (maximum, over-stability) | 5.724 cal | R10.3.1, R10.4.1 |
| WARN | Maximum Mach number | 1.166 |  |
| PASS | Simulated launch angle | 6 deg | R10.1.1 |
| PASS | Rail departure velocity | 40.82 m/s (133.9 ft/s) | R10.2.1 |
| PASS | Thrust-to-weight at liftoff (avg thrust / liftoff weight) | 10.83 | R10.2.2, R3.1.2, R3.1.3; 2027 Edicts (Advanced Specific) |
| PASS | Ascent stability (minimum, rail exit to apogee while airspeed > 100 ft/s) | 3.534 cal at t=0.335 s, Mach 0.1208; angle of attack 3.174 deg there (wind / rail exit): OpenRocket's CP moves forward at high angle of attack. Zero-AoA margin: 4.032 cal | R10.3.1, R10.4.1 |
| PASS | Ascent stability in 8.333 m/s (27.34 ft/s) wind (minimum, rail exit to apogee while airspeed > 100 ft/s) | 2.406 cal at t=0.335 s, Mach 0.1257; angle of attack 12.54 deg there (wind / rail exit): OpenRocket's CP moves forward at high angle of attack. Zero-AoA margin: 4.032 cal | R10.3.1, R10.4.1 |
| PASS | Fin flutter margin (Fins, Carbon fiber) | 2.462 at 939.2 m (3081 ft), 392.4 m/s (1287 ft/s) | structures.flutterMinMargin |
| PASS | Length-to-diameter ratio | 21.89 | 2027 Edicts, Stability: L:D Ratio |

### 13. "Simulate the flight and plot it."

<sub>Tools Claude uses: `run_simulation`</sub>

> Apogee **2998 m (9836 ft)** at 20.55 s. Top speed 392.3 m/s (1287 ft/s) (Mach 1.166), 15.33 G peak, 40.82 m/s (133.9 ft/s) off the rail.

![Altitude against time with burnout, apogee and both deployments marked](examples/flight-profile.svg)

### 14. "Where will it land in a 15 km/h west wind? Include our build and motor uncertainty, and give us a map for Google Earth."

<sub>Tools Claude uses: `monte_carlo`</sub>

> Over 200 simulated flights the median landing is 211 m (692.1 ft) from the pad and 95% land within 505.7 m (1659 ft); the landings centre 561 ft west of the pad (the rail is tilted into the wind, so it flies upwind and drifts back under the drogue). Apogee 2982 m (9782 ft) ± 200 m (656.1 ft).

![200 simulated landings around the pad with the 2-sigma ellipse](examples/landing.svg)

[`landing.kml`](examples/landing.kml) puts the pad, every landing and the ellipse on the map in Google Earth (desktop, web or phone), so the recovery team and the RSO see the fields, roads and trees it covers.

Claude also reports what drives the spread (correlation of each uncertain input with the result), so the team knows what to measure more carefully:

| Uncertain input | Apogee | Min stability | Landing distance |
|---|---|---|---|
| windSpeed | -0.1363 | -0.9657 | -0.7174 |
| launchAngle | -0.1505 | -0.005545 | 0.3506 |
| structureMass | -0.05261 | 0.1284 | 0.09882 |
| airframeDrag | -0.9239 | 0.04311 | -0.1806 |
| motorThrust | 0.3206 | 0.07675 | -0.01767 |
| parachuteCd | -0.03247 | 0.03013 | -0.09622 |

<sub>Correlation from -1 to 1: the closer to ±1, the more that input drives the result.</sub>

## Design studies

### 15. "Would a different nose cone or fin shape fly higher?"

<sub>Tools Claude uses: `compare_shapes`</sub>

> Best that keeps the stability: **Von Karman (Haack LD) nose with airfoil fin edges: 3718 m (12198 ft) (+24.01% vs current)**. Every nose profile and fin edge was flown; CD at the design Mach (1.166) shows where the gain comes from.

| Nose | Fin edges | Apogee | vs current | CD | Min stability |
|---|---|---|---|---|---|
| Von Karman (Haack LD) | airfoil | 3718 m (12198 ft) | 24.01% | 1.072 | 3.623 cal |
| tangent ogive (current) | airfoil | 3717 m (12196 ft) | 23.99% | 1.087 | 3.619 cal |
| tangent ogive (current) | rounded | 3584 m (11760 ft) | 19.56% | 1.146 | 3.54 cal |
| Von Karman (Haack LD) | square (current) | 3013 m (9886 ft) | 0.5105% | 1.337 | 3.538 cal |
| 1/2 power | square (current) | 3012 m (9883 ft) | 0.4735% | 1.336 | 3.549 cal |
| LV-Haack | square (current) | 3005 m (9858 ft) | 0.2182% | 1.352 | 3.544 cal |
| 3/4 power | square (current) | 3000 m (9841 ft) | 0.05224% | 1.357 | 3.528 cal |
| parabolic | square (current) | 2999 m (9839 ft) | 0.03261% | 1.365 | 3.533 cal |

<details><summary>Claude's shape guidance (from the tool)</summary>

- Subsonic (< Mach 0.8): skin friction dominates; nose shape changes apogee by ~1-3%. Tangent ogive or elliptical are easy to make and near-optimal; a smooth finish and filled fin joints matter more.
- Transonic / supersonic: theory favours Von Karman / LV-Haack and a longer nose (5-6 calibers) for wave drag, and blunt shapes (elliptical, 1/2 power) lose. OpenRocket uses empirical per-shape curves, so at modest fineness a tangent ogive can rank equal or better in this table: trust the table for this vehicle, and the theory only as a tie-breaker.
- Fin edges: airfoiled or beveled edges reduce fin pressure drag, most at high speed; they also lower the fin's thickness where it is thin, so re-check fin_flutter. Swept, clipped-delta planforms suit supersonic flight.
- OpenRocket's nose and fin pressure-drag models are empirical and least accurate transonic; confirm close calls with RASAero (import_aero_table) or CFD before committing tooling.

</details>

## Structures and CFD

### 16. "Check the fins in FEA: our quasi-isotropic laminate has E = 45 GPa and Poisson's ratio 0.3. And give us the joint loads for the airframe FEA."

<sub>Tools Claude uses: `fin_fea`, `structural_loads`</sub>

> **PASS**, margin of safety 8.722 with the team's safety factor of 2. Design load 208.7 N (46.93 lbf) (limit load; the most loaded fin), from the crosswind gust at max q at t=3.93 s, Mach 1.165, q 86.11 kPa (12.49 psi), angle of attack 1.217 deg. Tip deflection 1.097 mm; peak stress 23.14 MPa. Natural frequencies: 183.8 Hz (1st bending), 643.7 Hz (1st torsion), 1074 Hz (2nd bending).

![The fin coloured by stress under the design load, clamped at the root](examples/fin-stress.svg)

Claude writes a CalculiX model of the fin (8-node shells, clamped root, the flight load) and runs it when CalculiX is installed. The input deck is kept, so a team member can refine it in PrePoMax or Abaqus. The model was checked against cantilever plate theory: deflection, root stress and first frequency agree within 0.2%. The stress peaks at the root, most at its corners, where the real fin's fillet or tab spreads the load.

Joint loads for the airframe: 3 joints, the highest wall stress at Switch band / Lower airframe: 4479 kPa (649.6 psi); the load cases (axial force, bending moments, the flight time, Mach and angle of attack of each) go to a CSV for the tube and coupler FEA.

### 17. "Export it for CFD, and tell us which cases to run."

<sub>Tools Claude uses: `export_geometry`</sub>

> The rocket as STL (9840 triangles, mm, one region per part so the solver reports the force on each), the fin cutting pattern as DXF for the waterjet or laser, and a run matrix taken from the simulated flight: each Mach number at the altitude where the rocket reaches it. When the CFD results are in, `import_aero_table` reads them back and every later simulation and rule check uses them.

![The exported CFD model: nose, airframe sections, base and fins as separate regions](examples/cfd-model.svg)

| Case | Mach | Altitude | Velocity | Reynolds | OpenRocket CD |
|---|---|---|---|---|---|
| M0.10 | 0.1 | 4.134 m (13.56 ft) | 34.04 m/s (111.7 ft/s) | 5.21e+06 | 1.056 |
| M0.30 | 0.3 | 36.98 m (121.3 ft) | 102.1 m/s (334.9 ft/s) | 1.56e+07 | 1.079 |
| M0.50 | 0.5 | 102.2 m (335.2 ft) | 170 m/s (557.7 ft/s) | 2.58e+07 | 1.124 |
| M0.70 | 0.7 | 201.3 m (660.5 ft) | 237.7 m/s (779.9 ft/s) | 3.58e+07 | 1.192 |
| M0.80 | 0.8 | 271.8 m (891.7 ft) | 271.5 m/s (890.6 ft/s) | 4.07e+07 | 1.235 |
| M0.90 | 0.9 | 362.2 m (1188 ft) | 305.1 m/s (1001 ft/s) | 4.54e+07 | 1.285 |
| M0.95 | 0.95 | 419.4 m (1376 ft) | 321.8 m/s (1056 ft/s) | 4.77e+07 | 1.306 |
| M1.00 | 1 | 479.8 m (1574 ft) | 338.5 m/s (1111 ft/s) | 4.99e+07 | 1.329 |
| M1.05 | 1.05 | 560.5 m (1839 ft) | 355.1 m/s (1165 ft/s) | 5.20e+07 | 1.376 |
| M1.10 | 1.1 | 662.1 m (2172 ft) | 371.6 m/s (1219 ft/s) | 5.40e+07 | 1.38 |
| M1.20 | 1.2 | 965.7 m (3168 ft) | 403.9 m/s (1325 ft/s) | 5.73e+07 | 1.33 |
| max_q | 1.165 | 939.2 m (3081 ft) | 392.4 m/s (1287 ft/s) | 5.57e+07 | 1.353 |

## Build

### 18. "We weighed the parts we've built. Here is our weigh-in sheet; how does it compare with the model? Our launch mass target is 12 kg."

<sub>Tools Claude uses: `mass_budget`</sub>

<details><summary>Weigh-in sheet (sample numbers)</summary>

```csv
part,mass (g),status,section,parent
Nose cone,491,measured,no,
Upper airframe,830,measured,no,
Lower airframe,1398,measured,no,
Fins,1052,measured,no,
Av-bay coupler,358,measured,no,
Fin fillets and tip-to-tip epoxy,140,measured,no,Lower airframe
Paint and primer,90,estimated,no,Upper airframe
```

</details>

| Part | Weigh-in | Model | Difference |  |
|---|---|---|---|---|
| Fins | 1.052 kg (2.319 lb) (measured) | 915 g (2.017 lb) | +137 g (4.831 oz) (+15%) | CHECK |
| Lower airframe | 1.398 kg (3.082 lb) (measured) | 1.271 kg (2.802 lb) | +127.1 g (4.482 oz) (+10%) | ok |
| Upper airframe | 830 g (1.83 lb) (measured) | 768.5 g (1.694 lb) | +61.53 g (2.17 oz) (+8%) | ok |
| Nose cone | 491 g (1.082 lb) (measured) | 438.8 g (15.48 oz) | +52.17 g (1.84 oz) (+12%) | CHECK |
| Av-bay coupler | 358 g (12.63 oz) (measured) | 340.5 g (12.01 oz) | +17.49 g (0.617 oz) (+5%) | ok |
| Fin fillets and tip-to-tip epoxy | 140 g (4.938 oz) (measured) |  |  |  |
| Paint and primer | 90 g (3.175 oz) (estimated) |  |  |  |

> Projected launch mass **11.63 kg (25.63 lb)**, including 5.645 kg (12.44 lb) of motor and 10% contingency on everything not weighed yet: +372.3 g (13.13 oz) under the target of 12 kg. 2 part(s) differ from the model by more than 10% (flagged). The sheet leaves out 1.468 kg of parts the model has (heaviest: Nose ballast 293 g (10.34 oz), Main 242.8 g (8.564 oz), Motor mount 186.8 g (6.589 oz)), which stay at the model's values.

### 19. "Put the weigh-ins into the design."

<sub>Tools Claude uses: `mass_budget`</sub>

> Done: 7 changes (mass overrides on the weighed parts, the fillets and paint added as mass components). Launch mass 10.85 kg (23.91 lb) -> 11.47 kg (25.29 lb), CG 1571 mm (61.84 in) -> 1564 mm (61.57 in), stability 3.999 -> 4.067 cal. Every later step flies the rocket as built; `undo` takes it back in one step.

## Reviews

### 20. "Where do we stand? What's left before CDR?"

<sub>Tools Claude uses: `design_status`</sub>

> **In progress: 2 thing(s) to set up, 6 warning(s).** Launch Canada 2027: DTEG Revision 4 + LC 2027 DTEG and R&R Edicts (to become DTEG R5): No failures; 5 warning(s).

| Area | Status | Finding |
|---|---|---|
| Rules | WARN | Within the models' range: OpenRocket warns: Body calculations may not be entirely accurate at supersonic speeds. |
| Launch site | WARN | Launch site altitude: 0 m (0 ft) used by this simulation |
| Stability | WARN | Ascent stability (maximum, over-stability): 5.721 cal (R10.3.1, R10.4.1) |
| Stability | WARN | Ascent stability in 8.333 m/s (27.34 ft/s) wind (maximum, over-stability): 5.722 cal (R10.3.1, R10.4.1) |
| Rules | WARN | Maximum Mach number: 1.135 |
| Electronics | WARN | GPS tracker (1S LiPo 500 mAh): less than 30% to spare: a delay on the pad or a long search uses it up |
| Standards | TODO | The team standards were changed in this session but are not saved to a file, so the next session and the rest of the team will not have them. |
| File | TODO | Unsaved changes (22 edit(s) this session, undo available). |

Next steps, in order:

1. check_requirements for the details and rule reference.
2. update_standards launchSite.altitudeMsl (and weather_forecast on the day).
3. optimize_fins (smaller fins) or less nose ballast.
4. power_budget for the details.
5. update_standards with saveTo (e.g. openrocket-mcp.json), then commit the file.
6. save_design once the team agrees (history shows what changed).

### 21. "Make the design review package."

<sub>Tools Claude uses: `generate_report`</sub>

Claude writes a folder with `report.md` (requirement checks, vehicle, flight, stability, recovery, wind table, methods), the stability-vs-time plots Launch Canada asks for (DTEG R10.3.2), the drawing, the flight profile and a CSV of the flight data.

![Stability margin during the ascent with the rule minimum](examples/stability-ascent.svg)

### 22. "What changed since the version we showed at PDR?"

<sub>Tools Claude uses: `compare_designs`</sub>

- launch mass: 9.978 kg (22 lb) -> 11.47 kg (25.29 lb) (+1.494 kg (3.294 lb) (+14.97%))
- static stability at launch: 2.66 cal -> 4.067 cal (+1.407 cal)
- apogee: 3063 m (10050 ft) -> 3007 m (9867 ft) (-56.01 m (183.8 ft) (-1.828%))
- max Mach: 1.133 -> 1.135 (+0.001898)
- rail exit velocity: 39.39 m/s (129.2 ft/s) -> 39.79 m/s (130.5 ft/s) (+0.401 m/s (1.316 ft/s) (+1.018%))
- min stability in flight: 2.08 cal -> 3.593 cal (+1.513 cal)
- 9 rule check(s) changed status, 2 got worse
- 10 component(s) added, removed or edited

- **Nose cone (Nose Cone)**: massOverridden false -> true; overrideMass 438.8 g (15.48 oz) -> 491 g (1.082 lb)
- **Upper airframe (Body Tube)**: massOverridden false -> true; overrideMass 768.5 g (1.694 lb) -> 830 g (1.83 lb)
- **Main (Parachute)**: area 1.824 m2 (19.63 ft2) -> 3.575 m2 (38.48 ft2); cD 0.8 -> 0.85; diameter 1524 mm (60 in) -> 2134 mm (84 in)
- **Av-bay coupler (Tube Coupler)**: massOverridden false -> true; overrideMass 340.5 g (12.01 oz) -> 358 g (12.63 oz)

## Launch day

### 23. "We launch at 48.47, -81.33 on August 21 at 3 pm. What will the winds do, and make us the flight card."

<sub>Tools Claude uses: `weather_forecast`, `flight_card`</sub>

> Ground wind 6.75 m/s (22.15 ft/s) (24.3 km/h) from 250 deg, gusts 12 m/s (39.37 ft/s) (43.2 km/h) (mean wind within 30 km/h limit; gusts exceed it). Flown in the forecast winds aloft: apogee 3029 m (9937 ft), landing 109.6 m (359.5 ft) from the pad.

<sub>(A recorded forecast is used here so the page is reproducible; with internet access Claude fetches the live one from Open-Meteo.)</sub>

Winds aloft from the forecast, flown as a wind profile:

- 10 m (32.81 ft) AGL: 6.75 m/s (22.15 ft/s) from 250 deg
- 180 m (590.6 ft) AGL: 9.79 m/s (32.12 ft/s) from 255 deg
- 690 m (2264 ft) AGL: 5.65 m/s (18.54 ft/s) from 262.5 deg
- 2710 m (8891 ft) AGL: 9.02 m/s (29.59 ft/s) from 267.5 deg
- 6880 m (22572 ft) AGL: 15.97 m/s (52.4 ft/s) from 278 deg
- 11480 m (37664 ft) AGL: 23.63 m/s (77.53 ft/s) from 289.5 deg

From the one-page flight card (predictions, motor delay, deployment settings, drift in each wind, rule check and sign-off lines):

> **Predicted flight**
>
> | | |
> |---|---|
> | Apogee | 3029 m (9937 ft) |
> | Time to apogee | 20.93 s |
> | Max velocity | 389 m/s (1276 ft/s) |
> | Max Mach | 1.14 |
> | Max acceleration | 142.2 m/s2 (466.6 ft/s2) = 14.5 G |
> | Rail exit velocity | 39.78 m/s (130.5 ft/s) |
> | Thrust-to-weight | 12.65 |

### 24. "Where should the rail buttons go to get the most out of the rail?"

<sub>Tools Claude uses: `rail_buttons`</sub>

> The aft button goes 15 mm above the aft end and the forward one 955.7 mm up, 940.7 mm apart. The rocket leaves the rail at 39.43 m/s instead of 39.3 m/s with today's layout. OpenRocket says 39.78 m/s because it counts the whole rail and ignores where the buttons are.

|  | Current | Recommended |
|---|---|---|
| Forward button | 660.4 mm (26 in) above the aft end (1575 mm (62 in) from the nose tip) (no backing: add a block) | 955.7 mm (37.62 in) above the aft end (1280 mm (50.38 in) from the nose tip) (over Av-bay coupler) |
| Aft button | 50.8 mm (2 in) above the aft end (2184 mm (86 in) from the nose tip) (no backing: add a block) | 15 mm (0.5906 in) above the aft end (2220 mm (87.41 in) from the nose tip) (no backing: add a block) |
| Rail exit | 39.3 m/s (128.9 ft/s) | 39.43 m/s (129.4 ft/s) |
| Tip-off | 1.684 deg/s pitch rate, 0.01342 deg, over 15.93 ms on the aft button alone | 2.746 deg/s pitch rate, 0.03427 deg, over 24.96 ms on the aft button alone |
| Pointing error at rail exit | 0.1074 deg (tip-off + slop) | 0.09518 deg (tip-off + slop) |
| Side loads | forward 24.08 N (5.413 lbf), aft 23.15 N (5.203 lbf) | forward 17.14 N (3.853 lbf), aft 29.08 N (6.537 lbf) |

- 'Centering ring 1' is 11.83 mm (0.4656 in) aft of the aft button: move it under the button, or glue a block there, so the screw bites into it.
- Put a backing block or ring under every button without one (glued inside the tube), or the screw only holds in the tube wall.

Say "move them" and Claude applies it to the design (`apply`); `undo` takes it back.

## Show it off

### 25. "Make an exploded view for our design review poster, with the parts list."

<sub>Tools Claude uses: `render_3d`</sub>

![Exploded 3-D view of Maple 10K: airframe pieces pulled apart, fins slid out, every internal part laid out below the piece it goes in, with numbered balloons and a parts list with masses](examples/exploded.png)

37 parts, each with OpenRocket's mass (the weighed values from the mass budget). The heaviest: M1315W (5.645 kg), Lower airframe (1.398 kg), Fins (1.052 kg). Ask for `cutaway` to see them in place instead.

### 26. "Animate the flight for our social media post."

<sub>Tools Claude uses: `animate_flight`</sub>

![3-D animation of the simulated flight with the flight clock, altitude, speed, Mach and distance from the pad, captions at burnout, apogee and each deployment](examples/flight.gif)

A 19.8 s loop (GIF; an MP4 too when ffmpeg is installed), and a sheet of stills that Claude shows in the chat:

![Key moments: liftoff, burnout, apogee, drogue, main, touchdown](examples/flight-keyframes.png)

Real time through the burn, slowed down around apogee and each deployment; the coast and the long descent are sped up, with the rate on screen. Events on the timeline:

| Event | Flight time | In the video |
|---|---|---|
| Liftoff | 0.06 s | 1.3 s |
| Rail clear | 0.345 s | 1.6 s |
| Mach 1 | 2.882 s | 4.1 s |
| Max velocity | 4.049 s | 5.3 s |
| Motor burnout | 5.949 s | 7.2 s |
| Apogee | 20.98 s | 10.6 s |
| Drogue out | 20.98 s | 10.6 s |
| Main out | 114.4 s | 14.1 s |
| Touchdown | 160 s | 16.3 s |

### 27. "We're also flying a two-stage rocket. Simulate the staging, show it pulled apart and animate it."

<sub>Tools Claude uses: `run_simulation`, `render_3d`, `animate_flight`</sub>

> The sustainer lights at 1.535 s, 62.83 m up at 67.42 m/s and 4.759 deg off vertical (thrust-to-weight 10.27); apogee 674.9 m.
> - Sustainer: lands 36.18 m from the pad at 6.356 m/s.
> - Booster: lands 32.44 m from the pad at 8.448 m/s.

![Exploded view of a two-stage rocket: the sustainer and booster bracketed with their masses, every part numbered](examples/two-stage-exploded.png)

Each stage is followed through the whole flight. The captions name the stage (booster burnout, booster separation, sustainer ignition, sustainer burnout). A booster camera in the lower left follows the dropped booster down under its own chute. The trajectory inset draws its path in orange, and the flight summary lists where each stage lands.

![Two-stage flight animation with the booster camera](examples/two-stage-flight.gif)

![Key moments of the two-stage flight: liftoff, booster separation, sustainer burnout, apogee, main, touchdown](examples/two-stage-flight-keyframes.png)

## After the flight

*"Here is our altimeter file — how did we do compared with the prediction?"* Claude lines the log up with the simulation (`compare_flight`), fits the drag so the next prediction is closer, and plots simulated against measured altitude.

## Team history

### 28. "Here's our folder of past rockets and flight logs. Which 4 in rockets have we flown, and how good were our apogee predictions over the years?"

<sub>Tools Claude uses: `design_library`</sub>

> 4 designs (2023-2026), 2 flight logs. 3 of them are 4 in rockets:

| Rocket | File | Year | Motors | Predicted apogee |
|---|---|---|---|---|
| Maple 10K | 2026/maple/maple-10k.ork | 2026 | M1315W (M, 6645 Ns (1494 lbf-s)) | 3029 m (9937 ft) (simulated now ('MCP - [L1250DM-P]')) |
| Maple 10K | 2025/maple-pdr/maple-pdr.ork | 2025 | L1250DM (L, 4400 Ns (989.2 lbf-s)) | 3122 m (10243 ft) (simulated now in OpenRocket's default conditions (no simulation in the file)) |
| Two stage high power rocket | 2024/two-stage.ork | 2024 | H148R, H148R (I, 430.4 Ns (96.75 lbf-s)) | 675 m (2214 ft) (saved result of 'Simulation 1') |

Each altimeter log is matched to its design by name or folder:

| Year | Rocket | Predicted | Measured | Error |
|---|---|---|---|---|
| 2023 | Dual parachute deployment | 897.5 m (2944 ft) | 790 m (2592 ft) | +13.6% |
| 2024 | Two stage high power rocket | 675 m (2214 ft) | 609.9 m (2001 ft) | +10.67% |

> Overall, predictions over-estimated apogee by 12.13% on average over 2 flights (scatter 2.071%). Scale this year's predicted apogee by 0.8918 for a first estimate, and calibrate the drag with compare_flight on the closest past rocket's log.

<sub>(The two flight logs are samples made for this page; point Claude at your own folder of `.ork` / `.rkt` files and altimeter CSVs.)</sub>

Ask for `similarTo` to find the past rocket closest to the one you are designing, then open it to reuse its parts.

## Electronics

### 29. "Will our flight computer's sensors cope with this flight, and what do we set the altimeters to?"

<sub>Tools Claude uses: `sensor_check`, `altimeter_settings`</sub>

> 0 fail, 3 warn, 8 ok. Against the team's sensors (typical student parts, set in the standards):

| Sensor | Checked | Flight peak | Use of range |  |
|---|---|---|---|---|
| Accelerometer, ±16 g | axial acceleration (x), boost and coast | 15.45 g at t=0.815 s (boost) | 96.59% of range | WARN |
| Accelerometer, ±16 g | lateral acceleration (y, z) | 0.688 g at t=1.75 s (boost) | 4.3% of range | PASS |
| Accelerometer, ±16 g | parachute openings | 17.59 g at t=114.4 s | 110% of range | WARN |
| High-g accelerometer, ±200 g | axial acceleration (x), boost and coast | 15.45 g at t=0.815 s (boost) | 7.727% of range | PASS |
| High-g accelerometer, ±200 g | lateral acceleration (y, z) | 0.688 g at t=1.75 s (boost) | 0.344% of range | PASS |
| High-g accelerometer, ±200 g | parachute openings | 17.59 g at t=114.4 s | 8.796% of range | PASS |
| Gyroscope, ±2000 deg/s | roll rate (x) | 0.0008148 deg/s at t=5.19 s | 4.074e-05% of range | PASS |
| Gyroscope, ±2000 deg/s | pitch / yaw rate (y, z) | 35.54 deg/s at t=0.58 s | 1.777% of range | PASS |
| Barometer, 30-125 kPa | pressure (lowest at apogee) | 68.21 kPa (9.893 psi) at t=20.93 s |  | PASS |
| Barometer, 30-125 kPa | static pressure above Mach 0.7 | t=1.84 to 6.95 s (max Mach 1.14) |  | WARN |
| GPS receiver | speed and altitude (export limits 515 m/s, 18 km) | 389 m/s (1276 ft/s), 3329 m (10921 ft) MSL |  | PASS |

The altimeter card:

| Parachute | Primary altimeter | Backup altimeter |
|---|---|---|
| Main | 304.8 m (1000 ft) AGL on the way down | 274.3 m (900 ft) AGL on the way down |
| Drogue | apogee | apogee + 1 s |

> Mach lockout: 8 s after launch (above Mach 0.7 until t=6.911 s, plus 1 s). Static ports: bay volume 129 in3: 4 ports of 0.142 in (3.6 mm) evenly around the switch band (same area as 1.3 x 1/4 in).

### 30. "Give us simulated sensor data to test our flight software."

<sub>Tools Claude uses: `sensor_data`</sub>

> 16802 rows x 22 columns at 100 Hz (168 s: 5 s on the pad first), plus the true event times in a separate file. A few rows:

|  | time_s | acc_x_g (±16 g) | acc_hi_x_g (±200 g) | baro_pressure_pa | truth_altitude_agl_m | truth_phase |
|---|---|---|---|---|---|---|
| on the pad | 1.0000 | 0.99150 | 0.83300 | 97831.12 | 0.00 | pad |
| boost | 6.0600 | 15.02100 | 15.04300 | 97054.54 | 69.14 | boost |
| burnout | 11.1500 | -4.85300 | -4.85100 | 80933.70 | 1621.62 | coast |
| apogee | 25.9800 | -0.07600 | 0.04900 | 68213.02 | 3028.68 | coast |
| under the main | 155.0000 | -0.99050 | -0.83300 | 97095.44 | 65.18 | descent |

The ±16 g part comes within 3% of clipping at motor start, so a hotter motor or vibration would saturate it; the ±200 g part keeps the boost on scale. Under the main both read about −1 g (the canopy holding the rocket). Columns follow the team's sensors in the standards; pass your own parts with `sensors`.

### 31. "Do our batteries last a two-hour pad wait, and will we hear the GPS tracker all the way down?"

<sub>Tools Claude uses: `power_budget`, `radio_link`</sub>

> 2 h on the pad + 2.667 min flight + 2 h until found. Each circuit against its battery (the team's parts, set in the standards):

| Circuit | Needs | Usable | Runs for |  |
|---|---|---|---|---|
| Primary altimeter (9 V alkaline) | 48.53 mAh (12 mA for 4.044 h) | 440 mAh usable (550 mAh x 0.8) | 36.67 h on the pad and after | PASS |
| Backup altimeter (2S LiPo 300 mAh) | 48.53 mAh (12 mA for 4.044 h) | 240 mAh usable (300 mAh x 0.8) | 20 h on the pad and after | PASS |
| GPS tracker (1S LiPo 500 mAh) | 364 mAh (90 mA for 4.044 h) | 400 mAh usable (500 mAh x 0.8) | 4.444 h on the pad and after | WARN |

E-match: 2.5 A through each e-match (1.6 ohm match, 1.7 ohm battery, 0.3 ohm wiring); the battery holds 4.75 V (from 9 V) while firing.

> Link: 915 MHz, 20 dBm, antennas 2.15 / 2.15 dBi, receiver -123 dBm, losses 3 dB: 144.3 dB to spend on the path.

| Flight | Farthest in flight | After landing |  |
|---|---|---|---|
| Sustainer | 3176 m (10420 ft) at t=21.63 s (3026 m (9929 ft) up): +42.59 dB margin | 109.6 m (359.5 ft) away on the ground: +48.73 dB margin | PASS |

Ground range: 1019 m (3342 ft) with 10 dB margin once landed (ground station antenna 2 m (6.562 ft) up, rocket antenna 0.1 m (0.3281 ft) off the ground). In the air the path is clear, so free-space loss rules; on the ground the two antennas are near the earth and the signal falls off with the fourth power of distance, which is why a tracker heard at apogee can go quiet once it lands.

## Parachutes and product links

### 32. "Plot our main's size against descent rate for Cd 0.85, 1.5 and 2.2."

<sub>Tools Claude uses: `parachute_sizes`</sub>

> 8.022 kg (17.69 lb) (simulated mass under Main (Sustainer)); the drogue stays attached under the main, so 0.2076 m2 of drag is counted (calibrated from the simulation). Target 6.096 m/s, below 9.144 m/s (30 ft/s) (R4.3.1).

![Descent rate against canopy diameter for three drag coefficients, with the rule limit, the target, catalog parachutes and the current main](examples/parachute-sizes.svg)

| Cd | Size for the target |
|---|---|
| 0.85 | 2298 mm (90.47 in) |
| 1.5 | 1730 mm (68.1 in) |
| 2.2 | 1428 mm (56.23 in) |

Catalog parachutes closest to the target (each at its own Cd):

| Parachute | Diameter | Cd | Rate |
|---|---|---|---|
| Rocketman PA-07 | 2134 mm (84 in) | 0.99 | 6.084 m/s (19.96 ft/s) |
| Rocketman HX-096 | 2438 mm (96 in) | 0.75 | 6.115 m/s (20.06 ft/s) |
| Spherachutes HS-096-CL | 2438 mm (96 in) | 0.75 | 6.115 m/s (20.06 ft/s) |

`apply: true` with one Cd sets the main to that size (rounded up to a whole inch) and re-flies the rocket.

### 33. "Here's the chute we want to buy: put it in as our main."

<sub>Tools Claude uses: `add_from_link`</sub>

|  | Value (source) |
|---|---|
| diameter | 2134 mm (84 in) (page) |
| cd | 2.2 (catalog: Fruity Chutes IFC-084-S) |
| mass | 379.9 g (13.4 oz) (catalog: Fruity Chutes IFC-084-S) |
| packedDiameter | 99.06 mm (3.9 in) (catalog: Fruity Chutes IFC-084-S) |
| packedLength | 157.5 mm (6.2 in) (catalog: Fruity Chutes IFC-084-S) |

> Main -> Iris Ultra 84" Compact Parachute: descent rate 6.536 m/s (21.44 ft/s) -> 4.156 m/s (13.64 ft/s) (simulated). The page named the maker and the size; Cd, weight and packed size came from the matching catalog entry (Fruity Chutes IFC-084-S).

<sub>(The product text here is a sample written for this page. With a link, the server reads the shop's page itself; when a shop blocks that, paste the page's text.)</sub>

## Explore it in the chat

### 34. "Show us the rocket and its flight so we can click through it."

<sub>Tools Claude uses: `show_design`</sub>

> Apogee 3028 m; Max speed 386.6 m/s; Rail exit 39.26 m/s; Stability (ascent) 2.87–5.83 cal; Max accel. 140.5 m/s2; Drogue descent 29.24 m/s; Iris Ultra 84" Compact Parachute descent 4.156 m/s; Landing 163.6 m. Rule checks: No failures; 5 warning(s).

In Claude Desktop and claude.ai the view opens in the chat. Anywhere else, open the HTML file it writes: [this one](examples/maple-10k-view.html) is the Maple 10K (download it and open it in a browser; it needs no internet).

- **Rocket:** drag to turn it, scroll to zoom. *Cut-away* opens the airframe, *Exploded* pulls the sections apart, *Side* is a view to scale with a ruler. CG and CP move as you scrub through the flight. Click a part to see its properties; in Claude, click a value to change it (the flight is re-simulated, and `undo` takes it back).
- **Fly:** replays the simulated flight in 3-D with the parachutes, following the rocket or the whole trajectory.
- **Flight plots:** altitude, speed, acceleration, Mach, stability, thrust and drag, angle of attack and mass, with the events marked and one time cursor across all of them (drag to zoom).
- **Trajectory** and **Rule checks:** the profile and ground track with the wind, and every check with its rule reference (in Claude, *Ask Claude how to fix* on a failure).

<p><img src="examples/view-rocket.png" alt="The design view: exploded cut-away 3-D model with the main parachute selected and its properties" width="49%"> <img src="examples/view-side.png" alt="Side view to scale at burnout with CG, CP, the margin and a ruler" width="49%"></p>
<p><img src="examples/view-flight.png" alt="Linked flight plots with the time cursor at maximum velocity" width="49%"> <img src="examples/view-fly.png" alt="3-D replay under the main parachute" width="49%"></p>

<sub>(Screenshots by [`scripts/view_shots.mjs`](../scripts/view_shots.mjs), which also checks the view in a headless browser.)</sub>
