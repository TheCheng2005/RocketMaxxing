<script lang="ts">
  import ColorInput from "../ColorInput.svelte";
  import { onMount, untrack } from "svelte";
  import * as THREE from "three";
  import { EffectComposer } from "three/examples/jsm/postprocessing/EffectComposer.js";
  import { RenderPass } from "three/examples/jsm/postprocessing/RenderPass.js";
  import { BokehPass } from "three/examples/jsm/postprocessing/BokehPass.js";
  import { OutputPass } from "three/examples/jsm/postprocessing/OutputPass.js";
  import { Camera, FolderOpen } from "@lucide/svelte";
  import { call } from "../api";
  import { app, run, toast } from "../state.svelte";
  import { borrow, release, type ViewRenderer } from "../design/viewRenderer";
  import { geometry, finPieces } from "../design/meshes";
  import { addUv, decalTexture } from "../design/decals";

  // A picture of the rocket for a poster, a report cover or social media, as OpenRocket's Photo Studio: the camera,
  // the sun and the sky, the ground's shadow, the motor's flame and smoke trail, and a soft focus. Painted parts and
  // decals as in the 3-D view. Saved as a PNG in the Downloads folder.

  type Pose = "flying" | "pad" | "display";
  type Sky = "day" | "sunset" | "night" | "plain";
  let pose = $state<Pose>("flying");
  let sky = $state<Sky>("day");
  let plain = $state("#f4f5f7");
  let turn = $state(35);
  let tilt = $state(12);
  let zoom = $state(1);
  let fov = $state(35);
  let roll = $state(0);
  let sunTurn = $state(40);
  let sunHeight = $state(45);
  let light = $state(1.2);
  let soft = $state(0.5);
  let ground = $state(true);
  let flame = $state(true);
  let smoke = $state(true);
  let blur = $state(0);
  let width = $state(2400);
  let saved = $state<{ path: string; folder: string } | null>(null);
  let busy = $state(false);

  let host: HTMLDivElement;
  let view = $state.raw<ViewRenderer | null>(null);
  let scene = new THREE.Scene();
  let camera = new THREE.PerspectiveCamera(35, 16 / 10, 0.01, 2000);
  let rocket = new THREE.Group();
  let effects = new THREE.Group();
  let sun = new THREE.DirectionalLight(0xffffff, 2);
  let hemi = new THREE.HemisphereLight(0xdfefff, 0x8a7a66, 0.7);
  let floor: THREE.Mesh | null = null;
  let length = 1;
  let radius = 0.05;
  /** The body's radius (not the fins'): what the flame and smoke are sized from. */
  let body = 0.03;
  let made: THREE.Material[] = [];
  const W = 960, H = 600;

  /** The outside of the rocket from the model the Design page has, with its paint and decals. */
  function build() {
    for (const m of made) m.dispose();
    made = [];
    rocket.clear();
    const parts = app.model?.parts ?? [];
    let x1 = 0, r = 0, br = 0;
    for (const p of parts) {
      if (p.internal) continue;
      const built = geometry(p.meshId, p.mesh);
      if (!built) continue;
      const n = p.plateDirs?.length ?? 0;
      const pieces = n > 1 ? finPieces(built, n) : [built];
      for (const b of pieces) {
        const mat = new THREE.MeshStandardMaterial({
          color: new THREE.Color(p.color),
          roughness: p.shine !== undefined && p.shine !== null ? 0.82 - 0.66 * p.shine : 0.5,
          metalness: p.type === "Motor" ? 0.5 : 0.05,
          side: THREE.DoubleSide,
        });
        if (p.decal?.image) {
          addUv(b.geo, n > 0 || (/FinSet$/.test(p.type) && p.type !== "TubeFinSet"));
          decalTexture(p.decal, p.color).then((t) => {
            if (!t || !made.includes(mat)) return;
            mat.map = t;
            mat.color.set(0xffffff);
            mat.needsUpdate = true;
            draw();
          });
        }
        made.push(mat);
        const mesh = new THREE.Mesh(b.geo, mat);
        mesh.castShadow = true;
        mesh.receiveShadow = true;
        rocket.add(mesh);
        const bb = b.geo.boundingBox!;
        const pr = Math.max(Math.abs(bb.max.y), Math.abs(bb.min.y), Math.abs(bb.max.z), Math.abs(bb.min.z));
        r = Math.max(r, pr);
        if (!/Fin|Lug|Button/.test(p.type)) br = Math.max(br, pr);
      }
      x1 = Math.max(x1, p.x1);
    }
    length = Math.max(x1, 0.05);
    radius = Math.max(r, 0.005);
    body = Math.max(br || r / 3, 0.003);
  }

  /** A soft round spot, white in the middle: smoke puffs and the flame's glow. */
  /** What the flame and smoke are made of now, freed when they are made again. */
  let effectStuff: { dispose(): void }[] = [];
  let effectsKey = "";
  let skyKey = "";
  let skyTexture: THREE.Texture | null = null;

  function puff(inner: string, outer: string): THREE.Texture {
    const c = document.createElement("canvas");
    c.width = c.height = 128;
    const g = c.getContext("2d")!;
    const grad = g.createRadialGradient(64, 64, 0, 64, 64, 64);
    grad.addColorStop(0, inner);
    grad.addColorStop(1, outer);
    g.fillStyle = grad;
    g.fillRect(0, 0, 128, 128);
    const t = new THREE.CanvasTexture(c);
    t.colorSpace = THREE.SRGBColorSpace;
    effectStuff.push(t);
    return t;
  }

  /** The motor's flame (two glowing cones and a light) and the smoke trail behind it, in the rocket's own frame. */
  function buildEffects() {
    freeEffects();
    const d = 2 * body;
    const tail = length;
    if (flame) {
      const glow = puff("rgba(255,240,200,1)", "rgba(255,120,20,0)");
      const outer = new THREE.Mesh(
        new THREE.ConeGeometry(d * 0.38, d * 4.5, 24, 1, true),
        new THREE.MeshBasicMaterial({ color: 0xff8a2a, transparent: true, opacity: 0.55, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide }),
      );
      outer.rotation.z = Math.PI / 2; // the cone's point down the rocket's axis, behind it
      outer.position.x = tail + d * 2.25;
      const core = new THREE.Mesh(
        new THREE.ConeGeometry(d * 0.22, d * 2, 24, 1, true),
        new THREE.MeshBasicMaterial({ color: 0xfff1c4, transparent: true, opacity: 0.9, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide }),
      );
      core.rotation.z = Math.PI / 2;
      core.position.x = tail + d * 1;
      const halo = new THREE.Sprite(new THREE.SpriteMaterial({ map: glow, blending: THREE.AdditiveBlending, depthWrite: false, transparent: true, opacity: 0.7 }));
      halo.scale.setScalar(d * 2.2);
      halo.position.x = tail + d * 0.8;
      const lamp = new THREE.PointLight(0xffa040, 3 * light, d * 40, 1.5);
      lamp.position.x = tail + d * 1.2;
      effects.add(outer, core, halo, lamp);
    }
    if (smoke) {
      const tex = puff("rgba(255,255,255,0.6)", "rgba(255,255,255,0)");
      const tone = sky === "night" ? 0x6f788a : sky === "sunset" ? 0xc9b8b0 : 0xd3d8de;
      let seed = 7;
      const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647) - 0.5;
      const start = tail + d * (flame ? 3.5 : 0.5);
      for (let i = 0; i < 90; i++) {
        const t = i / 89;
        // thin and faint at the nozzle, wider and fading down the trail
        const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, color: tone, transparent: true, depthWrite: false, opacity: 0.55 * Math.min(1, t * 6) * (1 - t * 0.75) }));
        const size = d * (1.2 + t * 5.5);
        s.scale.setScalar(size);
        s.position.set(start + t * length * 1.6, rnd() * size * 0.3, rnd() * size * 0.3);
        effects.add(s);
      }
    }
  }

  /** The sky behind the rocket: a gradient (with stars at night) or one colour. */
  function background(): THREE.Color | THREE.Texture {
    skyTexture?.dispose();
    skyTexture = null;
    if (sky === "plain") return new THREE.Color(plain);
    const c = document.createElement("canvas");
    c.width = 16;
    c.height = 512;
    const g = c.getContext("2d")!;
    const grad = g.createLinearGradient(0, 0, 0, 512);
    const stops: Record<Exclude<Sky, "plain">, [number, string][]> = {
      day: [[0, "#2f7fd0"], [0.6, "#8cc4ef"], [1, "#e3f1fb"]],
      sunset: [[0, "#1f2a5c"], [0.45, "#7a4f86"], [0.75, "#ef8a54"], [1, "#ffd48a"]],
      night: [[0, "#03060f"], [0.7, "#0d1a33"], [1, "#22324f"]],
    };
    for (const [at, col] of stops[sky]) grad.addColorStop(at, col);
    g.fillStyle = grad;
    g.fillRect(0, 0, 16, 512);
    let t: THREE.Texture;
    if (sky === "night") {
      const big = document.createElement("canvas");
      big.width = 1024;
      big.height = 512;
      const b = big.getContext("2d")!;
      b.drawImage(c, 0, 0, 1024, 512);
      let seed = 3;
      const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
      for (let i = 0; i < 260; i++) {
        b.fillStyle = `rgba(255,255,255,${0.3 + 0.7 * rnd()})`;
        b.fillRect(rnd() * 1024, rnd() * 400, 1.2, 1.2);
      }
      t = new THREE.CanvasTexture(big);
    } else t = new THREE.CanvasTexture(c);
    t.colorSpace = THREE.SRGBColorSpace;
    skyTexture = t;
    return t;
  }

  /** Where everything goes for the chosen pose, camera, sun and sky. */
  function arrange() {
    // The rocket's middle at the origin, its nose up (pad, flying) or to the left (display), turned about its axis.
    rocket.position.set(0, 0, 0);
    const holder = rocket.parent!;
    holder.rotation.set(0, 0, 0);
    holder.position.set(0, 0, 0);
    rocket.position.x = -length / 2;
    effects.position.x = -length / 2;
    rocket.rotation.set((roll * Math.PI) / 180, 0, 0);
    effects.rotation.set(0, 0, 0);
    if (pose === "display") holder.rotation.z = 0;
    else holder.rotation.z = -Math.PI / 2 + (pose === "flying" ? (12 * Math.PI) / 180 : 0);
    effects.visible = pose === "flying";
    // The ground under the rocket: below its lowest point (the tail on the pad, the side for display).
    const low = pose === "display" ? -radius * 1.05 : -length / 2 - radius * 0.2;
    if (floor) floor.visible = ground && pose !== "flying";
    if (floor) floor.position.y = low;
    // The camera round the rocket.
    camera.fov = fov;
    camera.aspect = W / H;
    const reach = (pose === "display" ? length / 2 : length / 2 + (pose === "flying" ? length * 0.45 : 0)) * 1.12;
    const dist = reach / Math.tan(((fov / 2) * Math.PI) / 180) / zoom;
    const a = (turn * Math.PI) / 180, e = (tilt * Math.PI) / 180;
    camera.position.set(dist * Math.cos(e) * Math.sin(a), dist * Math.sin(e), dist * Math.cos(e) * Math.cos(a));
    const aim = new THREE.Vector3(0, pose === "flying" ? -length * 0.4 : 0, 0);
    camera.lookAt(aim);
    camera.near = dist / 100;
    camera.far = dist * 20 + length * 10;
    camera.updateProjectionMatrix();
    // The sun: its direction and height, the light's strength, and how soft its shadow is.
    const sa = (sunTurn * Math.PI) / 180, sh = (sunHeight * Math.PI) / 180;
    const far = length * 3;
    sun.position.set(far * Math.cos(sh) * Math.sin(sa), far * Math.sin(sh), far * Math.cos(sh) * Math.cos(sa));
    sun.intensity = 2.4 * light * (sky === "night" ? 0.35 : sky === "sunset" ? 0.8 : 1);
    sun.color.set(sky === "sunset" ? 0xffc48a : sky === "night" ? 0xb8c8ff : 0xffffff);
    hemi.intensity = 0.9 * light * (sky === "night" ? 0.35 : 1);
    sun.shadow.radius = 1 + soft * 12;
    sun.shadow.blurSamples = 16;
    const box = length * 0.8;
    Object.assign(sun.shadow.camera, { left: -box, right: box, top: box, bottom: -box, near: 0.01, far: far * 3 });
    sun.shadow.camera.updateProjectionMatrix();
    scene.environmentIntensity = 0.6 * light * (sky === "night" ? 0.3 : 1);
  }

  function draw(w = W, h = H) {
    if (!view) return;
    const r = view.renderer;
    r.shadowMap.enabled = true;
    r.shadowMap.type = THREE.VSMShadowMap;
    r.setPixelRatio(1);
    r.setSize(w, h, false);
    if (blur > 0) {
      const composer = new EffectComposer(r);
      composer.setSize(w, h);
      composer.addPass(new RenderPass(scene, camera));
      // In focus at the rocket's middle; the nose and tail (and the sky) go soft.
      const focus = camera.position.length();
      composer.addPass(new BokehPass(scene, camera, { focus, aperture: (blur * 0.03) / focus, maxblur: 0.02 * blur }));
      composer.addPass(new OutputPass());
      composer.render();
      composer.dispose();
    } else r.render(scene, camera);
  }

  function refresh() {
    if (!view) return;
    // The sky and the flame are made again only when what they show changes, not on every move of the camera.
    const sk = `${sky}|${plain}`;
    if (sk !== skyKey) {
      scene.background = background();
      skyKey = sk;
    }
    const ek = `${flame}|${smoke}|${sky}|${light}`;
    if (ek !== effectsKey) {
      buildEffects();
      effectsKey = ek;
    }
    arrange();
    draw();
  }

  /** Renders at the chosen size and saves it to Downloads. */
  async function save() {
    if (!view) return;
    busy = true;
    try {
      const w = width, h = Math.round((width * H) / W);
      draw(w, h);
      const data = view.renderer.domElement.toDataURL("image/png").split(",")[1];
      draw();
      const name = `${(app.design?.name ?? "Rocket").replace(/[\\/:*?"<>|]/g, "_")} photo.png`;
      const r = await run(() => call<{ path: string; folder: string; folderName: string }>("app_export", { name, base64: data }), "Photo");
      if (r) {
        saved = r;
        toast(`Saved the photo in ${r.folderName}`);
      }
    } finally {
      busy = false;
    }
  }

  onMount(() => {
    try {
      view = borrow();
    } catch {
      return; // no WebGL here: the dialog says so
    }
    const r = view.renderer;
    host.appendChild(r.domElement);
    r.domElement.style.width = "100%";
    r.domElement.style.height = "auto";
    scene.environment = view.environment;
    sun.castShadow = true;
    sun.shadow.mapSize.set(2048, 2048);
    sun.shadow.bias = -0.0005;
    const holder = new THREE.Group();
    holder.add(rocket, effects);
    floor = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.ShadowMaterial({ opacity: 0.35 }));
    floor.rotation.x = -Math.PI / 2;
    floor.receiveShadow = true;
    scene.add(holder, sun, sun.target, hemi, floor);
    build();
    (floor.geometry as THREE.PlaneGeometry).dispose();
    floor.geometry = new THREE.PlaneGeometry(length * 20, length * 20);
    refresh();
    return () => {
      for (const m of made) m.dispose();
      skyTexture?.dispose();
      freeEffects();
      floor?.geometry.dispose();
      (floor?.material as THREE.Material | undefined)?.dispose();
      if (view) {
        view.renderer.shadowMap.enabled = false;
        release(view);
      }
    };
  });
  function freeEffects() {
    effects.traverse((o) => {
      const m = o as THREE.Mesh;
      if (m.isMesh) m.geometry.dispose(); // sprites share one geometry inside three.js: not theirs to free
      (m.material as THREE.Material | undefined)?.dispose();
    });
    effects.clear();
    for (const x of effectStuff) x.dispose();
    effectStuff = [];
  }

  $effect(() => {
    void [pose, sky, plain, turn, tilt, zoom, fov, roll, sunTurn, sunHeight, light, soft, ground, flame, smoke, blur];
    untrack(refresh);
  });

  const POSES: [Pose, string][] = [["flying", "Flying"], ["pad", "On the pad"], ["display", "On display"]];
  const SKIES: [Sky, string][] = [["day", "Day"], ["sunset", "Sunset"], ["night", "Night"], ["plain", "Plain"]];
