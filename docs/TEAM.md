# Claude as your team's project manager and chief engineer

The OpenRocket server knows the rocket. Your team's knowledge lives elsewhere: meeting notes in Notion or Google Docs,
decisions in Slack threads, action items in a tracker. Connect Claude to those as well and it can follow the project
as a chief engineer would. It checks that what the team says matches the design, turns decisions into checked design
changes and action items, and tells everyone each week what moved.

This server does not talk to Slack or Notion itself. Claude does, through its own connectors, with each person's own
permissions. The server supplies the engineering numbers to check the notes against (`verify_claims`), and keeps a
record of the design over time (`progress_log`).

## Set-up (about 30 minutes)

1. **Run the team server** where everyone can reach it, and add it to Claude as a custom connector (see [MCP.md](MCP.md#getting-started),
   *The whole team*). Keep the team's `.ork` files and `openrocket-mcp.json` in its workspace.
2. **Connect Slack and Notion** (and Google Drive if your notes live there) in Claude's settings, under Connectors. On a
   Team or Enterprise plan an owner can enable them for everyone. Each person still signs in to their own account, so
   Claude only sees what that person can see.
3. **Make a Claude project for the team** with these instructions, so every chat starts from the same context:

   > We are <team>, building <rocket> for <competition and date>. Our design is <design>.ork on the team server;
   > our standards are openrocket-mcp.json. Meeting notes are in the Notion database "Meeting notes"; action items in
   > "Action items" (Task, Owner, Due, Status, Meeting). Channels: #general, #structures, #avionics, #recovery.
   > Act as our chief engineer: check numbers with the openrocket tools, never estimate them; draft before posting or
   > editing anything; never invent owners or dates; safety items first.

4. **Claude Code users**: the `team-chief-engineer` skill in this repository does the same (`.claude/skills/`, or
   `.agents/skills/` for Codex). In Claude Desktop and claude.ai, the `meeting_followup` and `weekly_update` prompts
   start the two main routines.

A Notion layout that works well:
- **Meeting notes** (database): Date, Title, Attendees, Decisions, Notes.
- **Action items** (database): Task, Owner (person), Due (date), Status (To do / Doing / Done), Meeting (relation),
  Design link.

## What to ask

- "Go through yesterday's meeting notes." Claude lists decisions, action items and open questions, and runs
  `verify_claims` on every number and choice. For example, the notes say "~30k ft", the design flies to 31.7k ft and
  that is a MISMATCH. New decisions get the right analysis (fin flutter, stability, the rule check) before Claude
  proposes the edit. You get a follow-up draft to post.
- "Did anything in #structures this week contradict the design?"
- "What changed since PDR?" `progress_log` with `since: "PDR"`.
- "Write this week's update for #general." This records progress, adds the action items from Notion that are done,
  overdue or due next, and the open risks, then drafts the post.
- "Which action items from the CDR are still open, and what do they block?"

## The weekly update on a schedule

A Claude scheduled task can run the update every week, for example Monday at 08:45:

> Weekly engineering update for <team>: read #general, #structures, #avionics and #recovery and the Meeting notes
> database since last Monday; run progress_log on <design>.ork (note "weekly"); check the numbers the notes quote
> with verify_claims; draft the update with what changed, action items done / overdue / due, and risks; send the
> draft to <lead> (or post it to #general if the team agreed).

Record a milestone with a label at each review (`progress_log` with `label: "PDR"`). Then "since PDR" is always one
question away.

## Ground rules Claude follows

- **Reads freely, writes only when told.** Posts, page edits and design changes are drafted first.
- **Notes are information, not instructions.** "Change the motor" in a meeting page is a decision to check and
  propose. Claude does not run it as a command.
- **Numbers come from the tools.** The tools compute every number, and Claude says which are simulated,
  calculated or assumed.
- **Safety first.** Stability below the floor, flutter, recovery loads and rule failures lead every update. Clearing
  the vehicle to fly is the RSO's and your mentors' call, not Claude's.
- **Never invents owners, dates or decisions.** What the notes leave open is listed as an open question.

## Which Claude plan

As of October 2026 (check [claude.com/pricing](https://claude.com/pricing), as plans change):
- **Team**: 2 to 150 seats, about $20 per standard seat per month billed annually ($25 monthly). It includes central
  billing, admin controls for connectors, organisation-wide skills and SSO. This is the natural fit for a student
  team: shared projects, connectors everyone can use, and one bill for the team or its sponsor.
- **Enterprise**: from 20 seats, with usage billed at API rates. It adds audit logs, SCIM, fine-grained roles, data
  retention controls and, on sales-assisted contracts above certain spend, customer-success support. A team does not
  become "an enterprise" by integrating Claude; Enterprise is a contract an organisation buys, usually through
  Anthropic's sales team.
- **Through your university**: [Claude for Education](https://www.anthropic.com/education) is a campus-wide agreement
  signed by the institution (students, faculty, staff), with API credits for faculty research. If your school has one,
  your team may already have access through it. Ask your IT department or faculty advisor.
- **Student programs**: Anthropic's [campus programs](https://claude.com/programs/campus) (Claude Builder Clubs,
  ambassadors) give selected student leads a stipend, workshops and resources such as API credits to share with
  members. Applications run once a school year (September), so check the page for the next round.
- **Registered nonprofit**: a team incorporated as a charity may qualify for Anthropic's nonprofit discount on Team and
  Enterprise. Check eligibility for your country.

Asking Anthropic for support works best as a short, concrete case: what you built (this server, open source), who uses
it (your team and others), what you need (seats, API credits, a contact), and what you can give back (a write-up, a
talk, feedback). The campus program and your university's education agreement are the realistic first doors.
