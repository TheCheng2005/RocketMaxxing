#!/usr/bin/env python3
"""Scenario benchmark for openrocket-mcp.

Drives a fresh server over stdio (exactly like Claude Code does) through realistic student-team requests, checks
each answer against engineering expectations, and times every call.

    ./gradlew installDist && python3 scripts/benchmark.py [--verbose]

Exit code 0 when every check passes.
"""
import json
import re
import os
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BIN = os.path.join(ROOT, "build", "install", "openrocket-mcp", "bin",
                   "openrocket-mcp.bat" if os.name == "nt" else "openrocket-mcp")
VERBOSE = "--verbose" in sys.argv


class Server:
    def __init__(self):
        env = dict(os.environ, OPENROCKET_MCP_STANDARDS="")
        self.p = subprocess.Popen([BIN], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                  text=True, cwd=tempfile.mkdtemp(), env=env)
        self.id = 0
        self.calls = []
        self.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                "clientInfo": {"name": "benchmark", "version": "1"}})

    def rpc(self, method, params):
        self.id += 1
        self.p.stdin.write(json.dumps({"jsonrpc": "2.0", "id": self.id, "method": method, "params": params}) + "\n")
        self.p.stdin.flush()
        return json.loads(self.p.stdout.readline())

    def call(self, tool, args=None, expect_error=False):
        t0 = time.time()
        r = self.rpc("tools/call", {"name": tool, "arguments": args or {}})["result"]
        dt = time.time() - t0
        text = r["content"][0]["text"]
        self.calls.append((tool, dt, r["isError"], len(text)))
        if VERBOSE:
            print(f"  -> {tool} ({dt:.2f}s){' ERROR' if r['isError'] else ''}\n{text[:1500]}\n")
        if r["isError"] and not expect_error:
            raise AssertionError(f"{tool} failed: {text}")
        if expect_error:
            return text
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            return text

    def close(self):
        self.p.stdin.close()
        self.p.wait(timeout=10)


def num(s):
    """Leading number of a formatted quantity such as '3047 m (9997 ft)'."""
    return float(str(s).split()[0])


def ft(s):
    """Feet value from '3047 m (9997 ft)'."""
    s = str(s)
    return float(s[s.index("(") + 1:].split()[0]) if "(" in s else num(s) / 0.3048


RESULTS = []


def check(scenario, name, ok, detail=""):
    RESULTS.append((scenario, name, bool(ok), detail))
    mark = "PASS" if ok else "FAIL"
    print(f"  [{mark}] {name}" + (f" - {detail}" if detail and (not ok or VERBOSE) else ""))


def scenario_from_scratch(s):
    """'Design a 4 in dual-deploy rocket for 10,000 ft that passes Launch Canada rules.'"""
    sc = "from-scratch 10k ft"
    first = s.call("open_design", {"newRocketName": "Bench 10k"})
    d = first["designId"]
    check(sc, "the first result offers what to ask next (design_status, capabilities)",
          any("design_status" in x for x in first.get("youCouldAlsoAsk", [])), str(first.get("youCouldAlsoAsk")))
    stage = s.call("get_design", {"designId": d})["components"][0].split("[")[1][:8]
    add = lambda parent, typ, name, props: s.call("add_component", {"designId": d, "parent": parent, "type": typ,
                                                                    "name": name, "properties": props})
    add(stage, "NoseCone", "Nose cone", {"shapeType": "OGIVE", "length": "18 in", "aftDiameter": "4.02 in",
                                          "thickness": "2.5 mm", "material": "Fiberglass",
                                          "aftShoulderLength": "4 in", "aftShoulderDiameter": "3.9 in"})
    add(stage, "BodyTube", "Upper airframe", {"length": "24 in", "outerDiameter": "4.02 in", "thickness": "2 mm",
                                              "material": "Fiberglass"})
    add(stage, "BodyTube", "Lower airframe", {"length": "40 in", "outerDiameter": "4.02 in", "thickness": "2 mm",
                                              "material": "Fiberglass"})
    add("Lower airframe", "InnerTube", "MMT", {"length": "30 in", "outerDiameter": "78 mm", "thickness": "1.5 mm",
                                              "axialMethod": "BOTTOM", "axialOffset": 0})
    # Student-style property names: sweepLength and span must work; a bad property must not leave a half-built part.
    err = s.call("add_component", {"designId": d, "parent": "Lower airframe", "type": "TrapezoidFinSet",
                                   "name": "Broken", "properties": {"finCount": 4, "bogus": 1}}, expect_error=True)
    tree = json.dumps(s.call("get_design", {"designId": d})["components"])
    check(sc, "failed add_component leaves nothing behind", "Broken" not in tree and "Nothing was added" in err)
    fins = add("Lower airframe", "TrapezoidFinSet", "Fins", {"finCount": 4, "rootChord": "9 in", "tipChord": "3 in",
                                                             "span": "5 in", "sweepLength": "5 in",
                                                             "thickness": "3.2 mm", "material": "Fiberglass",
                                                             "axialMethod": "BOTTOM", "axialOffset": 0})
    applied = " ".join(fins["applied"])
    check(sc, "sweep length accepts inches (not an angle)", "sweep = 127 mm" in applied, applied)
    check(sc, "material set by name", "material = Fiberglass" in applied, applied)
    add("Upper airframe", "Parachute", "Main", {"diameter": "60 in", "cd": 0.8})
    add("Lower airframe", "Parachute", "Drogue", {"diameter": "18 in", "cd": 0.8})
    add("Upper airframe", "MassComponent", "Avionics", {"mass": "1.2 kg", "length": "8 in"})

    t0 = time.time()
    rank = s.call("rank_motors", {"designId": d, "mount": "MMT", "objective": "target_apogee",
                                  "targetApogee": "10000 ft", "minClass": "K"})
    check(sc, "rank_motors works on a design without a flight configuration", "createdConfiguration" in rank)
    rows = rank["ranking"]
    names = [r["motor"] for r in rows]
    check(sc, "no duplicate motors in ranking", len(names) == len(set(names)), str(names))
    check(sc, "all ranked motors fit the 30 in mount (+50 mm)", all(num(r["length"]) <= 812 for r in rows))
    check(sc, "chutes left on the motor ejection event are warned about", "warning" in rank and "ejection" in rank["warning"],
          rank.get("warning", ""))

    s.call("set_deployment", {"designId": d, "component": "Drogue", "event": "apogee", "configuration": "all"})
    s.call("set_deployment", {"designId": d, "component": "Main", "event": "altitude", "altitude": "1000 ft",
                              "configuration": "all"})
    rank = s.call("rank_motors", {"designId": d, "mount": "MMT", "objective": "target_apogee",
                                  "targetApogee": "10000 ft", "minClass": "K"})
    top = rank["ranking"][0]
    check(sc, "best motor is within 5% of 10,000 ft", abs(ft(top["apogee"].split(" (+")[0].split(" (-")[0]) - 10000) < 500,
          f'{top["motor"]}: {top["apogee"]}')
    check(sc, "rule-compliant motors ranked first", top["meetsRules"] == "yes" or "0 meet the rules" in rank["candidates"],
          top["meetsRules"])
    check(sc, "ranking reports stability with each motor", "minAscentStability" in top)
    check(sc, "motor ranking time < 30 s", time.time() - t0 < 30, f"{time.time() - t0:.1f} s")

    s.call("set_motor", {"designId": d, "mount": "MMT", "motor": top["motor"]})
    fins = [{"component": "Fins", "property": "height", "min": "3.5 in", "max": "8 in"},
            {"component": "Fins", "property": "rootChord", "min": "7 in", "max": "12 in"}]
    tight = s.call("optimize", {"designId": d, "objective": "max_apogee", "minStability": 1.6, "maxStability": 4.5,
                                "variables": fins, "maxEvaluations": 48})
    check(sc, "an infeasible stability window is explained (which limits bind)",
          tight["feasible"] or ("stability" in tight.get("note", "")), tight.get("note", "feasible"))
    thin = s.call("optimize", {"designId": d, "objective": "max_apogee", "meetRules": True,
                               "variables": fins, "maxEvaluations": 24})
    check(sc, "with meetRules the optimizer will not size 1/8 in fins that flutter at Mach > 1, and says what to do",
          not thin["feasible"] and "thicker or stiffer fins" in thin.get("note", ""), thin.get("note", "feasible"))
    opt = s.call("optimize", {"designId": d, "objective": "max_apogee", "meetRules": True, "minFlutterMargin": 0,
                              "variables": fins, "maxEvaluations": 48, "apply": True})
    check(sc, "optimizer meets the rule-derived stability window (incl. 10% body length, design wind)", opt["feasible"],
          opt.get("note", json.dumps(opt["best"])))
    check(sc, "rule-derived stability floor includes the 2027 %-body-length edict",
          "body length" in opt["constraints"].get("fromRules", ""), opt["constraints"].get("fromRules", ""))
    check(sc, "optimizer evaluates the design-wind case", "evaluatedAt" in opt["constraints"])
    # Mach > 1 on 1/8 in fins: the flutter screen should object, and its suggested thickness should fix it.
    fl = s.call("fin_flutter", {"designId": d})["finSets"][0]
    check(sc, "flutter screen flags thin fins at Mach > 1", fl["status"] != "PASS", fl["status"])
    if "toReachRequiredMargin" in fl:
        need_in = num(fl["toReachRequiredMargin"]["thickness"].split("(")[1]) if "(" in fl["toReachRequiredMargin"]["thickness"] \
            else num(fl["toReachRequiredMargin"]["thickness"]) / 25.4
        sheet = next(t for t in [0.125, 0.1875, 0.25, 0.3125, 0.375, 0.5, 0.625, 0.75] if t >= need_in)  # stock G10 sheet
        s.call("edit_components", {"designId": d, "changes": [{"component": "Fins", "properties": {"thickness": f"{sheet} in"}}]})
        fl = s.call("fin_flutter", {"designId": d})["finSets"][0]
        check(sc, f"suggested fin thickness ({sheet} in stock) passes the flutter screen", fl["status"] == "PASS",
              fl["minMargin"])
        refit = s.call("optimize", {"designId": d, "objective": "max_apogee", "meetRules": True, "variables": fins,
                                    "maxEvaluations": 32, "apply": True})
        check(sc, "after the thickness fix, fins meeting stability AND flutter are found",
              refit["feasible"] and num(refit["best"].get("finFlutterMargin", "0")) >= 1.5, refit.get("note", json.dumps(refit["best"])))
    req = s.call("check_requirements", {"designId": d})
    fails = [c for c in req["checks"] if c["status"] == "FAIL"]
    check(sc, "final design has no rule failures", not fails, json.dumps(fails)[:600])
    items = " ".join(c["item"] for c in req["checks"])
    check(sc, "2027 edicts checked: L:D, damping ratio, % body length, pop tests",
          all(k in items for k in ["Length-to-diameter", "Damping ratio", "% of body length", "dress rehearsal"]), items[:300])
    check(sc, "manual checklist (electronics, radio, structures) included", len(req.get("manualChecks", [])) >= 10)
    return d


