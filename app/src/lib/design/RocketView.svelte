<script lang="ts">
  import { onMount } from "svelte";
  import * as THREE from "three";
  import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
  import { borrow, release, type ViewRenderer } from "./viewRenderer";
  import { geometry, kept, trim, finPieces, disposeBuilt, type Built } from "./meshes";
  import { Maximize, Scissors, Expand } from "@lucide/svelte";
  import { app, select, findNode, parentOf, editField, type Part, type TreeNode } from "../state.svelte";
  import { fmt } from "../units.svelte";
  import { openMenu, currentPageItems, type MenuItem } from "../menu/menu.svelte";
  import { partItems } from "./partMenu";
  import { ui, take } from "../search/ui.svelte";

  let { picture }: { picture?: () => void } = $props();

  // The rocket as OpenRocket draws it (side view, nose to the left; back view; free 3-D), from the engine's own
  // meshes of every part. Outside parts are see-through in the side view so the inside shows, as in OpenRocket.
  // The selected part can be dragged along the rocket (it snaps to the edges of the parts near it), and the view can
  // be pulled apart into an exploded view.

  type Mode = "side" | "back" | "3d";
  const MODE_KEY = "rocketmaxxing.view";
  let mode = $state<Mode>((() => {
    try {
      return (localStorage.getItem(MODE_KEY) as Mode) || "side";
    } catch {
      return "side";
    }
  })());
  let cutaway = $state(true);
  /** 0: assembled, 1: fully exploded. */
  let explode = $state(0);

  let host: HTMLDivElement;
  let canvasHost: HTMLDivElement;
  let cgEl: HTMLDivElement;
  let cpEl: HTMLDivElement;

  let renderer: THREE.WebGLRenderer;
  let scene: THREE.Scene;
  let ortho: THREE.OrthographicCamera;
  let persp: THREE.PerspectiveCamera;
  let camera: THREE.Camera;
  let controls: OrbitControls | null = null;
  let rocket = new THREE.Group();
  let axis: THREE.Line | null = null;
  let guides: THREE.LineSegments | null = null;
  let shadow: THREE.Mesh | null = null;
  let length = 1;
  let radius = 0.05;
  let finOut = 0;
  /** Each part's place at full explosion, relative to where it is installed. */
  let apart = new Map<Part, THREE.Vector3>();
  /** One per drawn piece: a part, or one fin of a fin set (fin: its index). */
  type Piece = { mesh: THREE.Mesh; edges: THREE.LineSegments; part: Part; fin: number | null; owned: Built | null };
  let meshes: Piece[] = [];
  let webgl = $state(true);

  const css = (name: string) => getComputedStyle(document.documentElement).getPropertyValue(name).trim() || "#888";

  /** How a part's surface looks: paint shine makes it glossier; motor cases are metal. */
  function surface(p: Part): { roughness: number; metalness: number } {
    if (p.type === "Motor") return { roughness: 0.38, metalness: 0.55 };
    if (p.shine !== undefined && p.shine !== null) return { roughness: 0.82 - 0.66 * p.shine, metalness: 0.04 };
    return { roughness: 0.6, metalness: 0.04 };
  }

  function build(parts: Part[]) {
    for (const m of meshes) {
      // Geometry is kept by mesh id (design/meshes.ts) and reused by the next build; only materials are per build.
      if (m.owned && !kept(m.part.meshId)) disposeBuilt(m.owned);
      (m.mesh.material as THREE.Material).dispose();
      (m.edges.material as THREE.Material).dispose();
    }
    scene.remove(rocket);
    rocket = new THREE.Group();
    meshes = [];
    let x1 = 0;
    let r = 0;
    const clip = [new THREE.Plane(new THREE.Vector3(0, 0, -1), 0)];
    const inUse = new Set<string>();
    for (const p of parts) {
      const built = geometry(p.meshId, p.mesh);
      if (!built) continue;
      if (p.meshId) inUse.add(p.meshId);
      const n = p.plateDirs?.length ?? 0;
      const pieces: [Built, number | null][] = n > 1 ? finPieces(built, n).map((b, k) => [b, k]) : [[built, null]];
      for (const [b, fin] of pieces) {
        const mat = new THREE.MeshStandardMaterial({
          color: new THREE.Color(p.color),
          ...surface(p),
          side: THREE.DoubleSide,
          polygonOffset: true,
          polygonOffsetFactor: 1,
          polygonOffsetUnits: 1,
        });
        if (p.cutOpen) mat.clippingPlanes = clip;
        const mesh = new THREE.Mesh(b.geo, mat);
        const edges = new THREE.LineSegments(b.edges, new THREE.LineBasicMaterial({ color: 0x334155 }));
        if (p.cutOpen) (edges.material as THREE.LineBasicMaterial).clippingPlanes = clip;
        mesh.userData.part = p;
        rocket.add(mesh, edges);
        meshes.push({ mesh, edges, part: p, fin, owned: fin === null ? built : null });
        const bb = b.geo.boundingBox!;
        r = Math.max(r, Math.abs(bb.max.y), Math.abs(bb.min.y), Math.abs(bb.max.z), Math.abs(bb.min.z));
      }
      // The whole fin set is disposed through its parent geometry (its fins with it).
      if (n > 1) meshes[meshes.length - 1].owned = built;
      x1 = Math.max(x1, p.x1);
    }
    trim(inUse);
    length = Math.max(x1, 0.05);
    radius = Math.max(r, 0.005);
    layOut();
    scene.add(rocket);
    if (axis) {
      scene.remove(axis);
      axis.geometry.dispose();
    }
    const g = new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(-0.04 * length, 0, 0), new THREE.Vector3(1.04 * length, 0, 0)]);
    axis = new THREE.Line(g, new THREE.LineDashedMaterial({ color: 0x94a3b8, dashSize: length / 60, gapSize: length / 120 }));
    axis.computeLineDistances();
    scene.add(axis);
    place();
    style();
  }

  // ------------------------------------------------------------------------------------------------ exploded view

  /**
   * The exploded layout, compact enough to read on screen: the airframe pieces pulled apart along the axis by a few
   * diameters, the fins slid out from the tube, and the parts inside laid out in rows under their piece, in order.
   */
  function layOut() {
    apart = new Map();
    const box = new Map<Part, THREE.Box3>();
    for (const { mesh, part } of meshes) {
      const b = box.get(part) ?? new THREE.Box3();
      b.union(mesh.geometry.boundingBox!);
      box.set(part, b);
    }
    let body = 0.005;
    for (const [p, b] of box) if (!p.internal && p.kind !== "fins") body = Math.max(body, b.max.y, -b.min.y, b.max.z, -b.min.z);
    const gap = Math.max(4 * body, 0.07 * length);
    finOut = body * 0.9;
    const shift = (p: Part) => p.piece * gap + (p.stageNo ?? 0) * gap * 0.5;
    const inside: Part[] = [];
    for (const p of box.keys()) {
      if (p.internal) inside.push(p);
      else apart.set(p, new THREE.Vector3(shift(p), 0, 0));
    }
    // Rows below the rocket (and below the slid-out fins), each as tall as its largest part.
    inside.sort((a, b) => a.x0 + shift(a) - (b.x0 + shift(b)) || b.x1 - b.x0 - (a.x1 - a.x0));
    const margin = 0.02 * length;
    const rows: { end: number; parts: Part[]; h: number }[] = [];
    for (const p of inside) {
      const x0 = p.x0 + shift(p);
      const b = box.get(p)!;
      const h = Math.max(b.max.y - b.min.y, b.max.z - b.min.z);
      let row = rows.find((r) => r.end + margin <= x0);
      if (!row) rows.push((row = { end: -Infinity, parts: [], h: 0 }));
      row.parts.push(p);
      row.end = p.x1 + shift(p);
      row.h = Math.max(row.h, h);
    }
    let y = -(radius + finOut + body * 1.2);
    for (const row of rows) {
      const mid = y - row.h / 2;
      for (const p of row.parts) {
        const c = box.get(p)!.getCenter(new THREE.Vector3());
        apart.set(p, new THREE.Vector3(shift(p), mid - c.y, -c.z));
      }
      y -= row.h + body * 0.7;
    }
  }

  /**
   * Where a piece sits now: pulled apart by the exploded view, and shifted along the axis while it is being dragged.
   */
  function place() {
    const e = explode;
    const moving = drag?.ids ?? held?.ids;
    const shift = drag?.dx ?? held?.dx ?? 0;
    for (const { mesh, edges, part, fin } of meshes) {
      const o = (apart.get(part) ?? new THREE.Vector3()).clone().multiplyScalar(e);
      if (fin !== null && part.plateDirs) {
        const d = part.plateDirs[fin];
        o.y += d[0] * finOut * e;
        o.z += d[1] * finOut * e;
      }
      if (moving && part.id && moving.has(part.id)) o.x += shift;
      mesh.position.copy(o);
      edges.position.copy(o);
    }
    lead();
  }

  /** In the exploded view, a dashed line from the selected (or pointed-at) part back to where it is installed. */
  function lead() {
    if (guides) {
      scene.remove(guides);
      guides.geometry.dispose();
      (guides.material as THREE.Material).dispose();
      guides = null;
    }
    if (explode < 0.05) return;
    const ids = new Set([...shapesOf(app.selected), ...shapesOf(app.hover)]);
    const pts: THREE.Vector3[] = [];
    for (const [part, v] of apart) {
      if (!part.id || !ids.has(part.id) || !part.internal) continue;
      const c = new THREE.Vector3((part.x0 + part.x1) / 2, 0, 0);
      pts.push(c, c.clone().addScaledVector(v, explode));
    }
    if (!pts.length) return;
    const mat = new THREE.LineDashedMaterial({ color: new THREE.Color(css("--select")), dashSize: length / 150, gapSize: length / 250, depthTest: false, transparent: true });
    guides = new THREE.LineSegments(new THREE.BufferGeometry().setFromPoints(pts), mat);
    guides.computeLineDistances();
    guides.renderOrder = 25;
    scene.add(guides);
  }

  let explodeFrame = 0;
  /** Eases the explosion to {@code to} over a moment, keeping the whole rocket in view. */
  function explodeTo(to: number) {
    cancelAnimationFrame(explodeFrame);
    const from = explode, t0 = performance.now(), T = 480;
    const step = () => {
      const k = Math.min(1, (performance.now() - t0) / T);
      explode = from + (to - from) * (1 - Math.pow(1 - k, 3));
      place();
      fit(false);
      if (k < 1) explodeFrame = requestAnimationFrame(step);
    };
    explodeFrame = requestAnimationFrame(step);
  }

  function setExplode(v: number) {
    explode = v;
    place();
    fit(false);
  }

  /**
   * The parts a tree node stands for in the view: the part itself, or, for a stage, pod set or other group that has
   * no shape of its own, everything in it.
   */
  function shapesOf(id: string | null): Set<string> {
    const out = new Set<string>();
    if (!id || id === "rocket") return out;
    if (meshes.some((m) => m.part.id === id)) {
      out.add(id);
      return out;
    }
    const n = findNode(app.design?.tree, id);
    if (n) subtree(n, out);
    return out;
  }

  function subtree(n: TreeNode, out = new Set<string>()): Set<string> {
    out.add(n.id);
    n.children.forEach((c) => subtree(c, out));
    return out;
  }

  /** 0..1 for the pulse after a selection (1 at the start, gone after PULSE ms). */
  const PULSE = 700;
  let pulse = 0;
  let pulseFrame = 0;

  function startPulse() {
    cancelAnimationFrame(pulseFrame);
    const t0 = performance.now();
    const step = () => {
      const k = (performance.now() - t0) / PULSE;
      pulse = k >= 1 ? 0 : Math.sin(Math.PI * Math.min(1, k * 1.6)) * (1 - k);
      glow();
      if (k < 1) pulseFrame = requestAnimationFrame(step);
    };
    pulseFrame = requestAnimationFrame(step);
  }

  /** Only the selected parts' tint, for the pulse (the rest of the look does not change frame to frame). */
  function glow() {
    const sel = new THREE.Color(css("--select"));
    const chosen = shapesOf(app.selected);
    for (const { mesh, part } of meshes) {
      if (part.id && chosen.has(part.id)) (mesh.material as THREE.MeshStandardMaterial).emissive = sel.clone().multiplyScalar(0.5 + 0.45 * pulse);
    }
    render();
  }

  /**
   * Looks of every part for the current view mode, selection and preview. The selected part is tinted and outlined
   * and the rest dims; an inside part shows through the airframe (x-ray) even without the cut-away. A previewed part
   * (pointed at in the tree or the view, or where Add part would put a new one) gets a lighter tint and outline.
   */
  function style() {
    const sel = new THREE.Color(css("--select"));
    const ink = new THREE.Color(css("--icon-ink"));
    const chosen = shapesOf(app.selected);
    const previewed = shapesOf(app.hover);
    const focus = chosen.size > 0;
    // Pulled apart, nothing hides anything: every part is drawn solid.
    const apart = explode > 0.5;
    for (const { mesh, edges, part } of meshes) {
      const m = mesh.material as THREE.MeshStandardMaterial;
      const e = edges.material as THREE.LineBasicMaterial;
      const selected = !!part.id && chosen.has(part.id);
      const hovered = !selected && !!part.id && previewed.has(part.id);
      const outside = !part.internal;
      let opacity = 1;
      let xray = false;
      if (mode === "3d") {
        // An inside part that is selected is drawn through the airframe instead of hiding behind it.
        xray = selected && !outside && !cutaway && !apart;
        mesh.visible = outside || cutaway || xray || hovered || apart;
        if (focus && !selected) opacity = outside ? 0.4 : 0.55;
        edges.visible = selected || hovered;
        m.clippingPlanes = cutaway && part.cutOpen && !apart ? [new THREE.Plane(new THREE.Vector3(0, 0, -1), 0)] : [];
        e.clippingPlanes = m.clippingPlanes;
      } else {
        mesh.visible = true;
        m.clippingPlanes = [];
        e.clippingPlanes = [];
        if (apart) opacity = selected || !focus ? 1 : 0.4;
        else opacity = outside ? (selected ? 0.6 : focus ? 0.16 : 0.28) : selected || !focus ? 1 : 0.45;
        edges.visible = true;
      }
      m.transparent = opacity < 1 || xray;
      m.opacity = xray ? 0.92 : opacity;
      m.depthWrite = !m.transparent;
      m.depthTest = !xray;
      e.depthTest = !xray;
      const glow = selected ? 0.5 + 0.45 * pulse : hovered ? 0.28 : 0;
      m.emissive = glow ? sel.clone().multiplyScalar(glow) : new THREE.Color(0x000000);
      e.color = selected || hovered ? sel : ink;
      e.opacity = selected ? 1 : hovered ? 0.8 : focus ? 0.3 : 0.55;
      e.transparent = !selected;
      mesh.renderOrder = xray ? 10 : outside ? 2 : 1;
      edges.renderOrder = xray ? 11 : 0;
      m.needsUpdate = true;
    }
    if (axis) axis.visible = mode !== "back" && explode < 0.05;
    lead();
    if (shadow) shadow.visible = mode === "3d";
    frame(chosen, previewed);
    render();
  }

  /**
   * In the side and back views, a box around the selected part (and a fainter one around a previewed part), drawn
   * over everything: through a see-through airframe a tint alone is easy to miss.
   */
  let boxes = new THREE.Group();
  function frame(chosen: Set<string>, previewed: Set<string>) {
    scene.remove(boxes);
    boxes.traverse((o) => {
      if (o instanceof THREE.LineSegments) {
        o.geometry.dispose();
        (o.material as THREE.Material).dispose();
      }
    });
    boxes = new THREE.Group();
    if (mode !== "3d") {
      rocket.updateMatrixWorld(true);
      const sel = new THREE.Color(css("--select"));
      const pad = radius * 0.12;
      for (const [ids, opacity] of [[chosen, 1], [previewed, 0.55]] as const) {
        const box = new THREE.Box3();
        for (const { mesh, part } of meshes) if (part.id && ids.has(part.id)) box.expandByObject(mesh);
        if (box.isEmpty() || (ids === previewed && [...previewed].every((id) => chosen.has(id)))) continue;
        box.expandByScalar(pad);
        const helper = new THREE.Box3Helper(box, sel);
        const mat = helper.material as THREE.LineBasicMaterial;
        mat.depthTest = false;
        mat.transparent = opacity < 1;
        mat.opacity = opacity;
        helper.renderOrder = 20;
        boxes.add(helper);
      }
    }
    scene.add(boxes);
  }

  /** The box round everything drawn now (exploded or not). */
  function bounds(): THREE.Box3 {
    rocket.updateMatrixWorld(true);
    const b = new THREE.Box3();
    for (const { mesh } of meshes) b.expandByObject(mesh);
    if (b.isEmpty()) b.set(new THREE.Vector3(0, -radius, -radius), new THREE.Vector3(length, radius, radius));
    return b;
  }

  /** A soft shadow on the floor under the rocket in the 3-D view, so it does not float in space. */
  function floor(b: THREE.Box3) {
    if (!shadow) {
      const c = document.createElement("canvas");
      c.width = c.height = 128;
      const g = c.getContext("2d")!;
      const grad = g.createRadialGradient(64, 64, 0, 64, 64, 64);
      grad.addColorStop(0, "rgba(0,0,0,0.38)");
      grad.addColorStop(0.55, "rgba(0,0,0,0.14)");
      grad.addColorStop(1, "rgba(0,0,0,0)");
      g.fillStyle = grad;
      g.fillRect(0, 0, 128, 128);
      const tex = new THREE.CanvasTexture(c);
      shadow = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshBasicMaterial({ map: tex, transparent: true, depthWrite: false, toneMapped: false }));
      shadow.rotation.x = -Math.PI / 2;
      shadow.renderOrder = -1;
      scene.add(shadow);
    }
    const size = b.getSize(new THREE.Vector3());
    shadow.scale.set(size.x * 1.15, Math.max(size.z, radius * 2) * 3.2, 1);
    shadow.position.set((b.min.x + b.max.x) / 2, b.min.y - radius * 0.9, (b.min.z + b.max.z) / 2);
    shadow.visible = mode === "3d";
  }

  /** Frames the whole rocket (and, exploded, everything laid out below it); {@code reset} also resets the turn. */
  function fit(reset = true) {
    if (!renderer) return;
    const w = canvasHost.clientWidth || 1;
    const h = canvasHost.clientHeight || 1;
    const aspect = w / h;
    const b = bounds();
    const c = b.getCenter(new THREE.Vector3());
    const size = b.getSize(new THREE.Vector3());
    floor(b);
    if (controls && reset) controls.dispose();
    if (mode === "3d") {
      persp.aspect = aspect;
      persp.near = length / 1000;
      persp.far = length * 100;
      // Keep the direction the user turned it to (unless resetting), and move back until all of it is in view.
      const dir = reset || !controls ? new THREE.Vector3(0.32, 0.38, 0.87).normalize() : persp.position.clone().sub(controls.target).normalize();
      let d = (Math.max(size.x, size.y, size.z) * 0.6) / Math.tan(((persp.fov / 2) * Math.PI) / 180);
      const corners = [0, 1, 2, 3, 4, 5, 6, 7].map((i) => new THREE.Vector3(i & 1 ? b.max.x : b.min.x, i & 2 ? b.max.y : b.min.y, i & 4 ? b.max.z : b.min.z));
      for (let k = 0; k < 6; k++) {
        persp.position.copy(c).addScaledVector(dir, d);
        persp.up.set(0, 1, 0);
        persp.lookAt(c);
        persp.updateProjectionMatrix();
        persp.updateMatrixWorld();
        let m = 0;
        for (const p of corners) {
          const v = p.clone().project(persp);
          m = Math.max(m, Math.abs(v.x), Math.abs(v.y));
        }
        d *= Math.max(0.5, Math.min(2, m / 0.86));
      }
      persp.position.copy(c).addScaledVector(dir, d);
      persp.updateProjectionMatrix();
      camera = persp;
    } else {
      let halfW: number, halfH: number;
      if (mode === "side") {
        halfW = (size.x * 1.12) / 2;
        // Room above for the CG and CP labels.
        halfH = Math.max(radius * 2.6, size.y * 0.62 + radius * 0.6);
      } else {
        halfW = halfH = Math.max(size.y, size.z) * 0.8;
      }
      if (halfW / aspect < halfH) halfW = halfH * aspect;
      else halfH = halfW / aspect;
      ortho.left = -halfW;
      ortho.right = halfW;
      ortho.top = halfH;
      ortho.bottom = -halfH;
      ortho.near = -length * 10;
      ortho.far = length * 10;
      ortho.zoom = 1;
      ortho.up.set(0, 1, 0);
      if (mode === "side") ortho.position.set(c.x, c.y, length * 3);
      else ortho.position.set(length * 4, 0, 0);
      ortho.lookAt(mode === "side" ? new THREE.Vector3(c.x, c.y, 0) : new THREE.Vector3(length, 0, 0));
      ortho.updateProjectionMatrix();
      camera = ortho;
    }
    // Zoom limits: never through the rocket or out of sight, however fast the wheel or the trackpad.
    const span = Math.max(length, size.x);
    minDist = Math.max(radius * 1.8, span * 0.02); // outside the airframe when orbiting its axis
    maxDist = span * 8;
    maxZoom = Math.max(4, ortho.top / (radius * 0.2));
    if (!controls || reset) {
      controls = new OrbitControls(camera, renderer.domElement);
      controls.enableDamping = false;
      controls.screenSpacePanning = true;
      controls.zoomToCursor = true;
      controls.addEventListener("change", render);
    }
    controls.object = camera;
    controls.minDistance = minDist;
    controls.maxDistance = maxDist;
    controls.minZoom = MIN_ZOOM;
    controls.maxZoom = maxZoom;
    controls.target.set(mode === "back" ? length : c.x, mode === "side" ? c.y : mode === "3d" ? c.y : 0, mode === "3d" ? c.z : 0);
    controls.enableRotate = mode === "3d";
    controls.mouseButtons =
      mode !== "3d" ? { LEFT: THREE.MOUSE.PAN, MIDDLE: THREE.MOUSE.DOLLY, RIGHT: THREE.MOUSE.PAN } : { LEFT: THREE.MOUSE.ROTATE, MIDDLE: THREE.MOUSE.DOLLY, RIGHT: THREE.MOUSE.PAN };
    controls.update();
    render();
  }

  // Wheel and trackpad zoom, done here rather than by OrbitControls, which zooms each event in one step (a fast flick
  // or a trackpad pinch, which it multiplies by 10, jumped) and could carry the camera through the point it orbits.
  // Each event's share is capped, a fling is eased over a few frames, and it zooms toward the pointer within limits.
  const MIN_ZOOM = 0.5;
  let minDist = 0.01;
  let maxDist = 100;
  let maxZoom = 50;
  let zoomLeft = 0; // log of the zoom still to apply
  let zoomPoint = { x: 0, y: 0 };
  let zoomFrame = 0;

  function wheel(e: WheelEvent) {
    if (!controls || !camera) return;
    e.preventDefault();
    e.stopPropagation(); // OrbitControls listens on the canvas inside: it does not get the wheel
    let dy = e.deltaY * (e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? 100 : 1);
    if (e.ctrlKey) dy *= 3; // a trackpad pinch arrives as small wheel steps with Ctrl
    dy = Math.max(-120, Math.min(120, dy));
    zoomLeft = Math.max(-1.2, Math.min(1.2, zoomLeft - dy * 0.0022));
    zoomPoint = { x: e.clientX, y: e.clientY };
    if (!zoomFrame) zoomFrame = requestAnimationFrame(zoomStep);
  }

  function zoomStep() {
    zoomFrame = 0;
    let step = zoomLeft * 0.35;
    if (Math.abs(zoomLeft) < 0.003) step = zoomLeft;
    zoomLeft -= step;
    zoomBy(Math.exp(step), zoomPoint.x, zoomPoint.y);
    if (zoomLeft !== 0) zoomFrame = requestAnimationFrame(zoomStep);
  }

  /** Zooms by {@code f} (above 1: closer) keeping the point under the pointer where it is. */
  function zoomBy(f: number, px: number, py: number) {
    if (!controls) return;
    const rect = renderer.domElement.getBoundingClientRect();
    const ndc = new THREE.Vector3(((px - rect.left) / rect.width) * 2 - 1, -((py - rect.top) / rect.height) * 2 + 1, 0);
    if (camera === ortho) {
      const before = ndc.clone().unproject(ortho);
      ortho.zoom = Math.max(MIN_ZOOM, Math.min(maxZoom, ortho.zoom * f));
      ortho.updateProjectionMatrix();
      const shift = before.sub(ndc.clone().unproject(ortho));
      ortho.position.add(shift);
      controls.target.add(shift);
    } else {
      // Toward the point under the pointer on the plane through the orbit centre, which moves along, so the camera
      // closes in on what is pointed at and never passes the centre.
      const ray = new THREE.Raycaster();
      ray.setFromCamera(new THREE.Vector2(ndc.x, ndc.y), persp);
      const plane = new THREE.Plane().setFromNormalAndCoplanarPoint(persp.getWorldDirection(new THREE.Vector3()), controls.target);
      const p = ray.ray.intersectPlane(plane, new THREE.Vector3()) ?? controls.target.clone();
      const d = persp.position.distanceTo(controls.target);
      const r = Math.max(minDist, Math.min(maxDist, d / f)) / d;
      persp.position.sub(p).multiplyScalar(r).add(p);
      controls.target.sub(p).multiplyScalar(r).add(p);
    }
    controls.update();
    render();
  }

  function marker(el: HTMLDivElement, x: number | null | undefined) {
    if (!el) return;
    if (x === null || x === undefined || !Number.isFinite(x) || mode === "back" || !camera || explode > 0.05) {
      el.style.display = "none";
      return;
    }
    const v = new THREE.Vector3(x, 0, 0).project(camera);
    if (v.z < -1 || v.z > 1) {
      el.style.display = "none";
      return;
    }
    el.style.display = "block";
    el.style.left = `${((v.x + 1) / 2) * canvasHost.clientWidth}px`;
    el.style.top = `${((1 - v.y) / 2) * canvasHost.clientHeight}px`;
  }

  function render() {
    if (!renderer || !camera) return;
    scene.background = new THREE.Color(css("--view-bg"));
    renderer.render(scene, camera);
    const st = app.model?.stability;
    marker(cgEl, st?.cg);
    marker(cpEl, st?.cp);
  }

  function resize() {
    if (!renderer) return;
    renderer.setSize(canvasHost.clientWidth, canvasHost.clientHeight, false);
    fit();
  }

  // ------------------------------------------------------------------------------------------------ pointing

  function ndcOf(e: { clientX: number; clientY: number }): THREE.Vector2 {
    const rect = renderer.domElement.getBoundingClientRect();
    return new THREE.Vector2(((e.clientX - rect.left) / rect.width) * 2 - 1, -((e.clientY - rect.top) / rect.height) * 2 + 1);
  }

  /** The part under the pointer: seen through the airframe, the innermost one is the one meant. */
  function partAt(e: { clientX: number; clientY: number }): Part | null {
    const ray = new THREE.Raycaster();
    ray.setFromCamera(ndcOf(e), camera);
    const hits = ray.intersectObjects(meshes.filter((m) => m.mesh.visible && m.part.id).map((m) => m.mesh), false);
    if (!hits.length) return null;
    let best = hits[0];
    if (mode !== "3d" && explode < 0.5) {
      for (const h of hits) if ((h.object.userData.part as Part).depth > (best.object.userData.part as Part).depth) best = h;
    }
    return best.object.userData.part as Part;
  }

  let down: { x: number; y: number } | null = null;

  function pick(e: PointerEvent) {
    if (e.button !== 0) return;
    if (!down || Math.hypot(e.clientX - down.x, e.clientY - down.y) > 4) return;
    down = null;
    const p = partAt(e);
    select(p?.id ?? "rocket");
  }

  // What is under the pointer, named next to it and marked in the parts list.
  let tip = $state<{ x: number; y: number; text: string } | null>(null);
  let cursor = $state("default");
  let hoverFrame = 0;
  let lastPointer: PointerEvent | null = null;
  function hover(e: PointerEvent) {
    lastPointer = e;
    if (drag || e.buttons) return;
    if (!hoverFrame) hoverFrame = requestAnimationFrame(hoverNow);
  }
  function hoverNow() {
    hoverFrame = 0;
    const e = lastPointer;
    if (!e || drag || !renderer) return;
    const p = partAt(e);
    const id = p?.id ?? null;
    if (app.hover !== id && !(id === null && app.hover && !meshes.some((m) => m.part.id === app.hover))) app.hover = id;
    const r = canvasHost.getBoundingClientRect();
    tip = p ? { x: e.clientX - r.left, y: e.clientY - r.top, text: p.name } : null;
    cursor = p && p.id === app.selected && movable() ? (mode === "3d" ? "grab" : "ew-resize") : p ? "pointer" : "default";
  }
  function unhover() {
    tip = null;
    if (app.hover && meshes.some((m) => m.part.id === app.hover)) app.hover = null;
  }

  // ------------------------------------------------------------------------------------------------ moving a part

  /** Whether the selected part can be slid along the rocket (airframe pieces follow each other; they move in the list). */
  function movable(): boolean {
    const ed = app.editor;
    if (!ed || ed.id !== app.selected || explode > 0.05) return false;
    const pos = ed.sections.find((s) => s.title === "Position")?.fields;
    const method = pos?.find((f) => f.key === "axialMethod")?.value;
    return !!pos?.some((f) => f.key === "axialOffset") && method !== "AFTER";
  }

  type Edge = { x: number; what: string };
  let drag: {
    ids: Set<string>;
    x0: number;
    x1: number;
    start: { x: number; y: number };
    /** Screen pixels per metre along the rocket's axis, as a direction. */
    axis: THREE.Vector2;
    targets: Edge[];
    middle: { x: number; what: string } | null;
    dx: number;
    snapped: string | null;
    name: string;
    offset: number;
  } | null = null;
  let readout = $state<{ x: number; y: number; text: string; snap: string | null } | null>(null);
  let guide: THREE.Line | null = null;

  function screenOf(p: THREE.Vector3): THREE.Vector2 {
    const v = p.clone().project(camera);
    return new THREE.Vector2(((v.x + 1) / 2) * canvasHost.clientWidth, ((1 - v.y) / 2) * canvasHost.clientHeight);
  }

  function span(ids: Set<string>): [number, number] | null {
    let a = Infinity, b = -Infinity;
    for (const { part } of meshes) if (part.id && ids.has(part.id)) ((a = Math.min(a, part.x0)), (b = Math.max(b, part.x1)));
    return a < b ? [a, b] : null;
  }

  function startDrag(e: PointerEvent): boolean {
    if (!movable() || e.button !== 0) return false;
    const p = partAt(e);
    if (!p || p.id !== app.selected) return false;
    const tree = app.design?.tree;
    const node = findNode(tree, app.selected);
    if (!tree || !node) return false;
    const own = span(new Set([node.id]));
    const ids = subtree(node);
    const s = own ?? span(ids);
    if (!s) return false;
    // Edges it can line up with: its parent's ends and middle, and the parts beside it in the same parent.
    const parent = parentOf(tree, node.id);
    const targets: Edge[] = [];
    let middle: { x: number; what: string } | null = null;
    if (parent) {
      const ps = span(new Set([parent.id])) ?? span(subtree(parent));
      if (ps && parent.id !== "rocket") {
        targets.push({ x: ps[0], what: `the front of ${parent.name}` }, { x: ps[1], what: `the back of ${parent.name}` });
        middle = { x: (ps[0] + ps[1]) / 2, what: `the middle of ${parent.name}` };
      }
      for (const c of parent.children) {
        if (c.id === node.id) continue;
        const cs = span(subtree(c));
        if (cs) targets.push({ x: cs[0], what: `the front of ${c.name}` }, { x: cs[1], what: `the back of ${c.name}` });
      }
    }
    const a = screenOf(new THREE.Vector3(0, 0, 0)), b = screenOf(new THREE.Vector3(1, 0, 0));
    const axisPx = b.sub(a);
    if (axisPx.length() < 1e-6) return false; // looking straight down the axis
    const off = app.editor?.sections.find((x) => x.title === "Position")?.fields.find((f) => f.key === "axialOffset")?.value ?? 0;
    drag = { ids, x0: s[0], x1: s[1], start: { x: e.clientX, y: e.clientY }, axis: axisPx, targets, middle, dx: 0, snapped: null, name: node.name, offset: Number(off) || 0 };
    if (controls) controls.enabled = false;
    renderer.domElement.setPointerCapture(e.pointerId);
    cursor = mode === "3d" ? "grabbing" : "ew-resize";
    tip = null;
    return true;
  }

  function dragMove(e: PointerEvent) {
    if (!drag) return;
    const d = new THREE.Vector2(e.clientX - drag.start.x, e.clientY - drag.start.y);
    let dx = d.dot(drag.axis) / drag.axis.lengthSq();
    const perM = drag.axis.length();
    drag.snapped = null;
    let snapX: number | null = null;
    if (!e.altKey) {
      // The nearest edge within 8 px: its front, back or middle on an edge or middle near it.
      let best = 8 / perM;
      const try_ = (mine: number, label: string, t: { x: number; what: string }) => {
        const gap = t.x - (mine + dx);
        if (Math.abs(gap) < best) {
          best = Math.abs(gap);
          drag!.snapped = `${label} lines up with ${t.what}`;
          snapX = t.x;
          dx += gap;
        }
      };
      for (const t of drag.targets) {
        try_(drag.x0, "Front", t);
        try_(drag.x1, "Back", t);
      }
      if (drag.middle) try_((drag.x0 + drag.x1) / 2, "Middle", drag.middle);
    }
    drag.dx = dx;
    place();
    frame(shapesOf(app.selected), shapesOf(app.hover));
    showGuide(snapX);
    render();
    const r = canvasHost.getBoundingClientRect();
    readout = {
      x: e.clientX - r.left,
      y: e.clientY - r.top,
      text: `${drag.name}: ${dx >= 0 ? "+" : "−"}${fmt(Math.abs(dx), "LENGTH")} ${dx >= 0 ? "aft" : "forward"}`,
      snap: drag.snapped,
    };
  }

  function showGuide(x: number | null) {
    if (guide) {
      scene.remove(guide);
      guide.geometry.dispose();
      (guide.material as THREE.Material).dispose();
      guide = null;
    }
    if (x === null) return;
    const h = radius * 2.4;
    const g = new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(x, -h, 0), new THREE.Vector3(x, h, 0)]);
    guide = new THREE.Line(g, new THREE.LineBasicMaterial({ color: new THREE.Color(css("--select")), depthTest: false, transparent: true }));
    guide.renderOrder = 30;
    scene.add(guide);
  }

  async function endDrag(e: PointerEvent) {
    if (!drag) return;
    const d = drag;
    drag = null;
    readout = null;
    showGuide(null);
    if (controls) controls.enabled = true;
    try {
      renderer.domElement.releasePointerCapture(e.pointerId);
    } catch {
      /* already released */
    }
    cursor = "default";
    if (Math.abs(d.dx) < 1e-5) {
      place();
      style();
      return;
    }
    // Keeps the drawn position until the engine's model of it arrives.
    const shifted = { ids: d.ids, dx: d.dx };
    held = shifted;
    const before = app.model;
    await editField("axialOffset", d.offset + d.dx);
    if (held === shifted && app.model === before) {
      // Not applied (the engine said why): back where it was.
      held = null;
      place();
      style();
    }
  }
  let held: { ids: Set<string>; dx: number } | null = null;

  // Right-click: the part's commands (or the view's alone on empty space). Opened on release, so a right-drag still
  // moves the view without a menu.
  let rightDown: { x: number; y: number } | null = null;
  function viewItems(): MenuItem[] {
    return [
      { header: "View" },
      { label: "Side", checked: mode === "side", run: () => setMode("side") },
      { label: "Back", checked: mode === "back", run: () => setMode("back") },
      { label: "3D", checked: mode === "3d", run: () => setMode("3d") },
      { label: "Exploded", checked: explode > 0, off: mode === "back" && "Not in the back view", run: () => explodeTo(explode > 0 ? 0 : 1) },
      mode === "3d" ? { label: "Cut-away", checked: cutaway, off: explode > 0 && "Not while exploded", run: () => ((cutaway = !cutaway), style()) } : null,
      { label: "Fit", run: () => fit() },
      picture ? { label: "Picture or drawing…", run: picture } : null,
    ].filter(Boolean) as MenuItem[];
  }
  function rightClick(e: PointerEvent) {
    const p = partAt(e);
    const view = viewItems();
    // The page's commands, less those the view's own already has (Picture, Exploded).
    const named = view.filter((v) => typeof v === "object" && "label" in v).map((v) => (v as { label: string }).label);
    const rest = (currentPageItems() as MenuItem[]).filter((it) => !(typeof it === "object" && "label" in it && named.some((n) => it.label.startsWith(n) || n.startsWith(it.label))));
    const items: MenuItem[] = p?.id ? [...partItems(p.id), "-", ...view] : [...view, "-", ...rest];
    tip = null;
    openMenu(e.clientX, e.clientY, items);
  }

  function setMode(m: Mode) {
    mode = m;
    try {
      localStorage.setItem(MODE_KEY, m);
    } catch {
      /* not kept */
    }
    if (m === "back" && explode > 0) {
      explode = 0;
      place();
    }
    style();
    fit();
  }

  /** Runs after the browser has painted: the tree, numbers and page show first, the 3-D work comes a frame later. */
  function afterPaint(f: () => void) {
    requestAnimationFrame(() => setTimeout(f, 0));
  }

  onMount(() => {
    let stopped = false;
    let cleanup: (() => void) | undefined;
    afterPaint(() => {
      if (!stopped) cleanup = setup();
    });
    return () => {
      stopped = true;
      cleanup?.();
    };
  });

  function setup(): (() => void) | undefined {
    // The renderer is shared between visits to this page (viewRenderer.ts): only the scene is made anew.
    let view: ViewRenderer;
    try {
      view = borrow();
    } catch {
      webgl = false;
      return;
    }
    renderer = view.renderer;
    renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
    canvasHost.appendChild(renderer.domElement);
    scene = new THREE.Scene();
    scene.environment = view.environment;
    scene.environmentIntensity = 0.55;
    scene.add(new THREE.HemisphereLight(0xffffff, 0x8a94a6, 0.9));
    const sun = new THREE.DirectionalLight(0xffffff, 1.5);
    sun.position.set(0.4, 1, 0.8);
    scene.add(sun);
    const back = new THREE.DirectionalLight(0xffffff, 0.45);
    back.position.set(-0.5, -0.3, -1);
    scene.add(back);
    ortho = new THREE.OrthographicCamera(-1, 1, 1, -1, -10, 10);
    persp = new THREE.PerspectiveCamera(35, 1, 0.01, 1000);
    camera = ortho;
    const el = renderer.domElement;
    // The canvas outlives this visit: its listeners are taken off together when the page is left.
    const leaving = new AbortController();
    const on = { signal: leaving.signal };
    el.addEventListener("pointerdown", (e) => {
      if (e.button === 2) {
        rightDown = { x: e.clientX, y: e.clientY };
        return;
      }
      down = { x: e.clientX, y: e.clientY };
      startDrag(e);
    }, on);
    el.addEventListener("contextmenu", (e) => {
      e.preventDefault();
      e.stopPropagation();
    }, on);
    el.addEventListener("pointerup", (e) => {
      if (e.button !== 2 || !rightDown) return;
      const still = Math.hypot(e.clientX - rightDown.x, e.clientY - rightDown.y) < 5;
      rightDown = null;
      if (still) rightClick(e);
    }, on);
    el.addEventListener("pointermove", (e) => (drag ? dragMove(e) : hover(e)), on);
    el.addEventListener("pointerup", (e) => (drag ? endDrag(e) : pick(e)), on);
    el.addEventListener("pointercancel", (e) => drag && endDrag(e), on);
    el.addEventListener("pointerleave", unhover, on);
    const explodeAsked = () => (mode === "back" ? (setMode("side"), explodeTo(1)) : explodeTo(1));
    addEventListener("rocketmaxxing:explode", explodeAsked);
    const ro = new ResizeObserver(resize);
    ro.observe(canvasHost);
    canvasHost.addEventListener("wheel", wheel, { passive: false, capture: true });
    const mq = matchMedia("(prefers-color-scheme: dark)");
    const theme = new MutationObserver(() => style());
    theme.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme", "data-scheme", "data-dark"] });
    mq.addEventListener("change", style);
    if (app.model) {
      lastModel = app.model;
      build(app.model.parts);
      fit();
      lastLength = length;
    }
    return () => {
      ro.disconnect();
      removeEventListener("rocketmaxxing:explode", explodeAsked);
      canvasHost.removeEventListener("wheel", wheel, { capture: true });
      cancelAnimationFrame(zoomFrame);
      cancelAnimationFrame(pulseFrame);
      cancelAnimationFrame(explodeFrame);
      cancelAnimationFrame(hoverFrame);
      theme.disconnect();
      mq.removeEventListener("change", style);
      controls?.dispose();
      leaving.abort();
      // What this visit put on the graphics card goes; part shapes kept for the next build (meshes.ts) stay.
      const keptShapes = new Set<THREE.BufferGeometry>();
      for (const m of meshes) {
        keptShapes.add(m.mesh.geometry).add(m.edges.geometry);
        if (m.owned && !kept(m.part.meshId)) disposeBuilt(m.owned);
      }
      scene.traverse((o) => {
        const drawn = o as Partial<THREE.Mesh>;
        if (drawn.geometry && !keptShapes.has(drawn.geometry)) drawn.geometry.dispose();
        for (const mat of drawn.material ? [drawn.material].flat() : []) {
          (mat as THREE.MeshBasicMaterial).map?.dispose();
          mat.dispose();
        }
      });
      meshes = [];
      release(view);
    };
  }

  // Rebuild when the engine sends a new model; restyle when the selection changes.
  let lastModel: unknown = null;
  let lastLength = 0;
  $effect(() => {
    const m = app.model;
    if (!scene || !m || m === lastModel) return;
    afterPaint(() => {
      if (m !== app.model || m === lastModel) return; // a newer model came meanwhile, or this one is built
      lastModel = m;
      held = null;
      build(m.parts);
      // Keep the camera where the user put it unless the rocket's size changed a lot (a new design, a big edit).
      if (Math.abs(length - lastLength) > 0.25 * Math.max(length, lastLength) || !lastLength) fit();
      else if (explode > 0) fit(false);
      else render();
      lastLength = length;
    });
  });
  $effect(() => {
    void app.selected;
    void app.hover;
    if (scene) style();
  });
  // A new selection pulses briefly, so the eye finds it.
  $effect(() => {
    const f = app.flash;
    if (f && scene) startPulse();
  });

  let canMoveHint = $derived(mode !== "back" && movable());

  // Asked for from the search: a view mode, the cut-away, Fit, the exploded view.
  $effect(() => {
    void ui.request;
    if (!webgl) return;
    const r = take("view", "cutaway", "fit", "explode");
    if (!r) return;
    setTimeout(() => {
      if (!renderer) return;
      if (r.name === "view") setMode(r.detail as Mode);
      else if (r.name === "cutaway") {
        if (mode !== "3d") setMode("3d");
        cutaway = r.detail !== "off";
        explode = 0;
        place();
        style();
      } else if (r.name === "fit") fit();
      else explodeTo(r.detail === "off" ? 0 : 1);
    }, renderer ? 0 : 400);
  });
