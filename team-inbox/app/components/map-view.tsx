"use client";

import { CornersOut, Desktop, MagnifyingGlassMinus, MagnifyingGlassPlus, Moon, Sun } from "@phosphor-icons/react";
import { memo, useCallback, useEffect, useRef, useState } from "react";
import { type FloorImage, imageBounds } from "@/lib/map-geo";
import type { MapState, Place, PlaceKind } from "@/lib/types";
import { normalizeName } from "@/lib/types";

// the live map: robot spots as labelled dots, otto as an arrow with its trail, lidar points,
// the patient's estimated position and the target. drawn in svg from the latest map frame

export type MapTheme = "auto" | "light" | "dark";
export type SpotInfo = { name: string; x: number; y: number; place?: Place };
// the real floor plan from otto (robot-setup.md section 1): the png and where it sits in map meters
export type FloorPlan = FloorImage & { href: string };
type View = { cx: number; cy: number; s: number };
type Size = { w: number; h: number };

const MIN_SCALE = 3;
const MAX_SCALE = 400;
const THEME_KEY = "nova-map-theme";

// robot spots matched to dashboard places by spot name, then by place name
export function spotsOf(map: MapState, places: Place[]): SpotInfo[] {
  const bySpot = new Map(places.map((p) => [normalizeName(p.spot), p]));
  const byName = new Map(places.map((p) => [normalizeName(p.name), p]));
  return map.places.map((s) => ({ ...s, place: bySpot.get(normalizeName(s.name)) ?? byName.get(normalizeName(s.name)) }));
}

export function spotLabel(s: SpotInfo) {
  return s.place?.name ?? s.name;
}

export const distance = (a: { x: number; y: number }, b: { x: number; y: number }) => Math.hypot(a.x - b.x, a.y - b.y);

export function meters(m: number) {
  return m < 10 ? `${m.toFixed(1)} m` : `${Math.round(m)} m`;
}

const KIND_COLOR: Record<PlaceKind, string> = {
  room: "var(--m-room)",
  other: "var(--m-room)",
  waiting: "var(--m-waiting)",
  reception: "var(--m-reception)",
  exit: "var(--m-exit)",
};

// what the view has to show. the small panel follows otto during a walk (otto, target, patient,
// the last metres of the trail), otherwise and on the full map: the whole floor
function focusPoints(map: MapState, compact: boolean, plan?: FloorPlan | null) {
  const pts: { x: number; y: number }[] = [map.pose];
  if (map.target) pts.push(map.target);
  if (map.follower) pts.push(map.follower);
  if (compact && map.target) return [...pts, ...map.trail.slice(-12)];
  pts.push(...map.places, ...map.trail);
  // the whole floor plan when there is one
  if (plan) {
    const b = imageBounds(plan);
    pts.push({ x: b.minX, y: b.minY }, { x: b.maxX, y: b.maxY });
    return pts;
  }
  // walls close to otto give the picture context, far reflections would only zoom out
  for (const [x, y] of map.scan) if (Math.hypot(x - map.pose.x, y - map.pose.y) <= 6) pts.push({ x, y });
  return pts;
}

function boundsOf(pts: { x: number; y: number }[]) {
  let minX = Infinity;
  let maxX = -Infinity;
  let minY = Infinity;
  let maxY = -Infinity;
  for (const p of pts) {
    minX = Math.min(minX, p.x);
    maxX = Math.max(maxX, p.x);
    minY = Math.min(minY, p.y);
    maxY = Math.max(maxY, p.y);
  }
  return { minX, maxX, minY, maxY };
}

function fit(map: MapState, size: Size, compact: boolean, plan?: FloorPlan | null): View {
  const b = boundsOf(focusPoints(map, compact, plan));
  const min = compact && map.target ? 9 : 4;
  const bw = Math.max(min, b.maxX - b.minX);
  const bh = Math.max(min * 0.6, b.maxY - b.minY);
  const padX = Math.min(compact ? 56 : 96, size.w * 0.12);
  const padY = Math.min(compact ? 32 : 48, size.h * 0.1);
  const s = Math.min((size.w - 2 * padX) / bw, (size.h - 2 * padY) / bh);
  return {
    cx: (b.minX + b.maxX) / 2,
    cy: (b.minY + b.maxY) / 2,
    s: Math.min(MAX_SCALE, Math.max(MIN_SCALE, s)),
  };
}