def scenario_recovery(s):
    """'Size our main for 20 ft/s and check shock loads, pins and charges.' (OpenRocket two-stage example)"""
    sc = "recovery chain"
    d = s.call("open_design", {"example": "Two stage high power"})["designId"]
    size = s.call("size_parachute", {"designId": d, "device": "Sustainer Main", "targetDescentRate": "20 ft/s"})
    m = size["presetMatches"]
    check(sc, "real parachutes suggested for 20 ft/s", len(m) > 0 and abs(ft(m[0]["descentRate"]) - 20) < 3,
          m[0]["descentRate"] if m else "none")
    rec = s.call("recovery_analysis", {"designId": d, "pinType": "4-40 nylon"})
    deps = rec["deployments"]
    drogue = [x for x in deps if x["role"] == "drogue"]
    mains = [x for x in deps if x["role"].startswith("main")]
    check(sc, "pins sized only for bays that must stay closed (drogue event)",
          all("shearPinsForLaterBays" in x for x in drogue) and not any("shearPinsForLaterBays" in x for x in mains))
    check(sc, "harness working load given for every deployment", all("harnessWorkingLoad" in x for x in deps))
    sweep = s.call("deployment_delay_sweep", {"designId": d, "device": "Sustainer Drogue", "delays": [0, 1, 2, 3, 4],
                                              "pinType": "4-40 nylon", "pinCount": 4})
    check(sc, "late-deployment study returns a latest safe delay", "latestDelayWithinCapacity" in sweep)
    bp = s.call("ejection_charge", {"bayDiameter": "4 in", "bayLength": "10 in", "pressure": "15 psi", "safetyFactor": 1})
    check(sc, "BP charge matches 0.006 D^2 L rule (~0.97 g)", abs(num(bp["primaryCharge"]) - 0.97) < 0.02, bp["primaryCharge"])
    shock = s.call("opening_shock", {"mass": 50, "velocity": 36, "cd": 2.2, "area": 0.636, "airDensity": 0.86, "cx": 1.4})
    check(sc, "opening shock reproduces team doc (~1100 N)", abs(num(shock["infiniteMassLoad"]) - 1092) < 5,
          shock["infiniteMassLoad"])
    chart = s.call("parachute_sizes", {"designId": d, "device": "Sustainer Main", "cds": [0.8, 1.5, 2.2]})
    sim_rate = chart["current"]["simulatedRate"].split(" (")[0]
    main_check = [c for c in s.call("check_requirements", {"designId": d})["checks"]
                  if c["item"].startswith("Sustainer: landing descent rate")]
    check(sc, "size chart: three Cd curves, the size each needs, and the current main where check_requirements has it",
          len(chart["sizeForTarget"]) == 3 and main_check and main_check[0]["value"].startswith(sim_rate),
          json.dumps(chart["current"]) + " vs " + str(main_check))
    sized = s.call("parachute_sizes", {"designId": d, "device": "Sustainer Main", "cd": 1.5, "apply": True,
                                       "targetDescentRate": "20 ft/s", "catalog": False})
    check(sc, "sizing the main at Cd 1.5 re-flies it at the 20 ft/s target",
          18.5 < ft(sized["current"]["simulatedRate"]) <= 20.05, sized["current"]["simulatedRate"])
    s.call("undo", {"designId": d})
    page = ("Iris Ultra 84\" Standard Parachute\nBrand: Fruity Chutes\nToroidal canopy for large rockets.")
    link = s.call("add_from_link", {"designId": d, "pageText": page, "component": "Sustainer Main"})
    check(sc, "pasted product page: 84 in parachute in, Cd and packed size from the catalog, re-flown",
          link["product"]["kind"] == "parachute" and "catalog" in link["fromThePage"]["cd"]
          and "->" in link["applied"]["descentRate"], json.dumps(link)[:400])
    blocked = s.call("add_from_link", {"designId": d, "url": "http://169.254.169.254/latest/meta-data"}, expect_error=True)
    check(sc, "links into the server's own network are refused", "not fetched" in blocked, blocked)