</script>

<div class="rv" bind:this={host}>
  <div
    class="canvas"
    bind:this={canvasHost}
    style:cursor
    title={mode === "3d" ? "Drag to turn, right-drag to move the view, scroll to zoom" : "Drag to move the view, scroll to zoom"}
  ></div>
  {#if !webgl}
    <div class="nogl">This computer cannot show 3-D graphics (WebGL is off).</div>
  {/if}
  <div class="marker cg" bind:this={cgEl} title="Centre of gravity (at launch)"><span>CG</span></div>
  <div class="marker cp" bind:this={cpEl} title="Centre of pressure (Mach 0.3)"><span>CP</span></div>
  {#if tip && !readout}
    <div class="tip" style="left: {tip.x + 14}px; top: {tip.y + 12}px">{tip.text}</div>
  {/if}
  {#if readout}
    <div class="readout" style="left: {readout.x + 16}px; top: {readout.y - 46}px">
      <b>{readout.text}</b>
      <span>{readout.snap ?? "Hold Alt to place it without lining up"}</span>
    </div>
  {/if}
  <div class="bar" data-tour="view-modes">
    <div class="seg" role="group" aria-label="View">
      <button class:on={mode === "side"} onclick={() => setMode("side")}>Side</button>
      <button class:on={mode === "back"} onclick={() => setMode("back")}>Back</button>
      <button class:on={mode === "3d"} onclick={() => setMode("3d")}>3D</button>
    </div>
    {#if mode !== "back"}
      <div class="explode" class:on={explode > 0}>
        <button class="btn small" class:on={explode > 0} onclick={() => explodeTo(explode > 0 ? 0 : 1)} title="Pull the rocket apart: airframe sections along the axis, fins out, the parts inside laid out below">
          <Expand size={14} /> Exploded
        </button>
        {#if explode > 0}
          <input type="range" min="0" max="1" step="0.01" value={explode} oninput={(e) => setExplode(Number(e.currentTarget.value))} aria-label="How far apart" />
        {/if}
      </div>
    {/if}
    {#if mode === "3d" && explode === 0}
      <button class="btn small" class:on={cutaway} onclick={() => ((cutaway = !cutaway), style())} title="Cut the airframe open to show the inside">
        <Scissors size={14} /> Cut-away
      </button>
    {/if}
    <button class="btn small" onclick={() => fit()} title="Fit the rocket in the view"><Maximize size={14} /> Fit</button>
  </div>
  {#if canMoveHint && !readout}
    <div class="hint">Drag {app.editor?.name} along the rocket to move it; it lines up with the parts near it (Alt: freely).</div>
  {/if}
</div>

<style>
  .rv {
    position: absolute;
    inset: 0;
  }
  .canvas {
    position: absolute;
    inset: 0;
  }
  .canvas :global(canvas) {
    width: 100%;
    height: 100%;
    display: block;
    touch-action: none;
  }
  .bar {
    position: absolute;
    top: 6px;
    right: 6px;
    display: flex;
    gap: 4px;
    align-items: center;
  }
  .seg {
    display: flex;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    overflow: hidden;
    background: var(--surface);
  }
  .seg button {
    border: 0;
    border-left: 1px solid var(--line);
    background: transparent;
    padding: 3px 12px;
    color: var(--ink-2);
  }
  .seg button:first-child {
    border-left: 0;
  }
  .seg button.on {
    background: var(--select-soft);
    color: var(--ink);
    font-weight: 600;
  }
  .btn.small {
    height: 28px;
  }
  .btn.small.on {
    background: var(--select-soft);
    border-color: var(--select);
  }
  .explode {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .explode.on {
    padding-right: 6px;
    border-radius: var(--radius);
    background: var(--surface);
  }
  .explode input {
    width: 90px;
  }
  .marker {
    position: absolute;
    width: 22px;
    height: 22px;
    margin: -11px 0 0 -11px;
    border-radius: 50%;
    pointer-events: none;
    display: none;
  }
  .marker span {
    position: absolute;
    top: -20px;
    left: 50%;
    transform: translateX(-50%);
    font-size: 11px;
    font-weight: 700;
    padding: 0 4px;
    border-radius: 4px;
    background: var(--surface);
  }
  /* OpenRocket's symbols: CG a blue and white quartered disc, CP a red ring with a dot. */
  .cg {
    border: 2px solid #1d4ed8;
    background: conic-gradient(#1d4ed8 0 25%, #fff 0 50%, #1d4ed8 0 75%, #fff 0);
  }
  .cg span {
    color: #1d4ed8;
  }
  .cp {
    border: 3px solid #dc2626;
    background: radial-gradient(circle, #dc2626 0 3px, transparent 4px);
  }
  .cp span {
    color: #dc2626;
  }
  .tip,
  .readout {
    position: absolute;
    pointer-events: none;
    padding: 3px 7px;
    border-radius: 5px;
    background: var(--surface);
    border: 1px solid var(--line-strong);
    box-shadow: var(--shadow-pop);
    font-size: 12px;
    white-space: nowrap;
    z-index: 2;
  }
  .readout {
    display: flex;
    flex-direction: column;
    gap: 1px;
  }
  .readout span {
    color: var(--ink-3);
    font-size: 11.5px;
  }
  .hint {
    position: absolute;
    left: 8px;
    bottom: 6px;
    right: 8px;
    font-size: 11.5px;
    color: var(--ink-3);
    pointer-events: none;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .nogl {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    color: var(--ink-3);
  }
</style>