</script>

<div class="studio">
  <div class="shot" bind:this={host}>
    {#if !view}<p class="muted">The photo studio needs 3-D graphics (WebGL), which this window does not have.</p>{/if}
  </div>
  <div class="ctl">
    <div class="grp">
      <h4>Scene</h4>
      <div class="seg" role="group" aria-label="Pose">{#each POSES as [k, l] (k)}<button class:on={pose === k} onclick={() => (pose = k)}>{l}</button>{/each}</div>
      <div class="seg" role="group" aria-label="Sky">{#each SKIES as [k, l] (k)}<button class:on={sky === k} onclick={() => (sky = k)}>{l}</button>{/each}</div>
      {#if sky === "plain"}<span class="c">Colour <ColorInput label="Background colour" value={plain} onchange={(v) => (plain = v)} /></span>{/if}
      <label class="c"><input type="checkbox" bind:checked={ground} disabled={pose === "flying"} /> Shadow on the ground</label>
      <label class="c"><input type="checkbox" bind:checked={flame} disabled={pose !== "flying"} /> Motor flame</label>
      <label class="c"><input type="checkbox" bind:checked={smoke} disabled={pose !== "flying"} /> Smoke trail</label>
    </div>
    <div class="grp">
      <h4>Camera</h4>
      <label>Round <input type="range" min="-180" max="180" bind:value={turn} /></label>
      <label>Height <input type="range" min="-40" max="70" bind:value={tilt} /></label>
      <label>Zoom <input type="range" min="0.5" max="4" step="0.05" bind:value={zoom} /></label>
      <label>Lens (wide to long) <input type="range" min="15" max="70" bind:value={fov} style="direction: rtl" /></label>
      <label>Roll the rocket <input type="range" min="0" max="360" bind:value={roll} /></label>
      <label>Soft focus <input type="range" min="0" max="1" step="0.05" bind:value={blur} /></label>
    </div>
    <div class="grp">
      <h4>Light</h4>
      <label>Sun round <input type="range" min="-180" max="180" bind:value={sunTurn} /></label>
      <label>Sun height <input type="range" min="5" max="88" bind:value={sunHeight} /></label>
      <label>Brightness <input type="range" min="0.3" max="2.5" step="0.05" bind:value={light} /></label>
      <label>Soft shadows <input type="range" min="0" max="1" step="0.05" bind:value={soft} /></label>
    </div>
    <div class="save">
      <select class="select" bind:value={width} aria-label="Width">
        <option value={1600}>1600 px</option><option value={2400}>2400 px</option><option value={3840}>3840 px (4K)</option>
      </select>
      <button class="btn primary" disabled={busy || !view} onclick={save}><Camera size={14} /> Save photo</button>
      {#if saved}<button class="btn" onclick={() => call("app_reveal", { path: saved!.path }).catch(() => {})}><FolderOpen size={14} /> Show</button>{/if}
    </div>
  </div>
</div>

<style>
  .studio {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 250px;
    gap: 14px;
  }
  .shot {
    min-height: 200px;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    overflow: hidden;
    background: #000;
    align-self: start;
  }
  .shot :global(canvas) {
    display: block;
    width: 100%;
  }
  .ctl {
    display: flex;
    flex-direction: column;
    gap: 12px;
    font-size: calc(12px * var(--text));
  }
  .grp {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  h4 {
    margin: 0;
    font-size: calc(12.5px * var(--text));
  }
  label {
    display: flex;
    flex-direction: column;
    gap: 2px;
    color: var(--ink-2);
  }
  label.c {
    flex-direction: row;
    align-items: center;
    gap: 6px;
  }
  .seg {
    display: flex;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    overflow: hidden;
  }
  .seg button {
    flex: 1;
    border: 0;
    background: var(--surface);
    padding: 4px 6px;
    color: var(--ink-2);
    font-size: calc(11.5px * var(--text));
  }
  .seg button.on {
    background: var(--select);
    color: #fff;
  }
  .save {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
  }
</style>