def scenario_two_stage(s):
    """'Is our sustainer stable after separation, and what tilt lockout / inhibit altitude do we program?'"""
    sc = "two-stage"
    d = s.call("open_design", {"example": "Two stage high power"})["designId"]
    g = s.call("get_design", {"designId": d})
    check(sc, "stability reported for sustainer alone", any("after" in st["when"] for st in g["stability"]))
    req = s.call("check_requirements", {"designId": d, "launchAngle": "6 deg"})
    items = [c["item"] for c in req["checks"]]
    tilt = [c for c in req["checks"] if c["item"].startswith("Tilt at upper-stage ignition")]
    check(sc, "tilt at air start checked (< 15 deg)", tilt and num(tilt[0]["value"]) < 15, tilt[0]["value"] if tilt else items)
    check(sc, "air-start altitude inhibit given", any("altitude inhibit" in i for i in items))
    check(sc, "booster and sustainer thrust-to-weight checked", any("Booster thrust-to-weight" in i for i in items)
          and any("Upper-stage thrust-to-weight" in i for i in items))
    # The sustainer alone through Mach, and a RASAero run of it used once the booster has gone.
    sus = s.call("aero_analysis", {"designId": d, "stage": "Sustainer"})
    whole = s.call("aero_analysis", {"designId": d, "machs": [0.3]})
    check(sc, "aero_analysis gives the sustainer alone, with its top speed and margin in flight",
          sus.get("when") == "after Booster separates" and "atMaxMach" in sus.get("inFlight", {})
          and num(sus["vsMach"][1]["marginBurnout"]) != num(whole["vsMach"][0]["marginBurnout"]), json.dumps(sus)[:300])
    lines = ["Mach,Alpha,CD Power-Off,CD Power-On,CP"]
    for r in s.call("aero_analysis", {"designId": d, "stage": "Sustainer", "machs": [0.05, 0.3, 0.6, 0.9, 1.2]})["vsMach"]:
        cp_in = float(r["cp"].split("(")[1].split()[0]) if "(" in r["cp"] else num(r["cp"]) / 25.4
        lines.append(f'{r["mach"]},0,{1.2 * float(r["cd"]):.4f},{1.1 * float(r["cd"]):.4f},{cp_in:.2f}')
    path = os.path.join(tempfile.mkdtemp(), "sustainer-rasaero.csv")
    open(path, "w").write("\n".join(lines) + "\n")
    imp = s.call("import_aero_table", {"designId": d, "path": path, "stage": "Sustainer"})
    eff = imp.get("effect", {})
    check(sc, "a RASAero run of the sustainer is used after separation (lower apogee, its CP checked)",
          imp.get("when") == "after Booster separates" and eff.get("apogeeImportedDrag") != eff.get("apogeeOpenRocketDrag")
          and "minAscentStabilityImportedCp" in eff, json.dumps(imp)[:300])
    req2 = s.call("check_requirements", {"designId": d, "includeWindCase": False})
    check(sc, "rule check has the sustainer's own imported-CP stability",
          any(c["item"].endswith(", Sustainer") and "imported CP" in c["item"] for c in req2["checks"]))
    s.call("import_aero_table", {"designId": d, "mode": "clear"})
    run = s.call("run_simulation", {"designId": d})
    check(sc, "each stage lands on its own", {b["branch"] for b in run["branches"]} == {"Sustainer", "Booster"},
          [b["branch"] for b in run["branches"]])
    check(sc, "booster chute deployment analysed", any(x["branch"] == "Booster" for x in run["deployments"]))
    tmp = tempfile.mkdtemp()
    view = s.call("render_3d", {"designId": d, "path": os.path.join(tmp, "x.png"), "width": 900})
    check(sc, "exploded view lists parts per stage", {p.get("stage") for p in view["parts"]} == {"Sustainer", "Booster"})
    anim = s.call("animate_flight", {"designId": d, "path": os.path.join(tmp, "f.gif"), "duration": 10, "fps": 4,
                                     "width": 480, "mp4": False, "stills": False})
    events = [e["event"] for e in anim["timeline"]]
    check(sc, "animation names each stage's events",
          all(e in events for e in ("Booster separation", "Sustainer ignition", "Booster touchdown")), events)


def scenario_custom_engine(s, tmp):
    """'Here is our liquid engine's static-fire curve; how high does Discovery go?'"""
    sc = "custom liquid engine"
    d = s.call("open_design", {"example": "Dual parachute"})["designId"]
    g = s.call("get_design", {"designId": d})
    mount = g["flightConfigurations"][0]["motors"][0]["mountId"]
    curve = [[0, 0], [0.2, 900], [3.5, 820], [3.8, 0]]
    m = s.call("create_custom_motor", {"designation": "Bench-Liquid", "manufacturer": "UTAT", "type": "liquid",
                                       "diameter": "38 mm", "length": "600 mm", "totalMass": "2.4 kg",
                                       "propellantMass": "1.0 kg", "thrustCurve": curve,
                                       "propellantCgFromTop": "200 mm", "dryCgFromTop": "400 mm",
                                       "saveTo": os.path.join(tmp, "Bench-Liquid.rse")})
    check(sc, ".rse file written for the OpenRocket GUI", os.path.exists(m["file"]))
    s.call("set_motor", {"designId": d, "mount": mount, "motor": "Bench-Liquid", "manufacturer": "UTAT"})
    sim = s.call("run_simulation", {"designId": d})
    check(sc, "simulates with the custom engine", num(sim["flight"]["apogee"]) > 100, sim["flight"]["apogee"])


def scenario_liquid_program(s):
    """'Is our N2O tank OK at 800 psi, and which probation level are we in?' (LC 2027 edicts)"""
    sc = "liquid program (2027 edicts)"
    pv = s.call("pressure_vessel", {"meop": "800 psi", "outerDiameter": "6 in", "wallThickness": "0.125 in",
                                    "ultimateStrength": "290 MPa", "welded": True, "weldKnockdown": 1.3,
                                    "proofPressure": "1200 psi"})
    burst_psi = 2 * 290e6 / 6894.757 * 0.125 / 6
    check(sc, "Barlow burst and 2 x MEOP x knockdown check", pv["burstCheck"] == ("PASS" if burst_psi >= 2 * 800 * 1.3 else "FAIL"),
          f'{pv["burstPressure"]} vs {pv["requiredBurst"]}')
    check(sc, "proof pressure >= 1.5 x MEOP", pv["proofCheck"] == "PASS", pv.get("proofCheck"))
    copv = s.call("pressure_vessel", {"meop": "750 psi", "burstPressure": "2800 psi", "copv": True})
    check(sc, "COPV needs 4 x MEOP (2800 < 3000 psi fails)", copv["burstCheck"] == "FAIL", copv["requiredBurst"])
    pl = s.call("advanced_probation", {"glppVolume": "6 L", "totalImpulse": "9000 Ns", "propellantMass": "7 kg",
                                       "targetAltitude": "10000 ft", "actualAltitude": "7000 ft"})
    check(sc, "6 L GLPP is probation level 1", pl["probationLevel"].startswith("PL1"), pl["probationLevel"])
    check(sc, "AASI = min(1, 7000/10000) x Isp", abs(num(pl["aasi"]) - 0.7 * 9000 / (7 * 9.80665)) < 0.2, pl["aasi"])


def scenario_dispersion_report(s, tmp):
    """'Where will it land in 15 km/h wind, and give me the design-review package.'"""
    sc = "dispersion + report"
    d = s.call("open_design", {"example": "Dual parachute"})["designId"]
    t0 = time.time()
    mc = s.call("monte_carlo", {"designId": d, "runs": 100, "windSpeed": "15 km/h"})
    dt = time.time() - t0
    check(sc, "100-run Monte Carlo < 20 s", dt < 20, f"{dt:.1f} s")
    check(sc, "landing ellipse reported", "ellipse2Sigma" in json.dumps(mc["landing"]))
    out = os.path.join(tmp, "review")
    s.call("generate_report", {"designId": d, "outputDir": out})
    check(sc, "report + two stability plots + CSV written",
          all(os.path.exists(os.path.join(out, f)) for f in
              ["report.md", "stability-ascent.svg", "stability-to-rail-exit.svg", "flight-data.csv"]))


