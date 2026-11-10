// The 3-D view's renderer, made once and kept for as long as the window is open. Making one is the slow part of
// showing the Design page: a WebGL context, the lit "studio room" the parts reflect, and the shaders compiled again.
// The Design page comes and goes with every page change, so it borrows this one and gives it back.

import * as THREE from "three";
import { RoomEnvironment } from "three/examples/jsm/environments/RoomEnvironment.js";

export interface ViewRenderer {
  renderer: THREE.WebGLRenderer;
  /** The soft studio lighting, for scene.environment. Belongs to the renderer: never dispose it from a view. */
  environment: THREE.Texture;
}

let shared: ViewRenderer | null = null;
let borrowed = false;

function make(): ViewRenderer {
  const renderer = new THREE.WebGLRenderer({ antialias: true }); // throws where there is no WebGL
  // Reading each shader's log back after compiling stalls the page, and these shaders are three.js's own.
  renderer.debug.checkShaderErrors = false;
  renderer.localClippingEnabled = true;
  // Colours as painted (a neutral curve keeps them), lit by a soft studio room as well as two lamps.
  renderer.toneMapping = THREE.NeutralToneMapping;
  renderer.toneMappingExposure = 1.05;
  const pmrem = new THREE.PMREMGenerator(renderer);
  const room = new RoomEnvironment();
  const environment = pmrem.fromScene(room, 0.04).texture;
  room.dispose();
  pmrem.dispose();
  return { renderer, environment };
}

/** The renderer to draw with (throws where there is no WebGL). Give it back with {@link release}. */
export function borrow(): ViewRenderer {
  if (borrowed) return make(); // a second view at the same time gets its own
  if (!shared) {
    const made = make();
    // A lost context (the graphics driver restarted, the computer slept) is not kept: the next view starts afresh.
    made.renderer.domElement.addEventListener("webglcontextlost", () => {
      if (shared === made) shared = null;
    });
    shared = made;
  }
  borrowed = true;
  return shared;
}

/** Gives the renderer back: its canvas leaves the page; the context, the lighting and the uploaded parts stay. */
export function release(v: ViewRenderer): void {
  v.renderer.domElement.remove();
  v.renderer.renderLists.dispose();
  if (v === shared) {
    borrowed = false;
    return;
  }
  v.environment.dispose();
  v.renderer.dispose();
  v.renderer.forceContextLoss();
}
