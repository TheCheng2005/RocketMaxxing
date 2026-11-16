// Decals on the 3-D view, as OpenRocket draws them: a picture wrapped round a body part (u round it, v along it) or
// laid on a fin (u along the chord, v up the span), placed by offset, size and turn, and repeated, mirrored, clamped
// or stuck on once over the paint. The meshes come without texture coordinates, so they are made here.

import * as THREE from "three";
import { fileUrl } from "../api";

export interface Decal {
  image?: string;
  missing?: string;
  name?: string;
  offsetU: number;
  offsetV: number;
  centerU: number;
  centerV: number;
  scaleU: number;
  scaleV: number;
  /** Degrees. */
  rotation: number;
  edge: "REPEAT" | "MIRROR" | "CLAMP" | "STICKER";
}

/**
 * Texture coordinates for a piece. Bodies: u is the angle round the axis (x), v the place along the part. Fins
 * (flat plates): u along the chord, v out from the body. The seam where u wraps is mended triangle by triangle.
 */
export function addUv(geo: THREE.BufferGeometry, fin: boolean): void {
  if (geo.getAttribute("uv")) return;
  const pos = geo.getAttribute("position") as THREE.BufferAttribute;
  const n = pos.count;
  const uv = new Float32Array(n * 2);
  geo.computeBoundingBox();
  const bb = geo.boundingBox!;
  const x0 = bb.min.x, len = Math.max(bb.max.x - x0, 1e-6);
  if (fin) {
    let r0 = Infinity, r1 = -Infinity;
    for (let i = 0; i < n; i++) {
      const r = Math.hypot(pos.getY(i), pos.getZ(i));
      r0 = Math.min(r0, r);
      r1 = Math.max(r1, r);
    }
    const span = Math.max(r1 - r0, 1e-6);
    for (let i = 0; i < n; i++) {
      uv[2 * i] = (pos.getX(i) - x0) / len;
      uv[2 * i + 1] = (Math.hypot(pos.getY(i), pos.getZ(i)) - r0) / span;
    }
  } else {
    for (let i = 0; i < n; i++) {
      // The angle from the top (+y), going round towards +z, as a fraction of a turn.
      uv[2 * i] = (Math.atan2(pos.getZ(i), pos.getY(i)) / (2 * Math.PI) + 1) % 1;
      uv[2 * i + 1] = 1 - (pos.getX(i) - x0) / len; // v up from the aft end, as in OpenRocket
    }
    // A triangle across the seam has u near 1 and near 0: bring the small ones round (the texture repeats in u).
    for (let t = 0; t + 2 < n; t += 3) {
      const us = [uv[2 * t], uv[2 * t + 2], uv[2 * t + 4]];
      if (Math.max(...us) - Math.min(...us) > 0.5) for (let k = 0; k < 3; k++) if (us[k] < 0.5) uv[2 * (t + k)] += 1;
    }
  }
  geo.setAttribute("uv", new THREE.BufferAttribute(uv, 2));
}

const images = new Map<string, Promise<HTMLImageElement>>();
function image(path: string): Promise<HTMLImageElement> {
  if (!images.has(path)) {
    images.set(
      path,
      new Promise((ok, fail) => {
        const im = new Image();
        im.onload = () => ok(im);
        im.onerror = fail;
        im.src = fileUrl(path);
      }),
    );
  }
  return images.get(path)!;
}

const textures = new Map<string, Promise<THREE.Texture | null>>();

/**
 * The part's surface as one texture: its paint, with the picture drawn on it where the decal puts it. Kept by what
 * it shows, so an edit elsewhere reuses it.
 */
export function decalTexture(d: Decal, paint: string): Promise<THREE.Texture | null> {
  if (!d.image) return Promise.resolve(null);
  const key = JSON.stringify([d, paint]);
  if (!textures.has(key)) {
    textures.set(
      key,
      image(d.image)
        .then((im) => compose(im, d, paint))
        .catch(() => null),
    );
    if (textures.size > 60) textures.delete(textures.keys().next().value!);
  }
  return textures.get(key)!;
}

function compose(im: HTMLImageElement, d: Decal, paint: string): THREE.Texture {
  const W = 1024, H = 1024;
  const c = document.createElement("canvas");
  c.width = W;
  c.height = H;
  const g = c.getContext("2d")!;
  g.fillStyle = paint;
  g.fillRect(0, 0, W, H);
  // OpenRocket's texture matrix: tc = T(-centre) · R(turn) · T(centre) · S(scale) · T(offset) · uv, the picture filling
  // tc 0 to 1 (so scale is how many pictures fit across the part, and the picture starts at uv = -offset). The canvas
  // is in uv, so the picture is drawn through the inverse, the canvas's y (down) flipped to v (up) first.
  g.translate(0, H);
  g.scale(W, -H);
  g.translate(-d.offsetU, -d.offsetV);
  g.scale(1 / (d.scaleU || 1), 1 / (d.scaleV || 1));
  g.translate(-d.centerU, -d.centerV);
  g.rotate((-d.rotation * Math.PI) / 180);
  g.translate(d.centerU, d.centerV);
  // From the picture's pixels to tc: its top row at tc v = 1.
  g.translate(0, 1);
  g.scale(1 / im.width, -1 / im.height);
  if (d.edge === "REPEAT" || d.edge === "MIRROR") {
    // Enough copies to cover the part: the scale says how many fit across, the offset and turn shift them.
    const n = Math.min(60, Math.ceil(Math.max(Math.abs(d.scaleU), Math.abs(d.scaleV), 1) * 1.5 + Math.abs(d.offsetU) + Math.abs(d.offsetV)) + 2);
    for (let i = -n; i <= n; i++) {
      for (let j = -n; j <= n; j++) {
        g.save();
        g.translate(i * im.width, j * im.height);
        if (d.edge === "MIRROR") {
          if (i % 2) g.transform(-1, 0, 0, 1, im.width, 0);
          if (j % 2) g.transform(1, 0, 0, -1, 0, im.height);
        }
        g.drawImage(im, 0, 0);
        g.restore();
      }
    }
  } else if (d.edge === "CLAMP") {
    // The picture's edge pixels stretched out every way (corners too), then the picture.
    const big = 200 * Math.max(im.width, im.height);
    g.drawImage(im, 0, 0, 1, 1, -big, -big, big, big);
    g.drawImage(im, im.width - 1, 0, 1, 1, im.width, -big, big, big);
    g.drawImage(im, 0, im.height - 1, 1, 1, -big, im.height, big, big);
    g.drawImage(im, im.width - 1, im.height - 1, 1, 1, im.width, im.height, big, big);
    g.drawImage(im, 0, 0, 1, im.height, -big, 0, big, im.height);
    g.drawImage(im, im.width - 1, 0, 1, im.height, im.width, 0, big, im.height);
    g.drawImage(im, 0, 0, im.width, 1, 0, -big, im.width, big);
    g.drawImage(im, 0, im.height - 1, im.width, 1, 0, im.height, im.width, big);
    g.drawImage(im, 0, 0);
  } else {
    g.drawImage(im, 0, 0); // a sticker: once, the paint round it
  }
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  t.wrapS = THREE.RepeatWrapping; // round a body, u carries on past 1 at the seam
  t.wrapT = THREE.ClampToEdgeWrapping;
  t.anisotropy = 4;
  return t;
}