def scenario_structures(s, d):
    """'Will our 1/8 in G10 fins flutter? How much nose weight for +0.5 cal? What drives our dispersion?'"""
    sc = "structures + vehicle dispersion"
    fl = s.call("fin_flutter", {"designId": d})
    fin = fl["finSets"][0]
    margin = num(fin["minMargin"])
    check(sc, "flutter margin reported with the worst point", margin > 0 and "altitude" in fin["worstPoint"], json.dumps(fin)[:300])
    check(sc, "shear modulus from the fin material (G10/fiberglass)", "fiberglass" in fin["shearModulus"], fin["shearModulus"])
    t_mm = float(fin["geometry"].split("thickness ")[1].split(" mm")[0])
    thin = s.call("fin_flutter", {"designId": d, "thickness": f"{t_mm / 2} mm"})["finSets"][0]
    ratio = num(thin["minMargin"]) / margin
    check(sc, "half the thickness -> ~0.35x margin (Vf ~ t^1.5)", 0.3 < ratio < 0.4, f"{ratio:.3f}")
    check(sc, "a failing fin gets a thickness / modulus fix", thin["status"] == "PASS" or "toReachRequiredMargin" in thin,
          thin["status"])
    # 'Should the fins be G10, carbon or aluminum, and how thick?' - flown and checked per material.
    mat = s.call("compare_materials", {"designId": d, "materials": ["Carbon fiber", "Aluminum"], "thicknesses": ["1/8 in", "3/16 in"]})
    pick = lambda name, mm: next((o for o in mat["options"] if o["option"].startswith(f"{name}, {mm} mm")), None)
    al, cf, cf2 = pick("Aluminum", "3.175"), pick("Carbon fiber", "3.175"), pick("Carbon fiber", "4.762")
    check(sc, "materials: stiffer aluminum fins have the larger flutter margin than carbon at the same thickness",
          al and cf and num(al["finFlutterMargin"]) > num(cf["finFlutterMargin"]), json.dumps([o["option"] for o in mat["options"]])[:300])
    check(sc, "materials: thicker stock raises the margin and the mass",
          cf2 and num(cf2["finFlutterMargin"]) > num(cf["finFlutterMargin"]) and num(cf2["launchMass"]) > num(cf["launchMass"]),
          json.dumps(cf2)[:300])
    req = s.call("check_requirements", {"designId": d, "includeWindCase": False})
    items = [c for c in req["checks"] if c["item"].startswith("Fin flutter margin")]
    check(sc, "check_requirements includes the flutter margin", len(items) == 1 and fin["minMargin"].split()[0] in items[0]["value"],
          json.dumps(items)[:300])

    now = json.dumps(s.call("run_simulation", {"designId": d}))
    cur = float(now.split('"minStabilityDuringAscent": "')[1].split()[0])
    target = round(cur + 0.5, 2)
    t0 = time.time()
    bal = s.call("ballast", {"designId": d, "targetStability": target})
    dt = time.time() - t0
    got = num(bal["effect"]["minAscentStability"].split("->")[1])
    check(sc, "ballast solve reaches the simulated target (+-0.05 cal)", abs(got - target) <= 0.05, f"{got} vs {target}")
    check(sc, "ballast reports the apogee cost", "->" in bal["effect"]["apogee"])
    check(sc, "ballast solve < 15 s", dt < 15, f"{dt:.1f} s")

    t0 = time.time()
    mc = s.call("monte_carlo", {"designId": d, "runs": 60, "thrustSd": 0.03, "massSd": 0.03, "dragSd": 0.1,
                                "chuteCdSd": 0.1})
    dt = time.time() - t0
    drv = mc.get("drivers", {}).get("apogee", {})
    check(sc, "vehicle-uncertainty Monte Carlo reports drivers", {"motorThrust", "airframeDrag", "structureMass"} <= set(drv),
          json.dumps(drv))
    check(sc, "drag lowers and thrust raises apogee", float(drv.get("airframeDrag", 0)) < 0 < float(drv.get("motorThrust", 0)),
          json.dumps(drv))
    check(sc, "60-run vehicle Monte Carlo < 20 s", dt < 20, f"{dt:.1f} s")


def scenario_openrocket_depth(s, d, tmp):
    """'Where does our drag come from? What do winds aloft do to our landing zone? Draw it for the review.'"""
    sc = "aero, winds aloft, parts, drawing"
    a = s.call("aero_analysis", {"designId": d, "maxMach": 1.5})
    rows = a["vsMach"]
    check(sc, "CD / CP / margin table vs Mach", len(rows) >= 8 and all("marginLaunch" in r for r in rows))
    cds = {float(r["mach"]): float(r["cd"]) for r in rows}
    check(sc, "transonic drag rise visible (CD at M1.0 > M0.5)", cds[1.0] > cds[0.5], json.dumps(cds))
    top = a["dragBreakdown"][0]
    check(sc, "per-component drag breakdown names the biggest contributor", float(top["share"].rstrip("%")) > 10, json.dumps(top))

    uniform = s.call("run_simulation", {"designId": d, "windSpeed": "20 km/h", "windDirection": "270 deg"})
    s.call("wind_profile", {"designId": d, "mode": "power_law", "groundSpeed": "20 km/h", "direction": "270 deg",
                            "exponent": 0.2, "top": "4000 m"})
    sheared = s.call("run_simulation", {"designId": d})
    far = lambda r: max(num(b["landingDistanceFromPad"]) for b in r["branches"] if "landingDistanceFromPad" in b)
    du, ds = far(uniform), far(sheared)
    check(sc, "winds aloft (shear profile) drift the vehicle farther than a uniform ground wind",
          du is not None and ds > du * 1.2, f"{du} vs {ds}")
    mc = s.call("monte_carlo", {"designId": d, "runs": 30})
    check(sc, "Monte Carlo runs on the wind profile", "landing" in mc and mc["failedRuns"] == 0)
    s.call("wind_profile", {"designId": d, "mode": "average"})

    parts = s.call("search_parts", {"type": "body_tube", "minOuterDiameter": "4.0 in", "maxOuterDiameter": "4.05 in",
                                    "text": "fiberglass"})
    check(sc, "parts database search by diameter and material", parts["matches"] > 0, json.dumps(parts)[:200])
    out = os.path.join(tmp, "draw.svg")
    s.call("draw_rocket", {"designId": d, "path": out})
    svg = open(out).read()
    check(sc, "rocket drawing with fins, CG and CP", svg.count("<polygon") >= 5 and "CG " in svg and "CP " in svg)


def scenario_post_flight(s, d, tmp):
    """'Here is RASAero data for our airframe, and here is the altimeter log from our test flight: calibrate the model.'"""
    sc = "RASAero data + flight-log calibration"
    aero = s.call("aero_analysis", {"designId": d, "machs": [0.05, 0.3, 0.6, 0.9, 1.0, 1.2, 1.5, 2.0]})
    lines = ["Mach,Alpha,CD Power-Off,CD Power-On,CP"]
    for r in aero["vsMach"]:
        cp_in = float(r["cp"].split("(")[1].split()[0]) if "(" in r["cp"] else num(r["cp"]) / 25.4
        lines.append(f'{r["mach"]},0,{1.1 * float(r["cd"]):.4f},{1.0 * float(r["cd"]):.4f},{cp_in:.2f}')
        lines.append(f'{r["mach"]},4,{1.3 * float(r["cd"]):.4f},{1.2 * float(r["cd"]):.4f},{cp_in + 1:.2f}')
    path = os.path.join(tmp, "rasaero.csv")
    open(path, "w").write("\n".join(lines) + "\n")
    imp = s.call("import_aero_table", {"designId": d, "path": path})
    eff = imp["effect"]
    check(sc, "RASAero export parsed (alpha 0 rows, power-on/off, CP in inches)", "with CP" in imp["table"], imp["table"])
    check(sc, "imported drag changes the simulated apogee", eff["apogeeImportedDrag"] != eff["apogeeOpenRocketDrag"], json.dumps(eff))
    req = s.call("check_requirements", {"designId": d, "includeWindCase": False})
    items = [c for c in req["checks"] if c["item"].startswith("Ascent stability with imported CP")]
    check(sc, "stability checked with the RASAero CP (DTEG R10.3.1)", len(items) == 1, json.dumps(items)[:300])
    s.call("import_aero_table", {"designId": d, "mode": "clear"})

    # A 'measured' flight 8% lower than the model, as an altimeter CSV in feet with a pad offset.
    fd = s.call("get_flight_data", {"designId": d, "variables": ["altitude"], "maxPoints": 500})
    header = fd["columns"] if "columns" in fd else None
    rows = fd["rows"]
    log = ["Time (s),Altitude (ft)"]
    for r in rows:
        t, alt = float(r[0]), float(r[1])
        log.append(f"{t + 3:.3f},{812 + 0.92 * alt / 0.3048:.1f}")
    log = ["Time (s),Altitude (ft)"] + [f"{t / 10:.1f},812" for t in range(0, 30)] + log[1:]
    lp = os.path.join(tmp, "altimeter.csv")
    open(lp, "w").write("\n".join(log) + "\n")
    t0 = time.time()
    cmp = s.call("compare_flight", {"designId": d, "path": lp, "plotPath": os.path.join(tmp, "overlay.svg")})
    dt = time.time() - t0
    fit = cmp["calibration"]["dragFactor"]
    diff = cmp["comparison"][0]["difference"]  # e.g. "measured 8.01% lower"
    pct = float(re.search(r"([\d.]+)%", diff).group(1)) if "%" in diff else float("nan")
    check(sc, "flight-log comparison finds the model 8% high on apogee", "lower" in diff and 7.5 <= pct <= 8.5, diff)
    check(sc, "drag calibration factor > 1 for an under-performing flight", fit[0].isdigit() and float(fit.split()[0]) > 1.05, fit)
    check(sc, "overlay plot written", os.path.exists(os.path.join(tmp, "overlay.svg")))
    check(sc, "calibration < 15 s", dt < 15, f"{dt:.1f} s")


