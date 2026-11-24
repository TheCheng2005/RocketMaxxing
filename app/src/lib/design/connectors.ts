// Connectors to add from the Add part panel: the fittings a recovery harness is built from. OpenRocket has no part of
// its own for these: each is a mass component of its "Recovery hardware" type, with a typical size and mass (steel,
// forged or ball-bearing). Weigh yours and type its mass in to be exact.

export interface Connector {
  /** Its name in the design; the 3-D view draws it from the name (eyebolt, eye nut, swivel, quick link, shackle, U-bolt). */
  name: string;
  /** Mass (kg), length along the rocket and radius of the space it takes (m). */
  mass: number;
  length: number;
  radius: number;
}

export const CONNECTORS: { group: string; items: Connector[] }[] = [
  {
    group: "Eyebolts (through a bulkhead, with nut and washer)",
    items: [
      { name: 'Eyebolt #10-24, forged', mass: 0.015, length: 0.055, radius: 0.011 },
      { name: 'Eyebolt 1/4"-20, forged', mass: 0.034, length: 0.075, radius: 0.015 },
      { name: 'Eyebolt 5/16"-18, forged', mass: 0.068, length: 0.09, radius: 0.019 },
      { name: 'Eyebolt 3/8"-16, forged', mass: 0.105, length: 0.11, radius: 0.022 },
      { name: 'Eyebolt 1/2"-13, forged', mass: 0.23, length: 0.14, radius: 0.03 },
    ],
  },
  {
    group: "U-bolts (with backing plate)",
    items: [
      { name: 'U-bolt 1/4" with plate', mass: 0.045, length: 0.05, radius: 0.025 },
      { name: 'U-bolt 5/16" with plate', mass: 0.075, length: 0.06, radius: 0.03 },
      { name: 'U-bolt 3/8" with plate', mass: 0.12, length: 0.07, radius: 0.035 },
      { name: 'U-bolt 1/2" with plate', mass: 0.23, length: 0.09, radius: 0.045 },
    ],
  },
  {
    group: "Forged eye nuts (on a threaded rod)",
    items: [
      { name: 'Forged eye nut 1/4"-20', mass: 0.025, length: 0.04, radius: 0.016 },
      { name: 'Forged eye nut 5/16"-18', mass: 0.04, length: 0.045, radius: 0.019 },
    ],
  },
  {
    group: "Quick links (maillons)",
    items: [
      { name: 'Quick link 1/8" (3.5 mm)', mass: 0.006, length: 0.035, radius: 0.008 },
      { name: 'Quick link 5/32" (4 mm)', mass: 0.01, length: 0.04, radius: 0.009 },
      { name: 'Quick link 3/16" (5 mm)', mass: 0.017, length: 0.05, radius: 0.011 },
      { name: 'Quick link 1/4" (6 mm)', mass: 0.028, length: 0.06, radius: 0.013 },
      { name: 'Quick link 5/16" (8 mm)', mass: 0.06, length: 0.075, radius: 0.016 },
      { name: 'Quick link 3/8" (10 mm)', mass: 0.105, length: 0.09, radius: 0.019 },
    ],
  },
  {
    group: "Swivels and shackles",
    items: [
      { name: "Swivel, ball-bearing (1,000 lb)", mass: 0.008, length: 0.055, radius: 0.008 },
      { name: "Swivel, ball-bearing (2,000 lb)", mass: 0.018, length: 0.07, radius: 0.011 },
      { name: 'Bow shackle 3/16"', mass: 0.02, length: 0.04, radius: 0.012 },
      { name: 'Bow shackle 1/4"', mass: 0.036, length: 0.05, radius: 0.015 },
    ],
  },
];

/** The properties of the mass component for one item. */
export function connectorProps(h: Connector): Record<string, unknown> {
  return { name: h.name, massComponentType: "RECOVERYHARDWARE", componentMass: h.mass, length: h.length, radius: h.radius };
}
