import type { Issue } from "../api";
import { CATALOG } from "../analysis/catalog";
import { label } from "../analysis/text";

// What an error means for someone using the app: the engine's sentence in the app's own words (its messages are
// also read by AI assistants, and name their tools), what could be causing it, and what to try. An error code or
// a bare sentence leaves the user guessing; these lines say where to look.

export interface Explained {
  text: string;
  /** What could be causing it, likeliest first. */
  causes: string[];
  /** What to try. */
  fix?: string;
}

/** "FinCount" as "Fin count". */
function words(camel: string): string {
  const w = camel.replace(/(?<=[a-z0-9])(?=[A-Z])/g, " ");
  return w.charAt(0) + w.slice(1).toLowerCase();
}

const REWRITES: [RegExp, string | ((...m: string[]) => string)][] = [
  [/^Error:\s*/, ""],
  [/\s*Use get_design to list component ids\.?/g, ""],
  [/\s*Use describe_component to list properties\.?/g, ""],
  [/\s*Use search_parts\.?/g, ""],
  [/\s*Open designs: \[[^\]]*\]\.?/g, ""],
  [/\s*Check the path \(list_files shows the workspace\)\.?/g, ""],
  [/\s*Use set_motor first\.?/g, " Choose a motor on the Motors page."],
  // A team-standards key in brackets is for editing the file by hand; the app has the Standards page.
  [/\s*\((?:[a-z][A-Za-z0-9]*\.)+[A-Za-z0-9]+\)/g, ""],
  [/in the team standards/g, "on the Standards page"],
  [/add the material to structures\.shearModulus/, "add the material's shear modulus"],
  // A standards key outside brackets (structures.shearModulus): its last word, as the Standards page names it.
  // Not a file name: those have a short ending (.ork, .csv) or sit in a path.
  [/(?<![\/\\\w-])(?:[a-z][A-Za-z0-9]*\.)+[A-Za-z][A-Za-z0-9]{5,}\b(?![\/\\])/g, (m) => (/[a-z][A-Z]/.test(m) ? label(m.split(".").pop()!).toLowerCase() : m)],

  [/set launchSite\.latitude \/ longitude in the team standards/, "set the launch site on the Standards page"],
  [/^I need the launch site's GPS coordinates: give/, "The forecast needs the launch site's position: give"],
  [/\(upperTube, lowerTube\)/, "(Upper tube and Lower tube in the form)"],
  [/\s*Run the simulation first\.?/g, " Run it on the Flights page first."],
  [/Unknown designId '[^']*'\.?/, "This design is no longer open in the engine."],
  [/No component '[^']*'\.?/, "That part is no longer in the design."],
  [/^Property '([A-Za-z0-9]+)': /, (_m, p) => `${words(p)}: `],
  // Inputs are named in the engine's spelling (targetDescentRate): the form's own words read better.
  [/^Missing required argument '([A-Za-z0-9]+)'/, (_m, p) => `${label(p)} is needed`],
  [/^Argument '([A-Za-z0-9]+)':? /, (_m, p) => `${label(p)}: `],
  [/^'([A-Za-z][A-Za-z0-9]*)' (must be|is yes or no|=)/, (_m, p, v) => `${label(p)} ${v}`],
  [/'([a-z][a-z0-9]*(?:[A-Z][a-zA-Z0-9]*)+)'/g, (_m, p) => label(p)],
  // Bare input names in a list (windSpeed, launchAngle); not units (kPa, mAh) and not parts of a file name or path.
  [/(?<![\/\\.\w-])[a-z]{3,}[a-z0-9]*(?:[A-Z][a-z0-9]+)+(?![\/\\\w-]|\.\w)/g, (m) => label(m)],
  [/: ([A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+) (cannot|=|is)/, (_m, p, v) => `: ${words(p).toLowerCase()} ${v}`],
  [/\((\w+) hit an internal error/, "(the engine hit an internal error"],
  [/^\w+ hit an internal error/, "The engine hit an internal error"],
  [/This is a bug in the server, not in your design: please report it with the call that caused it\./, "This is a bug in RocketMaxxing, not in your design."],
];

// The engine names its tools (for AI assistants); the app has a page, a button or an analysis for each.
const PLACES: Record<string, string> = {
  set_motor: "the Motors page", search_motors: "the Motors page", import_motor_file: "the Motors page", create_custom_motor: "the Motors page",
  run_simulation: "the Flights page", get_flight_data: "the Flights page", animate_flight: "Video on the Flights page",
  add_component: "Add part on the Design page", remove_component: "Delete on the Design page", edit_components: "the Design page",
  get_design: "the Design page", describe_component: "the part's Properties", set_deployment: "the parachute's Deployment settings",
  set_stage_separation: "the stage's Separation settings", flight_configuration: "the Motors page", search_parts: "the part's Catalogue button",
  apply_preset: "the part's Catalogue button", open_design: "Open", save_design: "Save", list_files: "Open", update_standards: "the Standards page",
  get_standards: "the Standards page", load_standards: "the Standards page", targets: "the Targets page", render_3d: "Picture on the Design page",
  draw_rocket: "Picture on the Design page", undo: "Undo", redo: "Redo",
};
const ANALYSES: Record<string, string> = Object.fromEntries(CATALOG.flatMap((g) => g.items.map((a) => [a.tool, a.label])));

/** The engine's sentence without the tool names and identifiers only an AI assistant can use. */
export function plain(text: string): string {
  let t = text;
  for (const [re, to] of REWRITES) t = t.replace(re, to as any);
  t = t.replace(/\b[a-z0-9]+(?:_[a-z0-9]+)+\b/g, (m) => PLACES[m] ?? (ANALYSES[m] ? `the ${ANALYSES[m]} analysis` : m));
  // "Pass X" is how an assistant is told to give an input; a person gives it in the form.
  t = t.replace(/\bPass (?=[A-Z][a-z])/g, "Give ").replace(/\s{2,}/g, " ").trim();
  // A sentence that now starts with an input's name starts with a capital; one that starts with a file name is left.
  const was = text.replace(/^Error:\s*/, "");
  return was.charAt(0) === t.charAt(0) ? t : t.charAt(0).toUpperCase() + t.slice(1);
}

interface Rule {
  test: RegExp;
  causes: string[];
  fix?: string;
}

const RULES: Rule[] = [
  {
    test: /Lost the connection to the RocketMaxxing engine/,
    causes: [
      "The engine (the part of RocketMaxxing that does the calculations) has stopped: the app was closed while this window stayed open, or it crashed.",
      "The computer went to sleep during a long calculation.",
      "A very large job (a long Monte Carlo run, a video) used up the memory.",
    ],
    fix: "Close this window and start RocketMaxxing again. Designs saved to a file are safe; edits since the last save are not.",
  },
  {
    test: /This window is not connected/,
    causes: ["This window belongs to an earlier launch of RocketMaxxing; each launch gives its window a new key.", "The page was opened from a bookmark or a copied address."],
    fix: "Close it and open RocketMaxxing from its icon.",
  },
  {
    test: /The engine answered \d+/,
    causes: ["The engine refused or could not handle the request: usually a bug in RocketMaxxing, sometimes a request too large to send (a huge pasted table)."],
    fix: "Try again once. If it repeats, copy the report from Problems and send it.",
  },
  {
    test: /The app hit an error/,
    causes: ["A bug in the app's interface. The design held by the engine is not affected."],
    fix: "Save the design, then close and reopen the window. If it repeats, send the report from Problems with what you were doing.",
  },
  {
    test: /internal error|bug in RocketMaxxing/,
    causes: [
      "A bug in RocketMaxxing's engine, not a mistake in the design.",
      "It is often set off by an unusual combination: a part with a zero or extreme size, a stage with nothing in it, a motor that no longer exists.",
    ],
    fix: "Undo the last change and try another way. Sending the report from Problems lets it be fixed.",
  },
  {
    test: /no longer open in the engine|Unknown designId/,
    causes: ["The design was closed, or the engine was restarted, while this page still showed it.", "An assistant or another window closed it."],
    fix: "Open the design again from Start.",
  },
  {
    test: /no longer in the design|No component/,
    causes: ["The part was deleted, or its creation undone, after this view was drawn.", "An assistant changed the design at the same moment."],
    fix: "Select the part again in the Parts list.",
  },
  {
    test: /Cannot parse quantity|needs a number or a quantity/,
    causes: ["Letters or symbols where a number was expected.", "A sum such as 10+5: the field takes one number.", "A unit the field does not know."],
  },
  {
    test: /Unknown unit ',/,
    causes: ["A comma used as the decimal mark, or as a thousands separator."],
    fix: "Use a point for decimals and no thousands separator: 1250.5, not 1,250.5 or 1250,5.",
  },
  {
    test: /Unknown unit/,
    causes: ["A unit this quantity does not have, or a typing slip in it.", "A sum or two numbers in one field."],
    fix: "Pick the unit from the list beside the field, or type one it shows there.",
  },
  {
    test: /is an? \w+ but an? \w+ was expected/,
    causes: ["The unit typed belongs to a different kind of quantity, for example a mass where a length or a speed goes."],
    fix: "Use a unit of the kind the message names.",
  },
  {
    test: /cannot be negative/,
    causes: ["A minus sign typed by mistake.", "A position meant as an offset: sizes are never negative, offsets can be."],
    fix: "Enter a size of zero or more.",
  },
  {
    test: /cannot be zero/,
    causes: ["The field was cleared or set to 0.", "The part was meant to be removed, not shrunk to nothing."],
    fix: "Give it a real size, or delete the part.",
  },
  {
    test: /far beyond any rocket part/,
    causes: ["A unit slip: metres typed where millimetres were meant, or the unit list set to a larger unit than intended.", "An extra digit."],
    fix: "Check the unit shown beside the field.",
  },
  {
    test: /is a count/,
    causes: ["A word, a decimal or a negative number where a whole number is needed."],
    fix: "Type digits only, or use the − and + buttons.",
  },
  {
    test: /cannot hold|cannot be placed in|cannot go inside|cannot go in the selected part/,
    causes: [
      "OpenRocket only lets certain parts hold others: stages hold nose cones, tubes and transitions; tubes hold fins, rings, parachutes and masses; nose cones hold masses and recovery gear.",
    ],
    fix: "Select a part that can hold it (the Add part panel greys out what cannot go in the selection), then try again.",
  },
  {
    test: /itself cannot|Cannot remove the rocket|already at the (top|bottom)/,
    causes: ["The action does not apply to this part where it is: the rocket itself stays, and a part at the end of its list cannot move further."],
  },
  {
    test: /has no motors|No motor/,
    causes: ["The active flight configuration has no motor, so there is no flight to work from.", "The configuration picked in the top bar is the empty [No motors] one."],
    fix: "Choose a motor on the Motors page, or pick a configuration that has one.",
  },
  {
    test: /File not found/,
    causes: ["The file was moved, renamed or deleted since it was last used.", "It is on a USB drive, network drive or cloud folder that is not connected or not synced."],
    fix: "Find the file with Open, or reconnect the drive.",
  },
  {
    test: /is a folder, not a design|is a folder: give/,
    causes: ["A folder was chosen where a file is needed."],
  },
  {
    test: /is empty \(0 bytes\)|could not read/,
    causes: [
      "The file is not an OpenRocket (.ork) or RockSim (.rkt) design, even if its name says so.",
      "It is incomplete: a download, a copy or a cloud sync that stopped part-way.",
      "It was written by a newer OpenRocket than the one inside RocketMaxxing.",
    ],
    fix: "Open it in OpenRocket to check it, or use another copy (cloud folders often keep earlier versions).",
  },
  {
    test: /Could not save|Not allowed to read or write/,
    causes: [
      "The folder is read-only, or needs administrator rights (Program Files, the root of a drive).",
      "The file is open in another program, such as OpenRocket.",
      "The disk is full, or a cloud-sync program is holding the file.",
    ],
    fix: "Use Save as and pick another folder, such as Documents.",
  },
  {
    test: /must end in \.ork/,
    causes: ["The file name has another ending. RocketMaxxing saves OpenRocket's own format."],
    fix: 'End the name in ".ork".',
  },
  {
    test: /outline crosses itself|below the root|no height|at least three points|trailing edge of the root/,
    causes: ["A point was dragged past its neighbour, below the body, or onto the root.", "Points were typed out of order: they run from the leading edge round to the trailing edge."],
    fix: "Move the point back, or undo. The outline is unchanged until it is valid.",
  },
  {
    test: /not a colour/,
    causes: ["A colour name or a short code where a six-digit code is needed."],
    fix: "Use the colour picker, or type a code like #1d6fe0.",
  },
  {
    test: /have no catalogue|No \w+ preset/,
    causes: ["OpenRocket's parts database has no entry for this kind of part, or none with that name."],
  },
  {
    test: /timed out|took too long|cancelled/i,
    causes: ["The calculation is larger than usual: many runs, a very fine time step, a long flight.", "The computer is busy with something else."],
    fix: "Run it again with fewer runs or a coarser setting.",
  },
  {
    test: /internet|network|UnknownHost|Connection refused|could not reach|connect to/i,
    causes: ["No internet connection, or a firewall or proxy blocking RocketMaxxing.", "The service it asked is down for the moment."],
    fix: "Check the connection and try again. Everything except the forecast, update check and shop links works offline.",
  },
  {
    test: /CalculiX|ccx|ffmpeg/,
    causes: ["A separate program this feature uses is not installed, or not where RocketMaxxing looks for it."],
    fix: "Install it (the message names it), then try again.",
  },
];

RULES.push(
  {
    test: /must be a whole number|is a count/,
    causes: ["A decimal, a word or a negative number where a count goes."],
    fix: "Type digits only: 3, 100.",
  },
  {
    test: /must be a number, with a unit|must be a number, got/,
    causes: ["Letters or symbols where one number goes.", "A sum (10+5), or a list typed into a box that takes a single value.", "A comma used for thousands (1,000)."],
    fix: 'Type one number, with a unit if the input has one: "20 ft/s", "4 in", "0.05".',
  },
  { test: /is yes or no/, causes: ["A word other than true or false in a yes-or-no input."] },
  {
    test: /outside the physical range|not a usable spread/,
    causes: ["A unit slip (feet typed as metres, a percentage typed as a fraction).", "An extra digit or a missing decimal point."],
    fix: "Check the number against the range in the message, and its unit.",
  },
  {
    test: /^Give |Give exactly one|needs? (at least )?one of|Missing required argument|is needed/,
    causes: ["An input this analysis cannot do without was left empty.", "Of several inputs that can each do the job, none was filled in."],
    fix: "Fill in the input marked with a red star, then run again.",
  },
  {
    test: /numeric rows|Could not find .* columns|needs a part .* column|at least two rows|header/i,
    causes: [
      "The file is not the kind this analysis reads: another program's export, or the wrong file picked.",
      "Its columns are named differently, or the first lines are notes above the header row.",
      "It uses semicolons or decimal commas (a spreadsheet saved in a European locale).",
    ],
    fix: "Open the file in a text editor: it should be comma-separated, with one header row naming the columns the message asks for.",
  },
  {
    test: /GPS coordinates|latitude and longitude/,
    causes: ["The launch site's position is not set, here or in the team standards."],
    fix: "Enter latitude and longitude in decimal degrees (48.47, -81.33), or set the launch site on the Standards page.",
  },
  {
    test: /Say which tubes|between \(upperTube, lowerTube\)|Say which/,
    causes: ["The design has more than one place this could go, and the analysis will not guess."],
    fix: "Pick the parts in the form, then run again.",
  },
  {
    test: /does not (lift off|leave the rail)|never reaches apogee|tumbl|unstable|did not reach/i,
    causes: [
      "The motor is too small for the rocket's mass, or burns out on the rail.",
      "The rocket is unstable: the centre of pressure is ahead of, or too close to, the centre of gravity.",
      "A mass or drag override far from the real value.",
    ],
    fix: "Look at the Summary on the Design page (stability, thrust-to-weight), then at the flight on the Flights page.",
  },
  {
    test: /No (parachute|recovery|fin|motor mount|body tube|altimeter|simulation)|has no (fins|parachute|recovery|stages|body)|not found in the design|needs a (fin|parachute|motor)/i,
    causes: ["The design does not have the part this analysis works on, or has it under another stage.", "The part picked in the form was deleted or renamed."],
    fix: "Add the part on the Design page, or pick another in the form.",
  },
);

const BY_KIND: Record<string, string> = {
  input: "Something typed or chosen cannot be used as it is: a value, a unit or a name.",
  physics: "The design or its flight cannot do what was asked: the simulation found something physically wrong.",
  output: "A result could not be computed, or comes from beyond where the model is valid.",
  system: "Something on this computer (a file, the network, another program) failed, not the design.",
};

/** The message in the app's words, with what could be behind it and what to try. */
export function explain(issue: Issue): Explained {
  const text = plain(issue.text);
  const rule = RULES.find((r) => r.test.test(text) || r.test.test(issue.text));
  if (rule) return { text, causes: rule.causes, fix: rule.fix };
  if (issue.bug) {
    const bug = RULES.find((r) => r.test.source.startsWith("internal error"))!;
    return { text, causes: bug.causes, fix: bug.fix };
  }
  // The engine's own sentence usually says what is wrong; the kind says whose problem it is.
  return { text, causes: [BY_KIND[issue.kind] ?? BY_KIND.system] };
}