def scenario_studies(s, d):
    """'Which nose cone and fin edges should we build? What lands where, and what do the joints carry?'"""
    sc = "shape study, sections, loads"
    t0 = time.time()
    sh = s.call("compare_shapes", {"designId": d})
    dt = time.time() - t0
    rows = sh["options"]
    check(sc, "every nose profile and fin edge flown (>= 11 options, no duplicates)",
          len(rows) >= 11 and len({(r["nose"], r["finEdges"]) for r in rows}) == len(rows), str(len(rows)))
    check(sc, "recommendation respects the stability floor", "bestMeetingStability" in sh, json.dumps(sh.get("bestMeetingStability")))
    haack = [r for r in rows if r["nose"].startswith("Von Karman")]
    blunt = [r for r in rows if r["nose"].startswith("1/2 power")]
    check(sc, "supersonic design: Von Karman has less drag than a 1/2-power nose",
          haack and blunt and float(haack[0]["cdAtDesignMach"]) < float(blunt[0]["cdAtDesignMach"]),
          f'{haack[0]["cdAtDesignMach"] if haack else "?"} vs {blunt[0]["cdAtDesignMach"] if blunt else "?"} at Mach {sh["designMach"]}')
    check(sc, "shape study < 20 s", dt < 20, f"{dt:.1f} s")

    sec = s.call("recovery_sections", {"designId": d})
    check(sc, "sections derived from the bays (nose / main bay / drogue bay + fin can)", len(sec["sections"]) == 3,
          json.dumps([x["section"] for x in sec["sections"]]))
    check(sc, "each section has landing energy vs the limit", all("landingEnergy" in x for x in sec["sections"]))
    check(sc, "bay fill reported for the main and drogue bays", sum(len(x.get("bays", [])) for x in sec["sections"]) == 2)

    ld = s.call("structural_loads", {"designId": d, "allowableStress": "150 MPa"})
    check(sc, "loads at every joint with axial and bending", all("maxAxialCompression" in j and "bendingMomentMaxQGust" in j
                                                                for j in ld["joints"]) and len(ld["joints"]) >= 2)
    check(sc, "fiberglass airframe has positive margin", all("OK" in j.get("margin", "OK") for j in ld["joints"]),
          json.dumps([j.get("margin") for j in ld["joints"]]))

    dg = s.call("load_diagrams", {"designId": d})
    check(sc, "load diagrams (UTAT method): maxima, joints and stations", "maxBendingMoment" in dg and len(dg["joints"]) >= 2
          and len(dg["stations"]) > len(dg["joints"]), str(dg)[:200])
    check(sc, "load diagrams cite the team's script", any("Fred Chun" in n and "UTAT-Rocketry" in n for n in dg["notes"]))


def scenario_launch_day(s, d, tmp):
    """'Here are our launch site coordinates: what will the winds do, and give us the flight card.'"""
    sc = "launch day: weather, flight card, heating, roll"
    ask = s.call("weather_forecast", {"designId": d, "apply": False}, expect_error=True)
    check(sc, "asks for GPS coordinates when the site is unknown", "GPS coordinates" in ask, ask[:120])
    sample = open(os.path.join(ROOT, "src", "test", "resources", "open-meteo-sample.json")).read()
    wx = s.call("weather_forecast", {"designId": d, "forecastJson": sample, "time": "2027-08-21T15:00"})
    ens = os.path.join(ROOT, "src", "test", "resources", "open-meteo-ensemble-sample.json")
    lp = s.call("launch_planner", {"designId": d, "ensembleJson": ens, "time": "2027-08-21T10:00", "runs": 40, "radius": "2 km"})
    check(sc, "launch planner: 50/95/99% landing areas from the ensemble", [r["percent"] for r in lp["landing"][0]["areas"]] == [50, 95, 99]
          and lp["landing"][0]["landings"] == 40 and "points" not in lp["landing"][0], str(lp)[:200])
    check(sc, "launch planner: forecast as is, winds by height, count beyond the recovery radius",
          "forecastAsIs" in lp and len(lp["winds"]) >= 5 and "beyondRadius" in lp["landing"][0], str(lp.get("summary"))[:200])
    check(sc, "forecast parsed: ground wind, gusts, winds aloft, rule-limit check",
          all(k in wx["ground"] for k in ("wind", "gusts", "vsRuleLimit")) and len(wx["windsAloft"]) >= 8, json.dumps(wx["ground"]))
    check(sc, "forecast applied and the flight re-simulated", "flightInForecast" in wx)
    card = os.path.join(tmp, "flight-card.md")
    s.call("flight_card", {"designId": d, "path": card})
    md = open(card).read()
    check(sc, "flight card has vehicle, motors + delay, recovery, drift table and rule check",
          all(h in md for h in ("## Vehicle", "optimumDelay", "## Recovery settings", "## Drift vs ground wind", "## Rule check")))
    heat = s.call("aero_heating", {"designId": d})
    check(sc, "heating screen covers nose tip, fins and body with material limits",
          any("(tip)" in x["surface"] for x in heat["surfaces"]) and any("leading edges" in x["surface"] for x in heat["surfaces"]))
    t0 = time.time()
    roll = s.call("roll_analysis", {"designId": d})
    check(sc, "roll analysis gives a fin alignment tolerance", "deg" in roll["alignmentTolerance"], roll["alignmentTolerance"])
    check(sc, "roll sweep < 15 s", time.time() - t0 < 15, f"{time.time() - t0:.1f} s")
    s.call("wind_profile", {"designId": d, "mode": "average"})