// the camera only moves when something it should show gets close to the edge
function stillFits(view: View, fitted: View, map: MapState, size: Size, compact: boolean, plan?: FloorPlan | null) {
  if (fitted.s > view.s * 1.8 || fitted.s < view.s / 1.25) return false;
  const inside = (p: { x: number; y: number }) => {
    const x = (p.x - view.cx) * view.s + size.w / 2;
    const y = size.h / 2 - (p.y - view.cy) * view.s;
    return x >= size.w * 0.12 && x <= size.w * 0.88 && y >= size.h * 0.12 && y <= size.h * 0.88;
  };
  const pts = compact ? focusPoints(map, compact, plan) : [map.pose, ...map.places];
  return pts.every(inside);
}

// the label over the arrow: "Otto" or "Virtual Otto"
function robotLabelOf(robot: string) {
  return robot === "otto-sim" ? "Virtual Otto" : "Otto";
}

// grid step in meters so lines are at least 36 px apart
function gridStep(s: number) {
  for (const step of [0.5, 1, 2, 5, 10, 20, 50, 100]) if (step * s >= 36) return step;
  return 200;
}

export function useMapTheme() {
  const [theme, setTheme] = useState<MapTheme>("auto");
  useEffect(() => {
    try {
      const saved = window.localStorage.getItem(THEME_KEY);
      if (saved === "light" || saved === "dark" || saved === "auto") setTheme(saved);
    } catch {
      /* private mode: stays auto */
    }
  }, []);
  const change = useCallback((t: MapTheme) => {
    setTheme(t);
    try {
      window.localStorage.setItem(THEME_KEY, t);
    } catch {
      /* not saved, still switched */
    }
  }, []);
  return [theme, change] as const;
}

const NEXT_THEME: Record<MapTheme, MapTheme> = { auto: "light", light: "dark", dark: "auto" };
const THEME_LABEL: Record<MapTheme, string> = { auto: "Map colors: like the system", light: "Map colors: light", dark: "Map colors: dark" };

function ToolButton({ label, onClick, children }: { label: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      title={label}
      className="press flex size-11 items-center justify-center rounded-xl text-muted hover:bg-subtle hover:text-ink"
    >
      {children}
    </button>
  );
}

