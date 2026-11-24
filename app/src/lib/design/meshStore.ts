// Which meshes the window keeps (design/meshes.ts builds them): the engine call needs only the ids, and this file
// keeps three.js out of the first download.

export interface Kept {
  geo: { dispose(): void };
  edges: { dispose(): void };
}

export const store = new Map<string, Kept>();

/** Ids of the meshes kept, for the engine to leave out. */
export function knownMeshes(): string[] {
  return [...store.keys()];
}

/** Whether every part's mesh is either in the reply or kept here. */
export function complete(parts: { mesh?: string; meshId?: string; openMesh?: string; openMeshId?: string }[]): boolean {
  const has = (mesh?: string, id?: string) => mesh !== undefined || (id !== undefined && store.has(id));
  return parts.every((p) => has(p.mesh, p.meshId) && (p.openMeshId === undefined || has(p.openMesh, p.openMeshId)));
}

/** Whether a geometry is kept here (then the view must not dispose it). */
export function kept(meshId: string | undefined): boolean {
  return !!meshId && store.has(meshId);
}