def scenario_history_and_rail(s, d, tmp):
    """'Where should the rail buttons go?' and 'How did our past rockets fly compared with the predictions?'"""
    sc = "rail buttons and design library"
    r = s.call("rail_buttons", {"designId": d})
    rec = r["recommended"]
    check(sc, "rail buttons: layout with rail exit, tip-off, slop and button loads",
          all(k in rec for k in ("forwardButton", "aftButton", "railExitVelocity", "tipOff", "slop", "buttonLoads")), json.dumps(rec)[:300])
    orv = num(r["openRocketRailExit"])
    check(sc, "real rail exit is below OpenRocket's whole-rail figure", num(rec["railExitVelocity"]) <= orv + 1e-6,
          f"{rec['railExitVelocity']} vs {orv}")
    if "current" in r:
        check(sc, "the recommendation leaves the rail no slower than the current layout",
              num(rec["railExitVelocity"]) >= num(r["current"]["railExitVelocity"]) - 0.01,
              f"{rec['railExitVelocity']} vs {r['current']['railExitVelocity']}")
    before = r.get("current")
    s.call("rail_buttons", {"designId": d, "apply": True})
    after = s.call("rail_buttons", {"designId": d})
    check(sc, "applied buttons sit where recommended", after["current"]["forwardButton"] == rec["forwardButton"]
          and after["current"]["aftButton"] == rec["aftButton"], json.dumps(after["current"])[:200])
    s.call("undo", {"designId": d})
    back = s.call("rail_buttons", {"designId": d}).get("current")
    check(sc, "undo puts the buttons back", back == before, json.dumps(back)[:200])

    lib = os.path.join(tmp, "library")
    os.makedirs(os.path.join(lib, "2025", "maple"))
    os.makedirs(os.path.join(lib, "2022"))
    s.call("save_design", {"designId": d, "path": os.path.join(lib, "2025", "maple", "maple.ork")})
    old = s.call("open_design", {"example": "Dual parachute"})["designId"]
    s.call("save_design", {"designId": old, "path": os.path.join(lib, "2022", "dual.ork")})
    s.call("close_design", {"designId": old})
    with open(os.path.join(lib, "2025", "maple", "maple-flight1.csv"), "w") as fh:
        fh.write("time,altitude (m)\n")
        for i in range(1200):
            t = i / 10
            a = 0 if t < 1 else 2900 * (1 - ((21 - t) / 20) ** 2) if t < 21 else max(0, 2900 - (t - 21) * 25)
            fh.write(f"{t:.1f},{a:.1f}\n")
    out = s.call("design_library", {"folder": lib})
    check(sc, "library reads every design with its year from the folder",
          out["matches"] == 2 and sorted(x["year"] for x in out["designs"]) == ["2022", "2025"], json.dumps(out)[:300])
    four_m = s.call("design_library", {"folder": lib, "minDiameter": "3.9 in", "maxDiameter": "4.1 in", "motorClass": "L-N"})
    check(sc, "search: 4 in rockets on L-N motors", four_m["matches"] == 1 and four_m["designs"][0]["file"] == "2025/maple/maple.ork",
          json.dumps(four_m["designs"])[:200])
    pv = out.get("predictedVsMeasured", {})
    check(sc, "flight log matched to its design; predicted vs measured with the bias",
          len(pv.get("flights", [])) == 1 and "overall" in pv and pv["flights"][0]["log"] == "2025/maple/maple-flight1.csv",
          json.dumps(pv)[:300])
    near = s.call("design_library", {"folder": lib, "similarTo": d})
    check(sc, "similarTo ranks the other designs", near["matches"] == 1, json.dumps(near)[:200])


def scenario_design_review(s, d, tmp):
    """'What changed since our last design review?'"""
    sc = "design review: diff against the last revision"
    v1 = os.path.join(tmp, "review", "v1.ork")
    s.call("save_design", {"designId": d, "path": v1})
    s.call("edit_components", {"designId": d, "changes": [{"component": "Fins", "properties": {"height": "7 in"}}]})
    t0 = time.time()
    md = os.path.join(tmp, "review", "changes.md")
    diff = s.call("compare_designs", {"designId": d, "baselinePath": v1, "path": md})
    fins = [c for c in diff["componentChanges"] if c.get("change") == "edited" and c["component"].startswith("Fins")]
    check(sc, "fin edit found with old -> new value", fins and any("height" in e for e in fins[0]["edits"]), json.dumps(fins)[:300])
    stab = next(r for r in diff["vehicle"] if r["quantity"] == "static stability at launch")
    h = next(e for e in fins[0]["edits"] if e.startswith("height")) if fins else "height 0 -> 0"
    old_h, new_h = (num(x.strip().split(" (")[0].replace("height", "")) for x in h.split("->"))
    check(sc, "stability moves with fin span (taller fins, more stable)",
          (new_h > old_h) == stab["change"].startswith("+") and new_h != old_h, h + " / " + json.dumps(stab))
    check(sc, "rule-check changes and a summary are reported", "ruleCheckChanges" in diff and diff["summary"])
    check(sc, "Markdown change summary written for the review", os.path.exists(md) and "## Component changes" in open(md).read())
    check(sc, "diff < 10 s", time.time() - t0 < 10, f"{time.time() - t0:.1f} s")
    files = s.call("list_files", {"folder": os.path.join(tmp, "review")})
    check(sc, "list_files finds the saved revision", any(f["path"].endswith("v1.ork") for f in files["files"]), json.dumps(files)[:300])


def scenario_fins_fea_cfd(s, d, tmp):
    """'Optimize our fins, check them in FEA, and give us what we need for CFD.'"""
    sc = "fins, FEA and CFD"
    before = s.call("describe_component", {"designId": d, "component": "Fins"})
    opt = s.call("optimize_fins", {"designId": d, "maxEvaluations": 24, "thicknesses": ["1/8 in", "1/4 in"],
                                   "plotPath": os.path.join(tmp, "fins.svg")})
    best = opt["optimized"]
    check(sc, "fin optimizer returns a planform per stock thickness", len(opt.get("byThickness", [])) == 2, str(opt)[:300])
    check(sc, "optimized fins meet the rules and flutter margin", opt["feasible"] and num(best["minAscentStability"]) >= 1.5
          and (best.get("finFlutterMargin") is None or num(best["finFlutterMargin"]) >= 1.5), str(best))
    check(sc, "only buildable fins (tip chord >= 0.5 in, no aft overhang)",
          num(best["tipChord"]) >= 12.69 and "aftOverhang" not in best, str(best))
    after = s.call("describe_component", {"designId": d, "component": "Fins"})
    check(sc, "optimize_fins leaves the design alone without apply", before == after)
    check(sc, "planform drawing written", "<polygon" in open(os.path.join(tmp, "fins.svg")).read())

    fea = s.call("fin_fea", {"designId": d, "outDir": os.path.join(tmp, "fea")})
    deck = open(fea["files"]["deck"]).read()
    check(sc, "FEA deck: shells, clamped root, flight pressure, modes",
          "TYPE=S8R" in deck and "ROOT,1,6,0" in deck and "*FREQUENCY" in deck and num(fea["load"]["normalForceOnFin"]) > 0)
    if isinstance(fea["fea"], dict):
        fe = fea["fea"]
        freqs = [num(f) for f in fe["naturalFrequencies"]]
        check(sc, "CalculiX ran: stress margin, bending before torsion",
              fe["status"].split(":")[0] in ("PASS", "FAIL", "INFO") and "1st bending" in fe["naturalFrequencies"][0]
              and freqs == sorted(freqs), str(fe)[:300])
    else:
        check(sc, "without CalculiX the deck is written and the user told how to run it", "CalculiX not found" in fea["fea"])

    geo = s.call("export_geometry", {"designId": d, "outDir": os.path.join(tmp, "cfd")})
    stl = geo["files"]["stl"]
    check(sc, "STL with a region per part, base and fins", "base" in stl and "fins" in stl and "nose" in stl, stl)
    check(sc, "CFD run matrix reaches the flight's max Mach", any(c["case"] == "max_mach" for c in geo["cases"])
          and os.path.exists(os.path.join(tmp, "cfd", "cfd-cases.csv")))
    tpl = os.path.join(tmp, "cfd", "cfd-results.csv")
    rows = open(tpl).read().strip().split("\n")
    filled = [rows[0]] + [r.split(",")[0] + ",0,0.55,0.50," for r in rows[1:]]
    open(tpl, "w").write("\n".join(filled) + "\n")
    imp = s.call("import_aero_table", {"designId": d, "path": tpl, "cpUnit": "m"})
    check(sc, "CFD results template reads back through import_aero_table", "vsOpenRocket" in imp, str(imp)[:200])
    s.call("import_aero_table", {"designId": d, "mode": "clear"})
    loads = s.call("structural_loads", {"designId": d, "csvPath": os.path.join(tmp, "loads.csv")})
    check(sc, "joint load cases as CSV for FEA", open(loads["csv"]).read().startswith("joint,station_m"))


def raw(s, tool, args, token=None, cancel_after=None):
    """A tool call with the whole response: (result, progress notifications). With cancel_after, cancel the call after
    that many progress notifications and return (None, notes) once a ping sent afterwards is answered."""
    s.id += 1
    rid = s.id
    params = {"name": tool, "arguments": args}
    if token:
        params["_meta"] = {"progressToken": token}
    s.p.stdin.write(json.dumps({"jsonrpc": "2.0", "id": rid, "method": "tools/call", "params": params}) + "\n")
    s.p.stdin.flush()
    notes, ping = [], None
    while True:
        m = json.loads(s.p.stdout.readline())
        if m.get("method") == "notifications/progress":
            notes.append(m["params"])
            if cancel_after and len(notes) == cancel_after and ping is None:
                s.id += 1
                ping = s.id
                s.p.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/cancelled",
                                            "params": {"requestId": rid, "reason": "benchmark"}}) + "\n")
                s.p.stdin.write(json.dumps({"jsonrpc": "2.0", "id": ping, "method": "ping"}) + "\n")
                s.p.stdin.flush()
            continue
        if ping is not None and m.get("id") == ping:
            return None, notes
        if m.get("id") == rid:
            return m["result"], notes


