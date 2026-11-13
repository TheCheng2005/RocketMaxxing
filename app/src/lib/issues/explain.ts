import type { Issue } from "../api";

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
  [/\s*Run the simulation first\.?/g, " Run it on the Flights page first."],
  [/Unknown designId '[^']*'\.?/, "This design is no longer open in the engine."],
  [/No component '[^']*'\.?/, "That part is no longer in the design."],
  [/^Property '([A-Za-z0-9]+)': /, (_m, p) => `${words(p)}: `],
  [/: ([A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+) (cannot|=|is)/, (_m, p, v) => `: ${words(p).toLowerCase()} ${v}`],
  [/\((\w+) hit an internal error/, "(the engine hit an internal error"],
  [/^\w+ hit an internal error/, "The engine hit an internal error"],
  [/This is a bug in the server, not in your design: please report it with the call that caused it\./, "This is a bug in RocketMaxxing, not in your design."],
];

/** The engine's sentence without the tool names and identifiers only an AI assistant can use. */
export function plain(text: string): string {
  let t = text;
  for (const [re, to] of REWRITES) t = t.replace(re, to as any);
  return t.replace(/\s{2,}/g, " ").trim();
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
    causes: ["The unit typed belongs to a different kind of quantity (a mass in a length field)."],
    fix: "Use a unit of the kind the field shows.",
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
