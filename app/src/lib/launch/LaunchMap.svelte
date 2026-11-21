<script lang="ts">
  import { onMount } from "svelte";
  import L from "leaflet";
  import "leaflet/dist/leaflet.css";
  import { tileUrl } from "../api";
  import type { Plan } from "./store.svelte";

  // The launch plan on a map: satellite imagery (or the street map), the pad, the landing areas holding 50, 95 and 99%
  // of the flights, every landing, and the flight in the forecast as it stands. Tiles come through the app, which keeps
  // them, so a site looked at once still shows at the field without internet.
  let {
    plan,
    site,
    radius = NaN,
  }: { plan: Plan | null; site: [number, number] | null; radius?: number } = $props();

  let host: HTMLDivElement;
  let map: L.Map | null = null;
  let layer: L.LayerGroup | null = null;

  // Colours that read on satellite imagery and on the street map.
  const RING = { 50: "#34d399", 95: "#f8fafc", 99: "#f87171" } as Record<number, string>;
  const TRACK = "#60a5fa";
  const DOT = "#facc15";

  onMount(() => {
    map = L.map(host, { preferCanvas: true, zoomControl: true, attributionControl: true, worldCopyJump: true });
    const satellite = L.tileLayer(tileUrl("satellite"), {
      maxZoom: 19,
      attribution: "Imagery © Esri, Maxar, Earthstar Geographics, and the GIS User Community",
    });
    const street = L.tileLayer(tileUrl("map"), { maxZoom: 19, attribution: "© OpenStreetMap contributors" });
    satellite.addTo(map);
    L.control.layers({ Satellite: satellite, Map: street }, undefined, { position: "topright" }).addTo(map);
    L.control.scale({ imperial: true, metric: true }).addTo(map);
    const legend = new L.Control({ position: "bottomright" });
    legend.onAdd = () => {
      const d = L.DomUtil.create("div", "legend");
      d.innerHTML = `<div><i style="border-color:${RING[50]}"></i>50% of landings</div>
        <div><i style="border-color:${RING[95]}"></i>95% of landings</div>
        <div><i style="border-color:${RING[99]};border-style:dashed"></i>99% of landings</div>
        <div><i class="dot" style="background:${DOT}"></i>One flight's landing</div>
        <div><i class="line" style="background:${TRACK}"></i>Flight in the forecast as is</div>`;
      return d;
    };
    legend.addTo(map);
    layer = L.layerGroup().addTo(map);
    map.setView(site ?? [45, -80], site ? 13 : 4);
    const ro = new ResizeObserver(() => map?.invalidateSize());
    ro.observe(host);
    return () => {
      ro.disconnect();
      map?.remove();
      map = null;
    };
  });

  $effect(() => {
    const p = plan, s = site, r = radius;
    if (!map || !layer) return;
    layer.clearLayers();
    const bounds: L.LatLngExpression[] = [];
    if (s) {
      L.circleMarker(s, { radius: 6, color: "#0f172a", weight: 2, fillColor: "#f8fafc", fillOpacity: 1 })
        .bindTooltip("Pad", { direction: "top" })
        .addTo(layer);
      bounds.push(s);
      if (Number.isFinite(r) && r > 0) {
        L.circle(s, { radius: r, color: "#fbbf24", weight: 2, dashArray: "6 6", fill: false }).bindTooltip("Recovery area").addTo(layer);
      }
    }
    if (p) {
      for (const a of p.landing) {
        for (const pt of a.points) {
          L.circleMarker(pt, { radius: 2.5, color: "#422006", weight: 0.5, fillColor: DOT, fillOpacity: 0.85 }).addTo(layer);
        }
        for (const ring of [...a.areas].reverse()) {
          L.polygon(ring.polygon, {
            color: RING[ring.percent] ?? "#fff",
            weight: ring.percent === 95 ? 3 : 2,
            dashArray: ring.percent === 99 ? "8 6" : undefined,
            fill: false,
          })
            .bindTooltip(`${ring.percent}% of ${a.branch} landings: ${ring.size}`, { sticky: true })
            .addTo(layer);
          ring.polygon.forEach((q) => bounds.push(q));
        }
      }
      for (const t of p.forecastAsIs?.tracks ?? []) {
        const ground = t.path.map((q) => [q[0], q[1]] as [number, number]);
        L.polyline(ground, { color: TRACK, weight: 2.5, opacity: 0.95 }).addTo(layer);
        ground.forEach((q) => bounds.push(q));
        L.circleMarker([t.apogee[0], t.apogee[1]], { radius: 5, color: "#0f172a", weight: 1.5, fillColor: TRACK, fillOpacity: 1 })
          .bindTooltip(`${t.branch}: apogee ${Math.round(t.apogee[2])} m above the pad`, { direction: "top" })
          .addTo(layer);
        if (t.landing) {
          L.circleMarker(t.landing, { radius: 5, color: "#0f172a", weight: 1.5, fillColor: "#f8fafc", fillOpacity: 1 })
            .bindTooltip(`${t.branch} lands here in the forecast as is (${t.landingFromPad ?? ""})`, { direction: "top" })
            .addTo(layer);
        }
      }
    }
    if (bounds.length > 1) map.fitBounds(L.latLngBounds(bounds), { padding: [30, 30], maxZoom: 16 });
    else if (s) map.setView(s, Math.max(map.getZoom(), 12));
  });
</script>

<div class="map" bind:this={host} aria-label="Map of the launch site and where the rocket lands"></div>

<style>
  .map {
    /* Leaflet stacks its panes and controls at z-index 400 to 1000: kept inside the map, under the app's dialogs. */
    isolation: isolate;
    flex: 1;
    min-height: 320px;
    border-radius: var(--radius);
    overflow: hidden;
    background: #1e293b;
  }
  .map :global(.legend) {
    padding: 6px 8px;
    border-radius: 6px;
    background: rgb(255 255 255 / 92%);
    color: #0f172a;
    font-size: 12px;
    line-height: 1.6;
  }
  .map :global(.legend i) {
    display: inline-block;
    width: 14px;
    height: 10px;
    margin-right: 6px;
    border: 2px solid;
    border-radius: 50%;
    vertical-align: middle;
    background: #475569;
  }
  .map :global(.legend i.dot) {
    width: 8px;
    height: 8px;
    margin: 0 9px 0 3px;
    border: 1px solid #422006;
  }
  .map :global(.legend i.line) {
    height: 3px;
    border: none;
    border-radius: 2px;
  }
</style>
