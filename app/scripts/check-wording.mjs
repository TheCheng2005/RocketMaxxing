// Run before every build: the app's rewording of engine messages (src/lib/issues/explain.ts) on sample messages.
// The rules are patterns, so a new one can catch what it should not (a file name, a unit); these cases stop that.
import { build } from "rolldown";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const dir = mkdtempSync(join(tmpdir(), "wording-"));
const out = join(dir, "explain.mjs");
await build({ input: "src/lib/issues/explain.ts", output: { file: out, format: "esm" }, logLevel: "silent" });
const { plain, explain } = await import(pathToFileURL(out).href);
rmSync(dir, { recursive: true, force: true });

const cases = [
  // input names and tool names become the app's words
  ["Error: 'colour' is not a launch condition (windSpeed, windDirection, launchSiteAltitude).", /Wind speed, Wind direction, Launch site altitude/],
  ["Missing required argument 'outerDiameter' (e.g. \"4 in\").", /^Outer diameter is needed/],
  ["Give burstPressure, or outerDiameter, wallThickness and ultimateStrength.", /^Give Burst pressure, or Outer diameter, Wall thickness and Ultimate strength\./],
  ["Flight configuration '[No motors]' has no motors. Use set_motor first.", /Choose a motor on the Motors page\.$/],
  ["Run check_requirements, then fin_flutter.", /the Rule check analysis.*the Fin flutter analysis/],
  ["Pass shearModulus (e.g. \"2.9 GPa\") or add the material to structures.shearModulus in the team standards.", /^Give Shear modulus .* add the material's shear modulus on the Standards page\.$/],
  ["Set the launch site's altitude in the team standards (launchSite.altitudeMsl) so air density is right", /^Set the launch site's altitude on the Standards page so air density is right$/],
  ["No component 'ab12cd34'. Use get_design to list component ids.", /^That part is no longer in the design\.$/],
  // file names, paths and units are left alone
  ["Could not save to /home/me/mapleRocket.ork (access denied).", /\/home\/me\/mapleRocket\.ork/],
  ["File not found: C:\\Users\\me\\myDesigns\\rocketOne.ork.", /myDesigns\\rocketOne\.ork/],
  ["docs is a folder, not a design. Choose a .ork or .rkt file inside it.", /^docs is a folder/],
  ["'pressure' = 900 kPa is outside the physical range [0 kPa, 500 kPa]. Battery 550 mAh at 20 dBm.", /900 kPa.*550 mAh.*20 dBm/],
  ["1.806 cal at t=0.4595 s, Mach 0.106; airspeed > 100 ft/s", /^1\.806 cal at t=0\.4595 s, Mach 0\.106; airspeed > 100 ft\/s$/],
];
let wrong = 0;
for (const [input, want] of cases) {
  const got = plain(input);
  if (!want.test(got)) {
    wrong++;
    console.error(`wording: ${JSON.stringify(input)}\n  became ${JSON.stringify(got)}\n  wanted ${want}`);
  }
}
// Every error gets at least one line on what could be causing it.
for (const text of ["Lost the connection to the RocketMaxxing engine.", "Something nobody wrote a rule for."]) {
  if (!explain({ kind: "system", severity: "error", text }).causes.length) {
    wrong++;
    console.error(`wording: no cause given for ${JSON.stringify(text)}`);
  }
}
if (wrong) process.exit(1);
console.log(`wording: ${cases.length} sample messages read as intended`);