export function MapView({
  map,
  spots,
  picked,
  onPick,
  compact = false,
  stale = false,
  className = "",
  label,
  toolbarStart,
  plan = null,
  robotVisible = true,
}: {
  map: MapState;
  plan?: FloorPlan | null;
  // false: a floor otto is not on (no arrow, trail, lidar or patient)
  robotVisible?: boolean;
  spots: SpotInfo[];
  picked: string | null;
  onPick: (name: string | null) => void;
  compact?: boolean;
  stale?: boolean;
  className?: string;
  label: string;
  toolbarStart?: React.ReactNode;
}) {
  const box = useRef<HTMLDivElement>(null);
  const svg = useRef<SVGSVGElement>(null);
  const [size, setSize] = useState<Size | null>(null);
  const [view, setView] = useState<View | null>(null);
  const [auto, setAuto] = useState(true);
  const [dragging, setDragging] = useState(false);
  const [theme, setTheme] = useMapTheme();
  const pointers = useRef(new Map<number, { x: number; y: number }>());
  const moved = useRef(0);
  const lastDeg = useRef<number | null>(null);

  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(([entry]) => {
      const { width, height } = entry.contentRect;
      if (width > 0 && height > 0) setSize({ w: Math.round(width), h: Math.round(height) });
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // follows everything until someone pans or zooms, "Fit" goes back to that
  const [autoView, setAutoView] = useState<View | null>(null);
  const autoSize = useRef<Size | null>(null);
  useEffect(() => {
    if (!size || !auto) return;
    const fitted = fit(map, size, compact, plan);
    setAutoView((prev) => {
      const sameSize = autoSize.current?.w === size.w && autoSize.current?.h === size.h;
      autoSize.current = size;
      return prev && sameSize && stillFits(prev, fitted, map, size, compact, plan) ? prev : fitted;
    });
  }, [map, size, compact, auto, plan]);
  const v = auto || !view ? (autoView ?? (size ? fit(map, size, compact, plan) : null)) : view;

  const zoomAt = useCallback(
    (factor: number, px?: number, py?: number) => {
      if (!size || !v) return;
      const s = Math.min(MAX_SCALE, Math.max(MIN_SCALE, v.s * factor));
      const x = px ?? size.w / 2;
      const y = py ?? size.h / 2;
      const wx = v.cx + (x - size.w / 2) / v.s;
      const wy = v.cy - (y - size.h / 2) / v.s;
      setView({ s, cx: wx - (x - size.w / 2) / s, cy: wy + (y - size.h / 2) / s });
      setAuto(false);
    },
    [size, v],
  );

  // wheel zoom on the full map; on the small panel only with ctrl, so the page still scrolls
  useEffect(() => {
    const el = svg.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      if (compact && !e.ctrlKey) return;
      e.preventDefault();
      const r = el.getBoundingClientRect();
      zoomAt(Math.exp(-e.deltaY * 0.0015), e.clientX - r.left, e.clientY - r.top);
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  }, [zoomAt, compact]);

  const local = (e: React.PointerEvent) => {
    const r = svg.current!.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  };

  const onPointerDown = (e: React.PointerEvent<SVGSVGElement>) => {
    if ((e.target as Element).closest(".map-spot")) return;
    if (compact && e.pointerType === "touch") return;
    svg.current?.setPointerCapture(e.pointerId);
    pointers.current.set(e.pointerId, local(e));
    moved.current = 0;
    setDragging(true);
  };

  const onPointerMove = (e: React.PointerEvent<SVGSVGElement>) => {
    const prev = pointers.current.get(e.pointerId);
    if (!prev || !v) return;
    const p = local(e);
    if (pointers.current.size === 1) {
      const dx = p.x - prev.x;
      const dy = p.y - prev.y;
      moved.current += Math.abs(dx) + Math.abs(dy);
      if (moved.current > 3) {
        setView({ ...v, cx: v.cx - dx / v.s, cy: v.cy + dy / v.s });
        setAuto(false);
      }
    } else if (pointers.current.size === 2) {
      const other = [...pointers.current.entries()].find(([id]) => id !== e.pointerId)?.[1];
      if (other) {
        const before = Math.hypot(prev.x - other.x, prev.y - other.y);
        const after = Math.hypot(p.x - other.x, p.y - other.y);
        moved.current += 10;
        if (before > 0) zoomAt(after / before, (p.x + other.x) / 2, (p.y + other.y) / 2);
      }
    }
    pointers.current.set(e.pointerId, p);
  };

  const onPointerUp = (e: React.PointerEvent<SVGSVGElement>) => {
    if (!pointers.current.has(e.pointerId)) return;
    pointers.current.delete(e.pointerId);
    if (pointers.current.size === 0) {
      setDragging(false);
      if (moved.current <= 3) onPick(null);
    }
  };

  const themeIcon = theme === "light" ? <Sun size={20} aria-hidden /> : theme === "dark" ? <Moon size={20} aria-hidden /> : <Desktop size={20} aria-hidden />;

  return (
    <div className={`map-surface map-${theme} overflow-hidden rounded-xl border border-line`}>
      <div className="flex flex-wrap items-center justify-end gap-1 border-b border-line bg-surface px-1.5 py-1">
        {toolbarStart && <div className="mr-auto min-w-0 px-2.5 py-1.5">{toolbarStart}</div>}
        <ToolButton label="Zoom out" onClick={() => zoomAt(1 / 1.5)}>
          <MagnifyingGlassMinus size={20} aria-hidden />
        </ToolButton>
        <ToolButton label="Zoom in" onClick={() => zoomAt(1.5)}>
          <MagnifyingGlassPlus size={20} aria-hidden />
        </ToolButton>
        <ToolButton
          label="Fit everything in view"
          onClick={() => {
            setAuto(true);
            setView(null);
            setAutoView(null);
          }}
        >
          <CornersOut size={20} aria-hidden />
        </ToolButton>
        <span className="mx-1 h-6 w-px bg-line" aria-hidden />
        <ToolButton label={`${THEME_LABEL[theme]}. Switch`} onClick={() => setTheme(NEXT_THEME[theme])}>
          {themeIcon}
        </ToolButton>
      </div>
      <div ref={box} className={`relative ${className}`}>
        {size && v && (
          <svg
            ref={svg}
            width={size.w}
            height={size.h}
            viewBox={`0 0 ${size.w} ${size.h}`}
            role="img"
            aria-label={label}
            className={`absolute inset-0 block select-none ${compact ? "touch-auto" : "touch-none"} ${dragging ? "cursor-grabbing" : "cursor-grab"}`}
            onPointerDown={onPointerDown}
            onPointerMove={onPointerMove}
            onPointerUp={onPointerUp}
            onPointerCancel={onPointerUp}
          >
            <Drawing map={map} plan={plan} spots={spots} view={v} size={size} picked={picked} onPick={onPick} stale={stale} compact={compact} glide={!dragging} lastDeg={lastDeg} robotVisible={robotVisible} />
          </svg>
        )}
      </div>
    </div>
  );
}

// memoized: a parent that re-renders for other reasons does not redraw 240 lidar points
const Drawing = memo(function Drawing({
  map,
  plan,
  spots,
  view,
  size,
  picked,
  onPick,
  stale,
  compact,
  glide,
  lastDeg,
  robotVisible,
}: {
  map: MapState;
  plan: FloorPlan | null;
  robotVisible: boolean;
  spots: SpotInfo[];
  view: View;
  size: Size;
  picked: string | null;
  onPick: (name: string | null) => void;
  stale: boolean;
  compact: boolean;
  glide: boolean;
  lastDeg: React.RefObject<number | null>;
}) {
  const X = (x: number) => (x - view.cx) * view.s + size.w / 2;
  const Y = (y: number) => size.h / 2 - (y - view.cy) * view.s;
  const step = gridStep(view.s);

  // grid lines across what is visible
  const left = view.cx - size.w / 2 / view.s;
  const right = view.cx + size.w / 2 / view.s;
  const bottom = view.cy - size.h / 2 / view.s;
  const top = view.cy + size.h / 2 / view.s;
  const xs: number[] = [];
  const ys: number[] = [];
  for (let x = Math.ceil(left / step) * step; x <= right; x += step) xs.push(x);
  for (let y = Math.ceil(bottom / step) * step; y <= top; y += step) ys.push(y);

  const otto = { x: X(map.pose.x), y: Y(map.pose.y) };
  // the shortest turn from the last angle, so a heading of 179 -> -179 does not spin around
  let deg = (-map.pose.theta * 180) / Math.PI;
  if (lastDeg.current !== null) deg = lastDeg.current + ((((deg - lastDeg.current) % 360) + 540) % 360) - 180;
  lastDeg.current = deg;

  const trail = robotVisible ? map.trail : [];
  const font = compact ? 12 : 13;
  const target = robotVisible ? map.target : undefined;
  const targetSpot = target ? spots.find((s) => s.name === target.name) : undefined;

  // labels that would cover another label are left out (the dot stays and can still be picked).
  // the picked one and the target go first, then places set up in the dashboard, then plain spots
  const boxes: { x1: number; y1: number; x2: number; y2: number }[] = [
    { x1: otto.x - 18, y1: otto.y - 30, x2: otto.x + 18, y2: otto.y + 18 },
    // the scale bar in the bottom left corner
    { x1: 0, y1: size.h - 28, x2: 12 + step * view.s + 44, y2: size.h },
    ...spots.map((s) => ({ x1: X(s.x) - 10, y1: Y(s.y) - 10, x2: X(s.x) + 10, y2: Y(s.y) + 10 })),
    ...(map.follower ? [{ x1: X(map.follower.x) - 10, y1: Y(map.follower.y) - 10, x2: X(map.follower.x) + 10, y2: Y(map.follower.y) + 10 }] : []),
  ];
  const labels = new Map<string, { x: number; anchor: "start" | "end" }>();
  const rank = (s: SpotInfo) => (s.name === picked ? 0 : s.name === target?.name ? 1 : s.place ? 2 : 3);
  for (const s of [...spots].sort((a, b) => rank(a) - rank(b))) {
    const x = X(s.x);
    const y = Y(s.y);
    if (x < -40 || x > size.w + 40 || y < -20 || y > size.h + 20) continue;
    const w = spotLabel(s).length * font * 0.58 + 4;
    const sides = x < size.w - w - 20 ? (["start", "end"] as const) : (["end", "start"] as const);
    for (const anchor of sides) {
      const x1 = anchor === "start" ? x + 13 : x - 13 - w;
      const box = { x1, y1: y - font + 1, x2: x1 + w, y2: y + 5 };
      const clash = boxes.some((b) => box.x1 < b.x2 && box.x2 > b.x1 && box.y1 < b.y2 && box.y2 > b.y1);
      const outside = box.x1 < 2 || box.x2 > size.w - 2 || box.y1 < 2 || box.y2 > size.h - 2;
      if ((!clash && !outside) || s.name === picked) {
        boxes.push(box);
        labels.set(s.name, { x: anchor === "start" ? x + 14 : x - 14, anchor });
        break;
      }
    }
  }

  // the floor plan: its lower-left corner sits at origin, one pixel is resolution meters (y flipped)
  const floor = plan
    ? { x: X(plan.originX), y: Y(plan.originY + plan.height * plan.resolution), w: plan.width * plan.resolution * view.s, h: plan.height * plan.resolution * view.s }
    : null;

  return (
    <g>
      <rect width={size.w} height={size.h} fill="var(--m-bg)" />
      {plan && floor && (
        <image
          href={plan.href}
          x={floor.x}
          y={floor.y}
          width={floor.w}
          height={floor.h}
          preserveAspectRatio="none"
          className="map-floor"
          style={{ imageRendering: plan.resolution * view.s > 3 ? "pixelated" : "auto" }}
          aria-hidden
        />
      )}
      <g aria-hidden opacity={plan ? 0.35 : 1}>
        {xs.map((x) => (
          <line key={`x${x}`} x1={X(x)} x2={X(x)} y1={0} y2={size.h} stroke={Math.abs(x) < 1e-6 ? "var(--m-grid-strong)" : "var(--m-grid)"} strokeWidth={1} />
        ))}
        {ys.map((y) => (
          <line key={`y${y}`} y1={Y(y)} y2={Y(y)} x1={0} x2={size.w} stroke={Math.abs(y) < 1e-6 ? "var(--m-grid-strong)" : "var(--m-grid)"} strokeWidth={1} />
        ))}
      </g>

      <g aria-hidden>
        {(robotVisible ? map.scan : []).map(([x, y], i) => (
          <circle key={i} cx={X(x)} cy={Y(y)} r={compact ? 1.5 : 1.8} fill="var(--m-scan)" opacity={0.7} />
        ))}
      </g>

      <g aria-hidden fill="none" stroke="var(--m-trail)" strokeLinecap="round" strokeWidth={compact ? 2.5 : 3}>
        {trail.slice(1).map((p, i) => (
          <line
            key={`${p.at}-${i}`}
            x1={X(trail[i].x)}
            y1={Y(trail[i].y)}
            x2={X(p.x)}
            y2={Y(p.y)}
            opacity={0.12 + (0.6 * (i + 1)) / trail.length}
          />
        ))}
        {trail.length > 0 && (
          <line x1={X(trail[trail.length - 1].x)} y1={Y(trail[trail.length - 1].y)} x2={otto.x} y2={otto.y} opacity={0.72} />
        )}
      </g>

      {target && (
        <g aria-hidden>
          <line x1={otto.x} y1={otto.y} x2={X(target.x)} y2={Y(target.y)} stroke="var(--m-target)" strokeWidth={1.5} strokeDasharray="5 6" opacity={0.6} />
          <circle cx={X(target.x)} cy={Y(target.y)} r={18} fill="none" stroke="var(--m-target)" strokeWidth={2} className="map-target-pulse" />
          <circle cx={X(target.x)} cy={Y(target.y)} r={12} fill="none" stroke="var(--m-target)" strokeWidth={2.5} />
          {!targetSpot && (
            <text x={X(target.x) + 18} y={Y(target.y) + 4} fontSize={font} fontWeight={600} fill="var(--m-target)" className="map-label">
              {target.name}
            </text>
          )}
        </g>
      )}

      {spots.map((s) => {
        const x = X(s.x);
        const y = Y(s.y);
        const on = picked === s.name;
        const color = s.place ? KIND_COLOR[s.place.kind] : "var(--m-spot)";
        const name = spotLabel(s);
        const lab = labels.get(s.name);
        return (
          <g
            key={s.name}
            className="map-spot cursor-pointer"
            role="button"
            tabIndex={0}
            aria-pressed={on}
            aria-label={`${name}${s.place?.room ? `, Room ${s.place.room}` : ""}. Select to send Otto here`}
            onClick={(e) => {
              e.stopPropagation();
              onPick(on ? null : s.name);
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                onPick(on ? null : s.name);
              }
            }}
          >
            <circle cx={x} cy={y} r={20} fill="transparent" />
            <circle className="map-spot-ring" cx={x} cy={y} r={on ? 11 : 8} fill="var(--m-bg)" stroke={on ? "var(--m-select)" : color} strokeWidth={on ? 3 : 2} />
            <circle cx={x} cy={y} r={on ? 5.5 : s.place ? 4.5 : 3} fill={color} />
            {lab && (
              <text
                x={lab.x}
                y={y + 4}
                textAnchor={lab.anchor}
                fontSize={font}
                fontWeight={on || s.place ? 600 : 500}
                fill={s.place ? "var(--m-label)" : "var(--m-label-muted)"}
                className="map-label"
              >
                {name}
              </text>
            )}
          </g>
        );
      })}

      {robotVisible && map.follower && (
        <g aria-hidden>
          <line x1={otto.x} y1={otto.y} x2={X(map.follower.x)} y2={Y(map.follower.y)} stroke="var(--m-patient)" strokeWidth={1.5} strokeDasharray="2 4" />
          <circle cx={X(map.follower.x)} cy={Y(map.follower.y)} r={9} fill="var(--m-patient)" opacity={0.18} />
          <circle cx={X(map.follower.x)} cy={Y(map.follower.y)} r={5} fill="var(--m-patient)" stroke="var(--m-bg)" strokeWidth={2} />
        </g>
      )}

      {robotVisible && (
      <>
      <g
        aria-hidden
        className={glide ? "map-otto" : undefined}
        style={{ transform: `translate(${otto.x}px, ${otto.y}px) rotate(${deg}deg)` }}
        opacity={stale ? 0.45 : 1}
      >
        <path d="M0 0 L40 -23 A46 46 0 0 1 40 23 Z" fill="var(--m-otto)" opacity={0.12} />
        <circle r={14} fill="var(--m-otto)" stroke="var(--m-bg)" strokeWidth={3} />
        <path d="M8 0 L-5 -6.5 L-2 0 L-5 6.5 Z" fill="var(--m-otto-ink)" />
      </g>
      <text
        aria-hidden
        x={otto.x}
        y={otto.y - 22}
        textAnchor="middle"
        fontSize={font}
        fontWeight={700}
        fill="var(--m-otto)"
        className="map-label"
        opacity={stale ? 0.6 : 1}
      >
        {robotLabelOf(map.robot)}
      </text>
      </>
      )}

      <ScaleBar step={step} s={view.s} h={size.h} compact={compact} />
    </g>
  );
});

function ScaleBar({ step, s, h, compact }: { step: number; s: number; h: number; compact: boolean }) {
  const len = step * s;
  const x = 12;
  const y = h - 14;
  return (
    <g aria-hidden>
      <line x1={x} x2={x + len} y1={y} y2={y} stroke="var(--m-label-muted)" strokeWidth={2} />
      <line x1={x} x2={x} y1={y - 4} y2={y + 1} stroke="var(--m-label-muted)" strokeWidth={2} />
      <line x1={x + len} x2={x + len} y1={y - 4} y2={y + 1} stroke="var(--m-label-muted)" strokeWidth={2} />
      <text x={x + len + 6} y={y + 4} fontSize={compact ? 11 : 12} fill="var(--m-label-muted)" className="map-label">
        {step} m
      </text>
    </g>
  );
}

// the colors under the map, in words
export function MapLegend({ follower, target }: { follower: boolean; target: boolean }) {
  const item = "inline-flex items-center gap-2";
  return (
    <ul className="flex flex-wrap gap-x-4 gap-y-1.5 text-sm text-muted">
      <li className={item}>
        <span className="size-3 rounded-full bg-st-walking" aria-hidden /> Otto and its trail
      </li>
      <li className={item}>
        <span className="flex gap-0.5" aria-hidden>
          <span className="size-1 rounded-full bg-muted" />
          <span className="size-1 rounded-full bg-muted" />
          <span className="size-1 rounded-full bg-muted" />
        </span>
        LiDAR points
      </li>
      {follower && (
        <li className={item}>
          <span className="size-3 rounded-full bg-warn" aria-hidden /> Patient, estimated
        </li>
      )}
      {target && (
        <li className={item}>
          <span className="size-3 rounded-full border-2 border-primary" aria-hidden /> Target
        </li>
      )}
    </ul>
  );
}