def scenario_project(s, tmp):
    """'Go through our meeting notes: do the numbers still hold? And what changed since PDR?'"""
    sc = "project manager"
    import shutil
    ork = os.path.join(tmp, "pm-maple.ork")
    shutil.copy(os.path.join(ROOT, "docs/examples/maple-10k.ork"), ork)
    d = s.call("open_design", {"path": ork})["designId"]
    apogee = s.call("run_simulation", {"designId": d})["flight"]["apogee"].split(" (")[0]
    v = s.call("verify_claims", {"designId": d, "claims": [
        {"quantity": "apogee", "value": apogee, "source": "meeting"},
        {"quantity": "apogee", "value": "~20k ft", "source": "Slack"},
        {"quantity": "rail exit", "value": "at least 100 ft/s"},
        {"component": "fins", "property": "material", "value": "carbon"},
        {"quantity": "apogee", "value": "3000"}]})
    st = [c["status"] for c in v["claims"]]
    check(sc, "notes checked against the design (match, mismatch, unitless refused)",
          st == ["MATCH", "MISMATCH", "MATCH", "MATCH", "UNCHECKED"], st)
    s.call("progress_log", {"designId": d, "label": "PDR", "note": "baseline"})
    s.call("edit_components", {"designId": d, "changes": [{"component": "Fins", "properties": {"thickness": "2 mm"}}]})
    p = s.call("progress_log", {"designId": d, "note": "thinner fins"})
    check(sc, "progress record shows what changed since the last one (and the flutter item got worse)",
          "flutter" in json.dumps(p.get("changes", {})) and "Changed:" in p.get("digest", ""), json.dumps(p)[:300])
    h = s.call("progress_log", {"designId": d, "mode": "history"})
    check(sc, "history kept next to the design, milestone labelled",
          h["records"] == 2 and h["history"][0].get("label") == "PDR" and os.path.exists(os.path.join(tmp, "pm-maple.progress.jsonl")))
    s.call("close_design", {"designId": d})


def scenario_user_experience(s, d, tmp):
    """Pictures in the chat, progress and cancel, status, undo, and the landing map for Google Earth."""
    sc = "user experience"
    cap = s.call("capabilities", {})
    asks = [a for g in cap["youCanAsk"].values() for a in g]
    check(sc, "capabilities: everything worded as a team would ask, grouped, with what was used this session",
          len(cap["youCanAsk"]) >= 8 and len(asks) >= 40 and any(", used)" in a for a in asks)
          and any("parachute_sizes" in a for a in asks), cap["notUsedYet"])
    r, _ = raw(s, "draw_rocket", {"designId": d, "path": os.path.join(tmp, "ux.svg")})
    imgs = [c for c in r["content"] if c["type"] == "image"]
    import base64
    check(sc, "the drawing comes back as a PNG image in the result",
          len(imgs) == 1 and imgs[0]["mimeType"] == "image/png" and base64.b64decode(imgs[0]["data"])[:4] == b"\x89PNG")
    kml = os.path.join(tmp, "land.kml")
    r, notes = raw(s, "monte_carlo", {"designId": d, "runs": 40, "kmlPath": kml, "siteLatitude": 48.47,
                                      "siteLongitude": -81.33}, token="mc")
    check(sc, "Monte Carlo reports progress to the end", len(notes) >= 2 and notes[-1]["progress"] == 40
          and notes[-1]["total"] == 40, str(notes[-1:]))
    import xml.etree.ElementTree as ET
    root = ET.parse(kml).getroot()
    ns = "{http://www.opengis.net/kml/2.2}"
    check(sc, "KML landing map: pad, ellipse and every landing",
          len(root.findall(f".//{ns}Polygon")) == 1 and len(root.findall(f".//{ns}Placemark")) >= 40)
    t0 = time.time()
    r, notes = raw(s, "monte_carlo", {"designId": d, "runs": 1000}, token="long", cancel_after=2)
    check(sc, "a long run can be cancelled and the server keeps answering", r is None and time.time() - t0 < 30,
          f"{time.time() - t0:.1f} s")
    st = s.call("design_status", {"designId": d})
    check(sc, "design_status gives readiness and next steps", st["readiness"] and len(st["nextSteps"]) >= 1, str(st)[:300])
    tpl = s.call("mass_budget", {"designId": d, "csvPath": os.path.join(tmp, "mb.csv")})
    rows = open(tpl["template"]).read().splitlines()
    check(sc, "mass budget template from the model", rows[0].startswith("part,component_id,mass (g)") and len(rows) > 5)
    design = lambda: {k: v for k, v in s.call("get_design", {"designId": d}).items() if k != "simulations"}
    launch0 = design()
    mb = s.call("mass_budget", {"designId": d, "csv": "part,mass (g),status\nFins,900,measured\nNose cone,700,measured\n"
                                "Camera,60,measured\n", "targetLaunchMass": "8 kg", "apply": True})
    check(sc, "weigh-ins compared, totals against the target, applied with the new stability",
          "margin" in mb["totals"] and "->" in mb["change"]["launchMass"] and len(mb["applied"]) == 2, str(mb)[:400])
    check(sc, "an unmatched line without a parent is reported, not guessed", "Camera" in mb.get("unmatched", ""))
    s.call("undo", {"designId": d})
    check(sc, "undo reverts the weigh-in (components, masses, stability)", design() == launch0)
    before = s.call("describe_component", {"designId": d, "component": "Fins"})
    s.call("edit_components", {"designId": d, "changes": [{"component": "Fins", "properties": {"thickness": "1 mm"}}]})
    s.call("undo", {"designId": d})
    check(sc, "undo restores the fins exactly", s.call("describe_component", {"designId": d, "component": "Fins"}) == before)


