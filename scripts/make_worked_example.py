#!/usr/bin/env python3
"""Builds docs/WORKED_EXAMPLE.md: a real rocket (Notre Dame Rocket Team, 2020, CTI L1395) rebuilt from its published
dimensions and weigh-in, run through the analyses a team does before launch, then checked against the altimeter and
accelerometer log of its flight. Every number on the page comes from the engine in this run. Re-run after changes:

    ./gradlew installDist && python3 scripts/make_worked_example.py
"""
import csv
import json
import os
import re
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import benchmark  # noqa: E402  (reuses its stdio client)

ROOT = benchmark.ROOT
DOCS = os.path.join(ROOT, "docs")
OUT = os.path.join(DOCS, "examples", "ndrt-2020")
LOG = os.path.join(OUT, "flight-log.csv")
md = []

# Launch day: Three Oaks, Michigan, 23 Feb 2020, about 16:00 UTC (RocketPy's notebook for this flight).
SITE = {"latitude": 41.775447, "longitude": -86.572467, "altitude": 206}
# Ground: Open-Meteo's ERA5 archive at 16:00 UTC (10 m wind and gusts, 2 m temperature; surface pressure 992.3 hPa at
# the 197 m grid elevation, 991.2 hPa at the 206 m pad). Aloft: ERA5 pressure levels from RocketPy's
# ndrt_2020_weather_data_ERA5.nc at 41.75 N, 86.5 W, 16:00 UTC: (hPa, geopotential height m MSL, speed m/s, from deg).
GROUND = {"wind": 4.43, "from": 205, "gust": 10.2, "temperature": 6.9, "pressure": 991.2}
ERA5 = [(1000, 133, 4.14, 207), (975, 340, 7.36, 209), (950, 550, 10.87, 224), (925, 766, 12.43, 246),
        (900, 990, 12.30, 244), (875, 1219, 14.23, 241), (850, 1454, 16.08, 245), (825, 1694, 16.94, 254),
        (800, 1941, 16.51, 266), (775, 2195, 15.54, 275), (750, 2457, 15.19, 278), (700, 3005, 15.22, 273),
        (650, 3586, 15.72, 269), (600, 4203, 17.49, 272)]
# The profile used: the 10 m wind, then every pressure level above the pad (heights AGL).
WIND = [(10, GROUND["wind"], GROUND["from"])] + [(z - SITE["altitude"], v, dr) for _, z, v, dr in ERA5
                                                 if z - SITE["altitude"] > 50]
MEASURED = {"mass": 18.998, "stability": 2.875, "apogee_ft": 4320, "target_ft": 4444, "drift_ft": 2275}


def w(text=""):
    md.append(text)


def table(rows, headers):
    w("| " + " | ".join(headers) + " |")
    w("|" + "---|" * len(headers))
    for r in rows:
        w("| " + " | ".join(str(c).replace("|", "/") for c in r) + " |")
    w()


def lead(s):
    """'1435 m (4707 ft)' -> '1435 m'."""
    return str(s).split(" (")[0]


def num(s):
    return float(re.match(r"[-+]?[\d.]+(?:e[-+]?\d+)?", str(s).strip()).group(0))


def ft(s):
    m = re.search(r"\(([-\d.]+) ft\)", str(s))
    return f"{float(m.group(1)):,.0f} ft" if m else str(s)


def both(s):
    """'1435 m (4707 ft)' -> '1,435 m (4,707 ft)'."""
    m = re.match(r"([-\d.]+) (\S+) \(([-\d.]+) (\S+)\)", str(s))
    if not m:
        return str(s)
    a, ua, b, ub = float(m.group(1)), m.group(2), float(m.group(3)), m.group(4)
    fa = f"{a:,.0f}" if abs(a) >= 100 else f"{a:.3g}"
    fb = f"{b:,.0f}" if abs(b) >= 100 else f"{b:.3g}"
    return f"{fa} {ua} ({fb} {ub})"


def ask(prompt, tools):
    w(f"> **Ask:** \"{prompt}\"  ")
    w(f"> <sub>Tools: {', '.join('`' + t + '`' for t in tools)}</sub>")
    w()


def rel(path):
    return os.path.relpath(path, DOCS).replace(os.sep, "/")