def scenario_avionics(s, tmp):
    """'Lay out our av-bay with two altimeters and a GPS, and show us where everything goes.'"""
    sc = "avionics bay"
    d = s.call("open_design", {"newRocketName": "Bench av-bay"})["designId"]
    stage = s.call("get_design", {"designId": d})["components"][0].split("[")[1][:8]
    add = lambda parent, typ, name, props: s.call("add_component", {"designId": d, "parent": parent, "type": typ,
                                                                    "name": name, "properties": props})
    tube = {"outerDiameter": "4.02 in", "thickness": "2 mm", "material": "Fiberglass"}
    add(stage, "NoseCone", "Nose cone", {"shapeType": "OGIVE", "length": "18 in", "aftDiameter": "4.02 in",
                                          "thickness": "2.5 mm", "aftShoulderLength": "4 in",
                                          "aftShoulderDiameter": "3.9 in"})
    add(stage, "BodyTube", "Upper airframe", dict(tube, length="24 in"))
    add(stage, "BodyTube", "Lower airframe", dict(tube, length="40 in"))
    add("Lower airframe", "InnerTube", "MMT", {"length": "30 in", "outerDiameter": "78 mm", "thickness": "1.5 mm",
                                              "axialMethod": "BOTTOM", "axialOffset": 0})
    add("Lower airframe", "TrapezoidFinSet", "Fins", {"finCount": 4, "rootChord": "9 in", "tipChord": "3 in",
                                                      "span": "5 in", "sweepLength": "5 in", "thickness": "3.2 mm",
                                                      "axialMethod": "BOTTOM", "axialOffset": 0})
    chute = add("Upper airframe", "Parachute", "Main", {"diameter": "60 in", "cd": 0.8})
    check(sc, "new parachute gets a packed size", any("packed size" in a for a in chute["applied"]), str(chute["applied"]))
    add("Upper airframe", "ShockCord", "Main cord", {"cordLength": "25 ft", "length": "4 in"})
    add("Lower airframe", "Parachute", "Drogue", {"diameter": "18 in", "cd": 0.8})
    add("Lower airframe", "ShockCord", "Drogue cord", {"cordLength": "20 ft", "length": "3 in"})
    bay = s.call("add_avionics_bay", {"designId": d})
    parts = [x["part"].lower() for x in bay["layout"]]
    count = lambda word: sum(word in p for p in parts)
    check(sc, "two altimeters, each with its own battery and switch",
          sum(p.endswith("altimeter") for p in parts) == 2 and count("battery (") == 2 and count("switch (") == 2,
          str(parts))
    check(sc, "four ejection charges and a tracker", count("charge") == 4 and "tracker" in bay["electronics"].lower(),
          str(parts))
    warn = [x for x in bay["recoveryPacked"] if x.startswith("WARNING")]
    check(sc, "drogue bay that runs into the motor mount is flagged", any("Lower airframe" in x for x in warn), str(warn))
    s.call("set_motor", {"designId": d, "mount": "MMT", "motor": "L1170FJ"})
    rules = json.dumps(s.call("check_requirements", {"designId": d}))
    check(sc, "rule check sees redundant electronics and separate batteries",
          "Redundant deployment electronics" in rules and "Individual power supply" in rules)
    out = os.path.join(tmp, "avbay.svg")
    s.call("draw_rocket", {"designId": d, "path": out})
    svg = open(out).read()
    check(sc, "cut-away shows electronics, parachutes, charges and separations",
          all(f'class="{c}"' in svg for c in ("elec", "batt", "chute", "charge", "sep")))
    s.call("set_deployment", {"designId": d, "component": "Drogue", "event": "apogee"})
    s.call("set_deployment", {"designId": d, "component": "Main", "event": "altitude", "altitude": "1000 ft"})
    alt = s.call("altimeter_settings", {"designId": d, "path": os.path.join(tmp, "altimeters.md")})
    chans = {c["device"]: c for c in alt["channels"]}
    check(sc, "altimeter settings: backup drogue after apogee, backup main lower, static ports for the bay",
          chans["Drogue"]["backup"] == "apogee + 1 s" and "AGL" in chans["Main"]["backup"]
          and alt["staticPorts"].startswith("bay volume"), json.dumps(alt)[:400])
    check(sc, "Mach lockout set for a transonic flight",
          alt["lockout"]["machLockout"].split()[0].replace(".", "").isdigit(), alt["lockout"]["machLockout"])
    chk = s.call("sensor_check", {"designId": d})
    rows = {(r["sensor"].split(",")[0], r["quantity"].split(",")[0]): r for r in chk["checks"]}
    check(sc, "sensor check covers accelerometers, gyro, barometer (incl. the Mach window) and GPS",
          ("Accelerometer", "axial acceleration (x)") in rows and any(q.startswith("static pressure above Mach") for _, q in rows)
          and any(k[0].startswith("GPS") for k in rows), str(list(rows))[:300])
    csv_path = os.path.join(tmp, "sensors.csv")
    data = s.call("sensor_data", {"designId": d, "path": csv_path, "rate": 50})
    header = open(csv_path).readline().strip().split(",")
    events = open(data["events"]).read()
    check(sc, "sensor CSV with every sensor, truth columns and the true events",
          {"acc_x_g", "gyro_y_dps", "baro_pressure_pa", "gps_lat_deg", "truth_phase"} <= set(header)
          and ",apogee," in events and "deployment: Main" in events, str(header)[:200] + " EVENTS " + events)
    pw = s.call("power_budget", {"designId": d})
    check(sc, "power budget: every team circuit with runtime, e-match current and a status",
          len(pw["circuits"]) >= 3 and all(r["status"] in ("PASS", "WARN", "FAIL") and "runtime" in r for r in pw["circuits"])
          and any("pyroCurrent" in r for r in pw["circuits"]), json.dumps(pw)[:400])
    weak = s.call("power_budget", {"designId": d, "padWait": "12 h", "circuits": [
        {"name": "Coin cell", "voltage": 3, "capacityMah": 220, "currentMa": 30, "internalResistance": 15, "pyroChannels": 1}]})
    check(sc, "power budget fails a coin cell on a long pad wait and a weak e-match current",
          weak["circuits"][0]["status"] == "FAIL" and len(weak["circuits"][0]["issues"]) >= 2, json.dumps(weak)[:400])
    rl = s.call("radio_link", {"designId": d})
    check(sc, "radio link: margin in flight and after landing with a ground range",
          rl["status"] in ("PASS", "WARN", "FAIL") and "afterLanding" in rl["paths"][0] and "groundRange" in rl, json.dumps(rl)[:400])
    far = s.call("radio_link", {"designId": d, "txPowerDbm": -10, "groundStationEast": "5 km"})
    check(sc, "a weak transmitter far from the pad fails the link", far["status"] == "FAIL", json.dumps(far)[:300])
    card = os.path.join(tmp, "avbay-card.md")
    s.call("flight_card", {"designId": d, "path": card})
    md = open(card).read()
    check(sc, "flight card carries the electronics: altimeters, power and radio",
          "## Electronics" in md and "power:" in md and "radio:" in md, md[:300])
    s.call("close_design", {"designId": d})


def main():
    if not os.path.exists(BIN):
        sys.exit(f"Build first: ./gradlew installDist ({BIN} missing)")
    tmp = tempfile.mkdtemp()
    t0 = time.time()
    s = Server()
    scratch = {}
    for name, fn in [("From-scratch 10k ft design", lambda: scratch.setdefault("d", scenario_from_scratch(s))),
                     ("Recovery chain", lambda: scenario_recovery(s)),
                     ("Two-stage", lambda: scenario_two_stage(s)),
                     ("Custom liquid engine", lambda: scenario_custom_engine(s, tmp)),
                     ("Liquid program (2027 edicts)", lambda: scenario_liquid_program(s)),
                     ("Dispersion + report", lambda: scenario_dispersion_report(s, tmp)),
                     ("Structures + vehicle dispersion", lambda: scenario_structures(s, scratch["d"])),
                     ("OpenRocket depth", lambda: scenario_openrocket_depth(s, scratch["d"], tmp)),
                     ("Post-flight", lambda: scenario_post_flight(s, scratch["d"], tmp)),
                     ("Design studies", lambda: scenario_studies(s, scratch["d"])),
                     ("Launch day", lambda: scenario_launch_day(s, scratch["d"], tmp)),
                     ("Rail buttons + design library", lambda: scenario_history_and_rail(s, scratch["d"], tmp)),
                     ("Design review", lambda: scenario_design_review(s, scratch["d"], tmp)),
                     ("Avionics bay", lambda: scenario_avionics(s, tmp)),
                     ("Fins, FEA and CFD", lambda: scenario_fins_fea_cfd(s, scratch["d"], tmp)),
                     ("User experience", lambda: scenario_user_experience(s, scratch["d"], tmp)),
                     ("Project manager", lambda: scenario_project(s, tmp))]:
        print(f"\n== {name}")
        try:
            fn()
        except Exception as e:  # a crashed scenario counts as a failure, keep going
            check(name, "scenario completed", False, repr(e)[:800])
    s.close()
    total = time.time() - t0
    passed = sum(1 for r in RESULTS if r[2])
    slow = sorted(s.calls, key=lambda c: -c[1])[:5]
    big = sorted(s.calls, key=lambda c: -c[3])[:5]
    chars = sum(c[3] for c in s.calls)
    print(f"\n{passed}/{len(RESULTS)} checks passed, {len(s.calls)} tool calls, {total:.1f} s total, "
          f"{chars / 1000:.0f}k chars of tool output (~{chars / 4000:.0f}k tokens)")
    print("slowest calls: " + ", ".join(f"{t} {dt:.1f}s" for t, dt, _, _ in slow))
    print("largest outputs: " + ", ".join(f"{t} {n / 1000:.1f}k" for t, _, _, n in big))
    sys.exit(0 if passed == len(RESULTS) else 1)


if __name__ == "__main__":
    main()