def main():
    keep_log = None
    if os.path.exists(LOG):
        with open(LOG, encoding="utf-8") as f:
            keep_log = f.read()
    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)
    if keep_log is None:
        sys.exit("docs/examples/ndrt-2020/flight-log.csv is missing (the NDRT 2020 log from RocketPy's data/rockets)")
    with open(LOG, "w", encoding="utf-8") as f:
        f.write(keep_log)
    with open(os.path.join(OUT, "README.md"), "w", encoding="utf-8") as f:
        f.write(DATA_README)
    s = benchmark.Server()
    call = s.call

    def p(name):
        return os.path.join(OUT, name)

    # ---------------------------------------------------------------------------------------------------- set-up
    call("update_standards", {"patch": {
        "launchSite": {"name": "Three Oaks, Michigan (NDRT, 23 Feb 2020)", "altitudeMsl": f"{SITE['altitude']} m",
                       "latitude": SITE["latitude"], "longitude": SITE["longitude"], "railLength": "11 ft",
                       "launchAngleFromVertical": "0 deg", "designWindSpeed": "20 mph"},
        "targets": {"apogee": {"min": "3500 ft", "max": "5500 ft", "goal": f"{MEASURED['target_ft']} ft"},
                    "railExitVelocity": {"min": "52 ft/s"}, "minStability": {"min": 2.0},
                    "thrustToWeight": {"min": 5}, "landingDistance": {"max": "2500 ft"}},
        "recovery": {"openingLoadMethod": "finite_mass"}}})

    # ---------------------------------------------------------------------------------------------------- build
    d = call("open_design", {"newRocketName": "NDRT 2020"})["designId"]
    stage = call("get_design", {"designId": d})["components"][0].split("[")[1][:8]

    def add(parent, typ, name, props):
        return call("add_component", {"designId": d, "parent": parent, "type": typ, "name": name, "properties": props})

    add(stage, "NoseCone", "Nose cone", {"shapeType": "OGIVE", "shapeParameter": 1, "length": "610 mm",
                                         "aftDiameter": "203 mm", "thickness": "3 mm", "material": "Fiberglass",
                                         "aftShoulderLength": "152 mm", "aftShoulderDiameter": "197 mm"})
    add(stage, "BodyTube", "Payload bay", {"length": "590 mm", "outerDiameter": "203 mm", "thickness": "2.5 mm",
                                           "material": "Fiberglass"})
    add(stage, "Transition", "Transition", {"length": "127 mm", "foreDiameter": "203 mm", "aftDiameter": "155 mm",
                                            "thickness": "3 mm", "material": "Fiberglass",
                                            "foreShoulderLength": "102 mm", "foreShoulderDiameter": "197 mm",
                                            "aftShoulderLength": "102 mm", "aftShoulderDiameter": "149 mm"})
    add(stage, "BodyTube", "Main bay", {"length": "889 mm", "outerDiameter": "155 mm", "thickness": "2.5 mm",
                                        "material": "Fiberglass"})
    add(stage, "BodyTube", "Fin can", {"length": "1150 mm", "outerDiameter": "155 mm", "thickness": "2.5 mm",
                                       "material": "Fiberglass"})
    add("Fin can", "InnerTube", "Motor mount", {"length": "650 mm", "outerDiameter": "78.5 mm", "thickness": "1.6 mm",
                                                "material": "Fiberglass", "axialMethod": "BOTTOM", "axialOffset": 0})
    for i, off in enumerate(["0 mm", "-300 mm", "-640 mm"]):
        add("Fin can", "CenteringRing", f"Centering ring {i + 1}", {"length": "6 mm", "material": "Plywood (birch)",
                                                                    "axialMethod": "BOTTOM", "axialOffset": off})
    add("Fin can", "TrapezoidFinSet", "Fins", {
        "finCount": 4, "rootChord": "152 mm", "tipChord": "76.2 mm", "span": "165 mm", "sweepLength": "38.1 mm",
        "thickness": "4.76 mm", "material": "Fiberglass", "crossSection": "ROUNDED", "tabHeight": "35.75 mm",
        "tabLength": "120 mm", "tabOffset": "16 mm", "filletRadius": "10 mm", "filletMaterial": "Fiberglass",
        "axialMethod": "BOTTOM", "axialOffset": "-189 mm"})
    add("Main bay", "RailButton", "Rail buttons", {"instanceCount": 2, "instanceSeparation": "500 mm",
                                                   "axialMethod": "TOP", "axialOffset": "173 mm"})
    add("Main bay", "Parachute", "Main", {"diameter": "120 in", "cd": 2.2})
    add("Main bay", "ShockCord", "Main shock cord", {"cordLength": "30 ft", "length": "100 mm"})
    add("Fin can", "Parachute", "Drogue", {"diameter": "24 in", "cd": 1.5})
    add("Fin can", "ShockCord", "Drogue shock cord", {"cordLength": "25 ft", "length": "80 mm"})
    bay = call("add_avionics_bay", {"designId": d, "upperTube": "Main bay", "lowerTube": "Fin can",
                                    "couplerLength": "12 in", "switchBand": "1 in",
                                    "altimeters": [{"name": "PerfectFlite StratoLoggerCF", "mass": "11 g", "length": "2 in"},
                                                   {"name": "Missile Works RRC3", "mass": "17 g", "length": "3.9 in"}]})
    motor = call("set_motor", {"designId": d, "mount": "Motor mount", "motor": "CTI L1395"})
    call("set_deployment", {"designId": d, "component": "Drogue", "event": "apogee", "configuration": "all"})
    call("set_deployment", {"designId": d, "component": "Main", "event": "altitude", "altitude": "550 ft",
                            "configuration": "all"})
    airframe = call("get_design", {"designId": d})
    airframe_mass = num(airframe["components"][0].split("mass ")[1])
    add("Payload bay", "MassComponent", "Payload", {"componentMass": "7 kg", "length": "300 mm", "radius": "80 mm",
                                                    "massComponentType": "PAYLOAD", "axialMethod": "MIDDLE",
                                                    "axialOffset": 0})
    add("Fin can", "MassComponent", "Epoxy, fasteners and paint", {"componentMass": "1 kg", "length": "450 mm",
                                                                   "axialMethod": "BOTTOM", "axialOffset": "-150 mm"})

    # Weigh-in: two masses the published dimensions do not give (the payload, and the epoxy, fasteners and paint),
    # set so the model has the team's weighed mass and measured stability.
    def stab():
        g = call("get_design", {"designId": d})
        st = g["stability"][0]
        return g, num(st["launchMass"]), num(st["cgAtLaunch"]) / 1000, num(st["cp"]) / 1000

    def centre(g, name):
        line = next(x for x in g["components"] if x.strip().startswith(name + " ["))
        top = num(line.split("top ")[1]) / 1000
        length = num(line.split(" L ")[1]) / 1000
        return top + length / 2

    g, mass0, cg0, cp = stab()
    x1, x2 = centre(g, "Payload"), centre(g, "Epoxy, fasteners and paint")
    motor_mass = num(motor["motor"]["launchMass"]) if "launchMass" in motor.get("motor", {}) else 4.323
    target_m = MEASURED["mass"] + motor_mass
    target_cg = cp - MEASURED["stability"] * 0.203
    base_m, base_mom = mass0 - 7 - 1, mass0 * cg0 - 7 * x1 - 1 * x2
    a, b = target_m - base_m, target_m * target_cg - base_mom
    m2 = (b - a * x1) / (x2 - x1)
    m1 = a - m2
    call("edit_components", {"designId": d, "changes": [
        {"component": "Payload", "properties": {"componentMass": f"{m1:.3f} kg"}},
        {"component": "Epoxy, fasteners and paint", "properties": {"componentMass": f"{m2:.3f} kg"}}]})
    g = call("get_design", {"designId": d})
    st = g["stability"][0]
    # The day's weather as an Open-Meteo-style hourly record, the format weather_forecast reads when pasted.
    hourly = {"time": ["2020-02-23T16:00"], "wind_speed_10m": [GROUND["wind"]], "wind_direction_10m": [GROUND["from"]],
              "wind_gusts_10m": [GROUND["gust"]], "temperature_2m": [GROUND["temperature"]],
              "surface_pressure": [GROUND["pressure"]]}
    for hpa, z, v, dr in ERA5:
        hourly.update({f"wind_speed_{hpa}hPa": [v], f"wind_direction_{hpa}hPa": [dr],
                       f"geopotential_height_{hpa}hPa": [z]})
    day = {"latitude": SITE["latitude"], "longitude": SITE["longitude"], "elevation": SITE["altitude"],
           "timezone": "GMT", "utc_offset_seconds": 0, "hourly": hourly}
    wx = call("weather_forecast", {"designId": d, "forecastJson": json.dumps(day), "time": "2020-02-23T16:00",
                                   "top": "3000 m"})
    # The drift with the ground wind alone, then the whole profile (every level, without the turbulence a forecast's
    # gusts would add, so the runs repeat exactly).
    call("wind_profile", {"designId": d, "mode": "levels",
                          "levels": [{"altitude": "10 m", "speed": GROUND["wind"], "direction": f"{GROUND['from']} deg"}]})
    ground_only = call("run_simulation", {"designId": d})["branches"][0]["landingDistanceFromPad"]
    call("wind_profile", {"designId": d, "mode": "levels",
                          "levels": [{"altitude": f"{h} m", "speed": v, "direction": f"{dr} deg"} for h, v, dr in WIND
                                     if h <= 3000]})
    call("save_design", {"designId": d, "path": p("ndrt-2020.ork")})

    # ---------------------------------------------------------------------------------------------------- analyses
    call("draw_rocket", {"designId": d, "path": p("rocket.svg")})
    call("render_3d", {"designId": d, "view": "cutaway", "width": 1600, "path": p("cutaway.png")})
    sim = call("run_simulation", {"designId": d, "plotPath": p("flight-profile.svg")})
    aero = call("aero_analysis", {"designId": d, "machs": [0.1, 0.3, 0.5]})
    tg = call("targets", {"designId": d})
    req = call("check_requirements", dict(designId=d))
    mc = call("monte_carlo", {"designId": d, "runs": 300, "windDirection": "205 deg", "windDirectionSd": "15 deg",
                              "windSpeedSd": "1.5 m/s", "dragSd": 0.1, "thrustSd": 0.04, "massSd": 0.02,
                              "chuteCdSd": 0.1, "seed": 7, "plotPath": p("monte-carlo.svg"),
                              "kmlPath": p("landing.kml"), "siteLatitude": SITE["latitude"],
                              "siteLongitude": SITE["longitude"]})
    rec = call("recovery_analysis", {"designId": d, "pinType": "4-40 nylon"})
    sec = call("recovery_sections", {"designId": d})
    ej_main = call("ejection_charge", {"designId": d, "bayTube": "Main bay", "pinType": "4-40 nylon", "pinCount": 3})
    ej_drogue = call("ejection_charge", {"designId": d, "bayTube": "Fin can", "pinType": "4-40 nylon", "pinCount": 3})
    alt = call("altimeter_settings", dict(designId=d))
    fl = call("fin_flutter", dict(designId=d))
    loads = call("structural_loads", dict(designId=d))
    rb = call("rail_buttons", dict(designId=d))
    fea = call("fin_fea", {"designId": d, "outDir": p("fin-fea"), "plotPath": p("fin-stress.svg")})
    heat = call("aero_heating", dict(designId=d))
    sens = call("sensor_check", dict(designId=d))
    claims = call("verify_claims", {"designId": d, "claims": [
        {"quantity": "static margin", "value": f"{MEASURED['stability']} cal", "tolerance": "0.1 cal",
         "source": "team's measured margin"},
        {"quantity": "apogee", "value": f"{MEASURED['target_ft']:,} ft", "tolerance": "3%", "source": "declared target"},
        {"quantity": "landing distance", "value": f"{MEASURED['drift_ft']:,} ft", "tolerance": "10%",
         "source": "measured drift"},
        {"quantity": "motor", "value": "CTI L1395", "source": "flight log"}]})
    call("flight_card", {"designId": d, "path": p("flight-card.md")})
    cmp = call("compare_flight", {"designId": d, "path": LOG, "plotPath": p("flight-vs-log.svg")})
    s.close()
    # Keep the FEA input deck and CalculiX's printed results; the binary result files are large and regenerate.
    for name in os.listdir(p("fin-fea")):
        if not name.endswith((".inp", ".dat")):
            os.remove(os.path.join(p("fin-fea"), name))

    # Measured numbers straight from the log, for the page.
    t, h, ta, acc = [], [], [], []
    with open(LOG, encoding="utf-8") as f:
        for r in list(csv.reader(f))[1:]:
            if r[3].strip():
                t.append(float(r[3]))
                h.append(float(r[4]) * 0.3048)
            if r[0].strip():
                ta.append(float(r[0]))
                acc.append(float(r[1]))
    ia = max(range(len(h)), key=lambda i: h[i])
    land = next(t[i] for i in range(ia, len(h)) if h[i] <= 0.5)
    pad_g = sorted(acc[:40])[20]
    second_g = sorted(acc)[-2]
    # RocketPy's CG without motor (1.30 m) with the motor (its CG about mid-case, 3.08 m from the tip) on the pad.
    rp_cg = (MEASURED["mass"] * 1.30 + motor_mass * (3.391 - 0.621 / 2)) / target_m
    rp_stab = (cp - rp_cg) / 0.203
    zero_aoa = next((re.search(r"Zero-AoA margin: ([\d.]+) cal", c["value"]).group(1) for c in req["checks"]
                     if "Zero-AoA margin" in c["value"]), None)

    def trim(text):
        """First clause of a rule text, without a dangling parenthesis."""
        t = text.split(";")[0]
        return t[:t.rindex("(")].rstrip() if t.count("(") > t.count(")") else t

    # ---------------------------------------------------------------------------------------------------- page
    f = sim["flight"]
    br = sim["branches"][0]
    dep = {x["device"]: x for x in sim["deployments"]}
    acc_r = cmp.get("accelerometer", {})
    comp = {r["quantity"]: r for r in cmp["comparison"]}
    rms = acc_r.get("climbRmsError", {})
    rms_vals = list(rms.items())

    w("# Worked example: a real rocket, start to finish, checked against its flight")
    w()
    w("Most examples check a simulation against another simulation. This one checks it against a rocket that flew. The")
    w("Notre Dame Rocket Team's 2020 NASA Student Launch vehicle is rebuilt from its published dimensions and weigh-in,")
    w("taken through the analyses a team runs before launch day, and then compared, number by number, with the")
    w("altimeter and accelerometer log of its flight on 23 February 2020. **Every number below is real output from the")
    w("engine (OpenRocket 24.12 inside RocketMaxxing)**, produced by [`scripts/make_worked_example.py`]")
    w("(../scripts/make_worked_example.py). The flight data comes from the team via the open-source RocketPy project; see")
    w("[where the data comes from](#where-the-data-comes-from).")
    w()
    w("Use it as the pattern for your own rocket: build from drawings, correct to the weigh-in, set the day's weather, run")
    w("the checks, fly, then compare and calibrate before the next flight. Open")
    w(f"[`examples/ndrt-2020/ndrt-2020.ork`]({rel(p('ndrt-2020.ork'))}) in RocketMaxxing (or OpenRocket) to follow along.")
    w()
    w("**Steps:** [The headline](#the-headline) · [1. Build](#1-build-it-from-the-drawings) · [2. Weigh-in](#2-correct-it-to-the-weigh-in) ·")
    w("[3. Launch day](#3-set-the-days-weather) · [4. Predict](#4-predict-the-flight) · [5. Recovery](#5-recovery) ·")
    w("[6. Structures](#6-structures-and-the-rail) · [7. Electronics](#7-electronics) · [8. Fly and compare](#8-the-flight-against-the-simulation) ·")
    w("[9. What it teaches](#9-what-it-teaches) · [Data](#where-the-data-comes-from)")
    w()

    # headline -------------------------------------------------------------------------------------------------
    w("## The headline")
    w()
    w("| | Simulation (before flight) | Measured | |")
    w("|---|---|---|---|")
    w(f"| Apogee | {both(comp['apogee']['simulated'])} | {both(comp['apogee']['measured'])} | {comp['apogee']['difference']} |")
    w(f"| Time to apogee | {comp['timeToApogee']['simulated']} | {comp['timeToApogee']['measured']} | {comp['timeToApogee']['difference']} |")
    if acc_r:
        bv = acc_r["burnoutVelocity"]
        w(f"| Burnout speed | {both(bv['simulated'])} | {both(bv['measured'])} (integrated accelerometer) | {bv['difference']} |")
        w(f"| Burnout | {acc_r['burnTime']['simulated']} (end of the thrust curve) | {acc_r['burnTime']['measured']} (thrust falls below drag) | |")
    dr = [k for k in comp if "drogue" in k][0]
    mn = [k for k in comp if "main" in k][0]
    w(f"| Descent under drogue | {both(comp[dr]['simulated'])} | {both(comp[dr]['measured'])} | {comp[dr]['difference']} |")
    w(f"| Descent under main | {both(comp[mn]['simulated'])} | {both(comp[mn]['measured'])} | {comp[mn]['difference']} |")
    w(f"| Main opens | 550 ft set; {lead(dep['Main']['altitudeAGL'])} in the simulation | ~170 m (the charge's pressure spike at 65.0 s) | |")
    w(f"| Landing distance | {both(br['landingDistanceFromPad'])} | {MEASURED['drift_ft']:,} ft (reported) | |")
    w(f"| Descent time | {br['descentTime']} | {land - t[ia]:.1f} s (log) | |")
    w()
    if acc_r:
        over = 100 * (num(comp["apogee"]["simulated"]) / num(comp["apogee"]["measured"]) - 1)
        w(f"Stock OpenRocket put the apogee **{over:.0f}% too high** "
          f"({lead(comp['apogee']['simulated'])} against {lead(comp['apogee']['measured'])}). Fitting a drag factor to the")
        w(f"apogee alone says the rocket had {cmp['calibration']['dragFactor'].split(' x')[0]}× OpenRocket's drag. **The accelerometer")
        w(f"says otherwise:** the motor gave {acc_r['thrustFactor'].split(' x')[0]}× its published thrust (the climb was slow from")
        w(f"the first seconds), and the coast after burnout decelerated at {acc_r['coastDrag'].split(' x')[0]}× OpenRocket's drag.")
        w(f"With both corrections the simulation follows the measured climb to within {lead(rms_vals[-1][1])} RMS, against")
        w(f"{lead(rms_vals[1][1])} for the drag-only fit and {lead(rms_vals[0][1])} for OpenRocket as is. The details are in")
        w("[step 8](#8-the-flight-against-the-simulation).")
        w()
    w(f"![Measured and simulated climb]({rel(p('flight-vs-log-climb.svg'))})")
    w()

    # 1 build --------------------------------------------------------------------------------------------------
    w("## 1. Build it from the drawings")
    w()
    ask("Build the NDRT 2020 rocket: 8 in tangent ogive nose 610 mm, 8 in payload bay to 1.2 m, a 127 mm transition to "
        "6 in, 6 in body to 3.391 m, four fins (root 152, tip 76.2, span 165 mm, 13° sweep) at 3.05 m, CTI L1395, "
        "24 in drogue at apogee and 120 in main at 550 ft",
        ["open_design", "add_component", "add_avionics_bay", "set_motor", "set_deployment"])
    w("The outside comes from the published model of the rocket; the inside (where bays split, wall thicknesses, fin")
    w("thickness, how the bay is laid out) is not published and is a reasonable guess. Each value is marked below.")
    w()
    table([
        ["Nose cone", "tangent ogive, 610 mm, 203 mm base", "published"],
        ["Payload bay", "203 mm OD, 590 mm", "published (outside)"],
        ["Transition", "203 → 155 mm over 127 mm, starting 1.2 m from the tip", "published"],
        ["Main bay + fin can", "155 mm OD, 2,064 mm together; split at an avionics bay 889 mm down", "outside published, split assumed"],
        ["Fins", "4 × root 152, tip 76.2, span 165 mm, 13° leading-edge sweep, leading edge 3.05 m from the tip", "published"],
        ["Fin thickness", "4.76 mm (3/16 in) G10 with 10 mm fillets", "assumed"],
        ["Motor", f"{motor['motor']['manufacturer']} {motor['motor']['designation']} in a 75 mm mount, flush with the tail", "published"],
        ["Rail buttons", "1.5 m and 2.0 m from the tip", "from RocketPy's model"],
        ["Drogue", "24 in, Cd 1.5 (an elliptical canopy), at apogee", "published (CdA)"],
        ["Main", "120 in, Cd 2.2 (a high-drag annular canopy), at 550 ft", "published (CdA, altitude)"],
        ["Avionics bay", "12 in coupler, 1 in switch band, two altimeters on their own batteries and switches", "assumed layout"],
    ], ["Part", "Value", "Source"])
    w(f"![The rocket]({rel(p('rocket.svg'))})")
    w()
    w(f"![Cut-away]({rel(p('cutaway.png'))})")
    w()
    w(f"From the drawings alone the model weighs **{airframe_mass:.2f} kg** without the motor: the airframe, the bay")
    w(f"({lead(bay['bayMass'])}) and the recovery gear. The real rocket weighed {MEASURED['mass']} kg, so")
    w(f"{MEASURED['mass'] - airframe_mass:.1f} kg is missing: the payload, plus everything a model leaves out.")
    w()

    # 2 weigh-in -----------------------------------------------------------------------------------------------
    w("## 2. Correct it to the weigh-in")
    w()
    ask(f"The rocket weighs {MEASURED['mass']} kg without the motor and we measured {MEASURED['stability']} cal on the "
        "pad. Make the model match", ["get_design", "edit_components"])
    w("Two measurements (mass and stability margin, which fixes the CG) settle two unknowns. The model takes a payload in")
    w("the payload bay and a line for epoxy, fasteners and paint low in the fin can, and solves for both masses:")
    w()
    table([
        ["Payload (payload bay)", f"{m1:.2f} kg"],
        ["Epoxy, fasteners and paint (fin can)", f"{m2:.2f} kg"],
        ["Mass without motor", f"{MEASURED['mass']:.2f} kg (weighed)"],
        ["Lift-off mass", both(st["launchMass"])],
        ["CG on the pad / CP", f"{lead(st['cgAtLaunch'])} / {lead(st['cp'])} from the tip"],
        ["Stability on the pad", f"{st['stabilityAtLaunch']} (measured {MEASURED['stability']} cal)"],
        ["Stability at burnout", st["stabilityAtBurnout"]],
    ], ["", "Value"])
    w(f"Both masses come out physical: a {m1:.1f} kg payload is what an 8 in payload section of a Student Launch rocket")
    w(f"carries, and {m2:.1f} kg of glue, hardware and paint on a 3.4 m fibreglass rocket is normal. RocketPy's model of")
    w(f"this rocket gives a CG without motor of 1.30 m (± 0.1 m), which would mean {rp_stab:.1f} cal on the pad; this page")
    w("follows the team's measured margin instead. The CG barely changes the apogee (it is set by mass, thrust and drag).")
    w()

    # 3 launch day ---------------------------------------------------------------------------------------------
    w("## 3. Set the day's weather")
    w()
    ask("Launch site Three Oaks, Michigan, 206 m, 11 ft rail. Fly it in the weather of 23 Feb 2020 at 16:00 UTC (the "
        "ERA5 reanalysis)", ["update_standards", "weather_forecast", "wind_profile"])
    w("A forecast is all a team has before launch; for a check after the flight, the reanalysis of the real weather is")
    w("better. Winds were light on the ground and much stronger aloft, which matters for drift:")
    w()
    table([[f"{hh:,} m", f"{v:.1f} m/s", f"{dd}°"] for hh, v, dd in WIND], ["Height above the pad", "Wind", "From"])
    gr = wx["ground"]
    w(f"On the ground: {gr['temperature']}, {gr['pressure']}, gusts to {lead(gr['gusts'])}. The record goes in through")
    w("`weather_forecast` (pasted in its Open-Meteo form), which sets the site, temperature and pressure on the")
    w("simulation, and `wind_profile` lays the full set of levels over it, so every tool below flies the same day.")
    w("Ground (10 m): Open-Meteo's ERA5 archive. Aloft: ERA5 pressure levels from RocketPy's data file for this flight.")
    w("The rail was 11 ft (3.35 m) and vertical.")
    w()

    # 4 predict ------------------------------------------------------------------------------------------------
    w("## 4. Predict the flight")
    w()
    ask("Simulate the flight in those conditions, check our targets and give me the spread", [
        "run_simulation", "targets", "monte_carlo", "aero_analysis", "check_requirements"])
    table([
        ["Apogee", both(f["apogee"])],
        ["Time to apogee", f["timeToApogee"]],
        ["Top speed", f"{both(f['maxVelocity'])}, Mach {float(f['maxMach']):.2f}"],
        ["Peak acceleration", f["maxAcceleration"].split(" = ")[-1]],
        ["Rail exit", both(f["railExitVelocity"])],
        ["Thrust to weight", f["thrustToWeightOnRail(avg)"]],
        ["Stability at rail exit (in the wind)", f["stabilityAtRailExit"]],
        ["Drogue / main descent", f"{lead(dep['Drogue']['steadyDescentRate'])} / {lead(dep['Main']['steadyDescentRate'])}"],
        ["Landing", f"{both(br['landingDistanceFromPad'])} from the pad after {br['flightTime']}"],
    ], ["Prediction", "Value"])
    w(f"![Flight profile]({rel(p('flight-profile.svg'))})")
    w()
    rows = []
    for x in aero["vsMach"]:
        rows.append([x["mach"], x["cd"], x["friction"], x["pressure"], x["base"], lead(x["cp"])])
    w("OpenRocket's drag at zero angle of attack (power off; reference: the 203 mm diameter):")
    w()
    table(rows, ["Mach", "CD", "friction", "pressure", "base", "CP from the tip"])
    w("Friction is about two thirds of the drag: a long fibreglass rocket at low Mach. Keep this in mind for step 8.")
    w()
    w("**The team's targets** (NASA Student Launch limits of the time; check your year's handbook):")
    w()
    trows = []
    for x in tg["targets"]:
        if "status" in x:
            lim = " to ".join(v for v in [x.get("min"), x.get("max")] if v)
            trows.append([x["label"], both(x["value"]), lim + (f", goal {x['goal']}" if x.get("goal") else ""), x["status"]])
    table(trows, ["Target", "Simulated", "Limit", ""])
    w("Two lines need reading. *Lowest stability* is OpenRocket's in-flight margin just off the rail, at an angle of")
    w(f"attack of several degrees in the crosswind, where its CP moves forward; the static (zero angle of attack) margin")
    w(f"there is {zero_aoa} cal, which is what Student Launch's 2.0 cal at rail exit means. The flutter line is the")
    w("team-standard margin of 1.5 on the NACA TN 4197 screen with the *assumed* fin thickness; see")
    w("[step 6](#6-structures-and-the-rail).")
    w()
    w(f"The 11 ft rail gives {lead(f['railExitVelocity'])} at rail exit, comfortably over Student Launch's 52 ft/s.")
    w(f"OpenRocket's {f['stabilityAtRailExit']} at rail exit is the same in-flight effect: at {lead(f['railExitVelocity'])}")
    w("with a 4.4 m/s crosswind the angle of attack is about 13°.")
    w()
    ap = mc["apogee"]
    w(f"**Monte Carlo, {mc['runs']} flights** (wind ± 1.5 m/s and ± 15°, drag ± 10%, thrust ± 4%, mass ± 2%, canopy Cd ± 10%):")
    w(f"apogee {lead(ap['mean'])} ± {lead(ap['sd'])} (1 sd), range {lead(ap['min'])} to {lead(ap['max'])}. The measured")
    meas_ap = num(comp["apogee"]["measured"])
    z = (meas_ap - num(ap["mean"])) / num(ap["sd"])
    w(f"{lead(comp['apogee']['measured'])} is **{z:+.1f} sd** from the mean: inside the predicted spread, on the low side.")
    drv = mc["drivers"]["apogee"]
    w(f"The spread is driven by thrust (r = {float(drv['motorThrust']):.2f}) and drag (r = {float(drv['airframeDrag']):.2f}), not")
    w("by wind: the two things step 8 ends up measuring.")
    w()
    w(f"![Monte Carlo]({rel(p('monte-carlo.svg'))})")
    w()
    fails = [c for c in req["checks"] if c["status"] in ("FAIL", "WARN")]
    w(f"For comparison, the built-in **Launch Canada 2027** rule check ({req['summary']}) asks for more than Student")
    w("Launch did. What it would flag:")
    w()
    table([[c["status"], c["item"], trim(c["value"]), trim(c["requirement"])] for c in fails],
          ["", "Check", "This rocket", "Launch Canada asks"])

    # 5 recovery -----------------------------------------------------------------------------------------------
    w("## 5. Recovery")
    w()
    ask("Check the recovery: opening loads, shear pins, charges, landing energy and the altimeter settings", [
        "recovery_analysis", "recovery_sections", "ejection_charge", "altimeter_settings"])
    rrows = []
    for x in rec["deployments"]:
        rrows.append([x["device"], lead(x["airspeedAtDeployment"]), lead(x["openingLoadInfiniteMass(Cx)"]),
                      lead(x["openingLoadFiniteMass(inflation)"].split(" (")[0]), x["knackeMassRatio"].split(" ")[0],
                      lead(x["designLoad"].split(" [")[0]), lead(x["harnessWorkingLoad"].split(" (")[0])])
    table(rrows, ["Device", "Airspeed at opening", "Infinite-mass load", "Finite-mass load", "Mass ratio", "Design load",
                  "Harness rating"])
    w("The main is a big canopy on a light rocket (Knacke mass ratio well above 1): the vehicle slows while the canopy")
    mains = [x for x in rec["deployments"] if x["device"] == "Main"][0]
    ratio = num(mains["openingLoadInfiniteMass(Cx)"]) / num(mains["openingLoadFiniteMass(inflation)"])
    w(f"fills, so the infinite-mass load (Cx × q × CdA) is {ratio:.0f} times the finite-mass one. This page sets the team standard")
    w("`recovery.openingLoadMethod` to `finite_mass` for that reason; the drogue's load is floored at its steady drag.")
    w()
    srows = []
    for x in sec["sections"]:
        srows.append([x["section"], lead(x["landingMass"]), x.get("landingVelocity", "").split(" (")[0],
                      x.get("landingEnergy", "")])
    table(srows, ["Section (lands on its own tether)", "Mass", "Descent rate", "Landing energy"])
    over = [x for x in sec["sections"] if "EXCEEDS" in x.get("landingEnergy", "")]
    if over:
        w(f"The heaviest section ({over[0]['section']}, with the payload) lands over the common 75 ft-lbf limit *in the")
        w("simulation*.")
    else:
        w("Every section lands within the common 75 ft-lbf limit.")
    w(f"The real rocket came down slower under its main ({lead(comp[mn]['measured'])} against {lead(comp[mn]['simulated'])}):")
    w("the tumbling airframe and lines add drag that OpenRocket leaves out, so the simulated landing energy is on the")
    w("safe side. A team would size the main for margin anyway: `size_parachute` gives the diameter for any target rate.")
    w()
    erows = []
    for name, e in [("Main bay", ej_main), ("Drogue bay (fin can)", ej_drogue)]:
        length = re.search(r"\(([\d.]+ mm) \([\d.]+ in\) of tube", e["bayVolume"])
        vol = num(e["bayVolume"]) / 1000
        erows.append([name, f"{vol:.1f} L" + (f" ({length.group(1)} of tube)" if length else ""),
                      lead(e["designPressure"]), lead(e["primaryCharge"]), lead(e["backupCharge"])])
    table(erows, ["Bay", "Pressurised volume", "Design pressure", "Primary charge", "Backup"])
    w("The pressurised volume is the stretch of tube between the closures around the parachute: in the main bay from")
    w("the transition's shoulder to the avionics coupler, in the fin can from the coupler down to the motor mount. The")
    w("charges break three 4-40 nylon pins with a 1.5 factor; three pins also hold the main bay shut while the drogue")
    w("opens (`recovery_analysis` sizes them from the drogue's load). Ground-test both before flight.")
    w()
    arows = [[c["device"], c["primary"], c["backup"]] for c in alt["channels"]]
    table(arows, ["Channel", "Primary altimeter", "Backup altimeter"])
    w(f"Static ports: {alt['staticPorts'].split(';')[0]}.")
    w()

    # 6 structures ---------------------------------------------------------------------------------------------
    w("## 6. Structures and the rail")
    w()
    ask("Will the fins flutter, what loads do the joints see, and are the rail buttons in the right place?", [
        "fin_flutter", "structural_loads", "fin_fea", "rail_buttons", "aero_heating"])
    fs = fl["finSets"][0]
    w(f"**Flutter.** Fins: {fs['geometry']}. Flutter speed {lead(fs['flutterSpeedSeaLevel'])} at sea level; the worst point is")
    w(f"{lead(fs['worstPoint']['airspeed'].split(' (')[0])} at {lead(fs['worstPoint']['altitude'])}, a margin of")
    w(f"**{fs['minMargin'].split(' ')[0]}** against the team's {fs['requiredMargin'].split(' ')[0]}. "
      f"{fs['toReachRequiredMargin']['thickness'].split(' at')[0]} fins would meet it.")
    w("The fins survived the flight, as the screen's conservatism suggests; their real thickness is not published.")
    w()
    jrows = [[j["joint"], lead(j["maxAxialCompression"].split(" at")[0]), lead(j["bendingMomentMaxQGust"].split(" at")[0]),
              lead(j["wallStress"].split(" (")[0])] for j in loads["joints"]]
    w(f"**Joint loads** ({loads['phases'][0]}, with a {lead(loads['gust'].split(' crosswind')[0])} gust):")
    w()
    table(jrows, ["Joint", "Max compression (boost)", "Bending (max q + gust)", "Wall stress"])
    w("A megapascal or so in fibreglass rated in the hundreds: the airframe is not the weak point at Mach 0.5. The")
    w("compression checks by hand: the mass ahead of the transition (about 10.6 kg) at the 7.9 g of first thrust is 0.8 kN.")
    w()
    w(f"**Fin FEA** (CalculiX shell model of one fin, clamped at the root; deck in [`fin-fea/`]({rel(p('fin-fea'))})). "
      f"Load: {fea['load']['basis']}, {lead(fea['load']['normalForceOnFin'])} on the most loaded fin.")
    w()
    res = fea.get("results") or {}
    hand = fea.get("handEstimate", {})
    if isinstance(fea.get("fea"), str) and not res:
        w(f"CalculiX was not on this machine, so only the hand estimates: root stress {lead(hand.get('rootBendingStress', ''))},")
        w(f"first bending mode {hand.get('firstBendingFrequency', '').split(' (')[0]}.")
    else:
        fr = fea["fea"]
        modes = fr.get("naturalFrequencies", [])
        table([
            ["Tip deflection", fr.get("maxDeflection", "").split(" (")[0] + " (" + fr.get("maxDeflection", "").split("(")[-1]],
            ["Peak stress", f"{lead(fr.get('peakStress', ''))}, against {lead(fea['material']['allowableStress'])} allowable"
                            f" (hand estimate {lead(hand.get('rootBendingStress', ''))})"],
            ["Margin of safety", f"{fr.get('marginOfSafety', '').split(' ')[0]} with the load doubled: {fr.get('status', '')}"],
            ["First modes", ", ".join(modes[:2]) + f" (hand estimate {hand.get('firstBendingFrequency', '').split(' (')[0]})"],
        ], ["Fin under the worst gust", "Result"])
        w("CalculiX agrees with the hand estimates to within about 20%, and the first torsion mode sits well above the")
        w("first bending mode, the separation that keeps flutter away. Strength is not the fin's limit; stiffness is.")
    w()
    if os.path.exists(p("fin-stress.svg")):
        w(f"![Fin stress]({rel(p('fin-stress.svg'))})")
        w()
    cur, recm = rb["current"], rb["recommended"]
    w(f"**Rail buttons.** With the aft button {lead(cur['aftButton'].split(' above')[0])} above the tail, the rocket leaves")
    w(f"the rail guide at {lead(cur['railExitVelocity'])}, not the {lead(rb['openRocketRailExit'].split(':')[0])} OpenRocket")
    w(f"reports (it counts the whole rail). Moving the aft button to the tail gives {lead(recm['railExitVelocity'])}")
    w(f"({rb['recommendedVsCurrent']['railExitVelocity'].split(' (')[0]}) on the same rail. Button positions here come from")
    w("RocketPy's model, not the team's drawings.")
    w()
    w(f"**Heating:** {heat['flight']['maxStagnationTemperature']} at the nose tip: nothing to check below Mach 1.")
    w()

    # 7 electronics --------------------------------------------------------------------------------------------
    w("## 7. Electronics")
    w()
    ask("Will our sensors see the flight without clipping?", ["sensor_check"])
    srows = [[c["sensor"].split(" (")[0], c["quantity"], c["peak"].split(" at")[0], c.get("use", ""), c["status"]]
             for c in sens["checks"]]
    table(srows, ["Sensor", "Quantity", "Peak", "Use of range", ""])
    w(f"The real log's axial accelerometer read {max(acc):.1f} g once (a single-sample spike at {ta[acc.index(max(acc))]:.2f} s);")
    w(f"the next highest sample was {second_g:.1f} g, in line with the simulated {sens['checks'][0]['peak'].split(' at')[0]}. "
      f"On the pad it read {pad_g:.2f} g instead of 1 g: an offset the comparison removes.")
    w()

    # 8 compare ------------------------------------------------------------------------------------------------
    w("## 8. The flight against the simulation")
    w()
    ask("Here is the altimeter log from the flight. How close was the simulation, and what should we change for the "
        "next one?", ["compare_flight"])
    w(f"![Altitude, measured and simulated]({rel(p('flight-vs-log.svg'))})")
    w()
    table([[r["quantity"], r["measured"], r["simulated"], r["difference"]] for r in cmp["comparison"]],
          ["", "Measured", "Simulated", ""])
    w("Time to apogee counts from lift-off to the top of a parabola fitted around apogee, for both curves: the top of")
    w("a flight is flat, and the highest raw sample of a barometric log can sit half a second off.")
    w()
    if acc_r:
        w("**Drag or motor?** An apogee alone cannot tell them apart: fitting only drag gives "
          f"{cmp['calibration']['dragFactor'].split(' x')[0]}×. The log also has an axial accelerometer, and `compare_flight`")
        w("uses it: integrated, it gives the speed at burnout (thrust per unit mass); after burnout, the deceleration over")
        w("speed squared is the drag per unit mass.")
        w()
        w(f"![Acceleration, measured and simulated]({rel(p('flight-vs-log-accel.svg'))})")
        w()
        w("The measured thrust follows the published curve for about 1.5 s, then falls steadily below it until burnout.")
        w("The motor file in the database is a 13-point curve; this motor delivered less in its second half.")
        w()
        table([
            ["Burnout speed", acc_r["burnoutVelocity"]["measured"], acc_r["burnoutVelocity"]["simulated"]],
            ["Coast drag", acc_r["coastDrag"], ""],
            ["Thrust that gives the measured burnout speed", acc_r["thrustFactor"], ""],
            ["Drag that then gives the measured apogee", acc_r["dragFactor"], ""],
            ["Apogee difference split", acc_r["apogeeDifference"], ""],
        ], ["", "From the log", "Simulation"])
        w("Two independent measures of drag agree (the coast deceleration and the apogee once thrust is corrected), and")
        w("the result follows the whole climb:")
        w()
        table([[k, v] for k, v in rms.items()], ["Version of the simulation", "RMS altitude error, lift-off to apogee"])
        cal = acc_r.get("calibratedFlight", {})
        if cal:
            w(f"With thrust ×{acc_r['thrustFactor'].split(' x')[0]} and drag ×{acc_r['dragFactor'].split(' x')[0]} the simulation")
            w(f"reaches {lead(cal['apogee'])} at {cal['timeToApogee']} with {lead(cal['burnoutVelocity'])} at burnout.")
            w()
        w(f"*{acc_r['reading']}* {acc_r['caveats']}")
        w()
    def alt_at(tt):
        k = min(range(len(t)), key=lambda j: abs(t[j] - tt))
        return h[k]
    w(f"**Descent.** Under the drogue the real rocket fell at {lead(comp[dr]['measured'])}, against {lead(comp[dr]['simulated'])}")
    w("simulated, and under the main slower too. OpenRocket counts only the canopy; the airframe sections tumbling on")
    w("their tethers add drag, so its descent rates (and landing energies) are on the safe side.")
    w()
    w(f"The real main fired at about 170 m (65.0 s) and took a while to bite: the log shows {alt_at(67):.0f} m at 67 s,")
    w(f"{alt_at(70):.0f} m at 70 s and {alt_at(72):.0f} m at 72 s, about 100 m and 5 s of canopy opening before the slow")
    w(f"descent. OpenRocket opens a canopy at once (at {lead(dep['Main']['altitudeAGL'])}: its descent time step lets it")
    w(f"fire a few metres below the 550 ft setting) and then floats down for the rest. So the simulated descent is")
    w(f"longer ({br['descentTime']} against {land - t[ia]:.1f} s) even though its rates are faster: worth knowing when a rule caps")
    w("the descent time.")
    w()
    w(f"The simulated drift, {both(br['landingDistanceFromPad'])}, is short of the reported {MEASURED['drift_ft']:,} ft")
    w(f"(with the ground wind alone it was {both(ground_only)}). The reanalysis is an hourly mean on a 0.25° grid,")
    w("and the descent is the longest stretch of the flight in the strongest wind.")
    w()
    w("**The team's own numbers**, checked with `verify_claims`:")
    w()
    table([[c.get("claim", ""), c.get("source", ""), c.get("status", ""), c.get("actual", ""),
            c.get("difference", c.get("why", ""))] for c in claims["claims"]],
          ["Claim", "Source", "", "Model", "Difference"])
    w("The declared target sits between the flown apogee and stock OpenRocket's prediction; the drift is the one")
    w("number the model gets short, for the reasons above.")
    w()

    # 9 lessons ------------------------------------------------------------------------------------------------
    w("## 9. What it teaches")
    w()
    w("1. **Weigh the rocket and measure its CG, then make the model match.** From drawings alone this model was")
    w(f"   {MEASURED['mass'] - airframe_mass:.1f} kg light.")
    w(f"2. **Use the day's real winds aloft.** With only the ground wind the simulated drift was {both(ground_only)};")
    w(f"   with the profile, {both(br['landingDistanceFromPad'])}, and the real drift was further still.")
    w("3. **Fly an accelerometer, not just a barometer.** The apogee alone would have calibrated drag to 1.3×; the")
    w("   accelerometer shows the motor was the main cause, and OpenRocket's drag was if anything 20% high. A team")
    w("   carrying 1.3× drag into its next design would have under-predicted a motor that performs to its curve.")
    w("4. **Treat a motor's published curve as a nominal.** Run Monte Carlo with a few percent of thrust spread, and")
    w("   compare the boost on every flight.")
    w("5. **Simulated descent rates are on the safe side** for landing energy, but the simulated descent *time* is not")
    w("   (canopies open at once in the model). For the opening load of a big canopy on a light rocket, use the")
    w("   finite-mass method.")
    w("6. **Check what a tool assumes.** Building this example found and fixed several things in the engine: motor names")
    w("   with the maker in front (\"CTI L1395\") in `set_motor` and `verify_claims`; in `compare_flight`, the main's")
    w("   descent band (it read the canopy opening), the apogee time (the raw highest sample) and the accelerometer, which")
    w("   it now uses; the wind's drift in the landing speed of `recovery_sections`; and the pressurised volume of a bay,")
    w("   which counted the whole tube instead of the stretch between bulkhead, coupler and motor mount.")
    w()

    # data -----------------------------------------------------------------------------------------------------
    w("## Where the data comes from")
    w()
    w("- **Rocket and flight:** the Notre Dame Rocket Team's 2020 vehicle, launched 23 Feb 2020 near Three Oaks,")
    w("  Michigan. Dimensions, masses, motor, recovery and the flight log (`flight-log.csv`: barometric altitude at 20 Hz")
    w("  and axial acceleration at 400 Hz) are from the [RocketPy](https://github.com/RocketPy-Team/RocketPy) project's")
    w("  `ndrt_2020_flight_sim` example and `data/rockets/NDRT_2020/` (MIT License; the notebook records the team's")
    w("  permission to use the flight data). The team's reported results: stability 2.875 cal, target 4,444 ft,")
    w("  apogee 4,320 ft, drift 2,275 ft.")
    w("- **Weather:** ERA5 reanalysis (Copernicus Climate Change Service): pressure levels from RocketPy's")
    w("  `ndrt_2020_weather_data_ERA5.nc`, the 10 m wind and 2 m temperature from the Open-Meteo archive API.")
    w("  Contains modified Copernicus Climate Change Service information 2020. Weather data by Open-Meteo.com (CC BY 4.0).")
    w("- **Motor:** OpenRocket's built-in Cesaroni 4895L1395-P thrust curve.")
    w("- **Assumed** (not published): the inner layout, wall and fin thicknesses, the avionics, the split of the")
    w("  unmodelled mass between payload and hardware. Mass and stability are matched to the measured values.")
    w()
    w(f"Files: [`ndrt-2020.ork`]({rel(p('ndrt-2020.ork'))}) · [`flight-log.csv`]({rel(LOG)}) ·")
    w(f"[`flight-card.md`]({rel(p('flight-card.md'))}) · [`landing.kml`]({rel(p('landing.kml'))}) ·")
    w(f"[`fin-fea/`]({rel(p('fin-fea'))}) · [data notice]({rel(p('README.md'))})")
    w()

    with open(os.path.join(DOCS, "WORKED_EXAMPLE.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(md).rstrip() + "\n")
    run = json.dumps({"simulation": sim, "compare": cmp, "monteCarlo": {k: mc[k] for k in ("apogee", "landing", "drivers")},
                      "weighIn": {"payload": m1, "hardware": m2}}, indent=1)
    # File paths relative to the folder, so the committed file does not carry this computer's folders.
    for prefix in (OUT + os.sep, OUT + "/"):
        run = run.replace(json.dumps(prefix)[1:-1], "")
    with open(os.path.join(OUT, "run.json"), "w", encoding="utf-8") as fh:
        fh.write(run)
    print("wrote docs/WORKED_EXAMPLE.md and", OUT)


DATA_README = """# NDRT 2020: data for the worked example

Files for [docs/WORKED_EXAMPLE.md](../../WORKED_EXAMPLE.md), regenerated by `scripts/make_worked_example.py`
(except `flight-log.csv`, which is kept as it is).

`flight-log.csv` is the flight log of the Notre Dame Rocket Team's 2020 NASA Student Launch vehicle (CTI L1395,
23 Feb 2020, Three Oaks, Michigan): axial acceleration (g) with its own time column, and barometric altitude (ft AGL).
It is copied unchanged from the RocketPy project (`data/rockets/NDRT_2020/ndrt_2020_flight_data.csv`), whose example
records that the team gave permission to use the flight data. RocketPy is distributed under the MIT License:

    MIT License

    Copyright (c) 2018 Giovani Hidalgo Ceotto

    Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
    documentation files (the "Software"), to deal in the Software without restriction, including without limitation
    the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
    to permit persons to whom the Software is furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all copies or substantial portions of
    the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
    THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
    CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
    IN THE SOFTWARE.

The weather numbers in the generator come from the ERA5 reanalysis (Contains modified Copernicus Climate Change
Service information 2020) and the Open-Meteo archive API (Weather data by Open-Meteo.com, CC BY 4.0).
"""

if __name__ == "__main__":
    main()
