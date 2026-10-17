// virtual otto's way on the real floor plan: the png from the robot (robot-setup.md section 1) becomes a coarse
// grid of free cells, and a* finds a way along the corridors instead of straight through walls.
// light values are free (ros maps: 254 free, 205 unknown, 0 wall). only node:zlib, tests load this file directly
import { inflateSync } from "node:zlib";

export type FloorMeta = { width: number; height: number; resolution: number; originX: number; originY: number };
export type Grid = { cols: number; rows: number; cell: number; originX: number; originY: number; free: Uint8Array };
type Pt = { x: number; y: number };

const FREE = 230;
const WALL = 100;

// 8 bit gray / gray+alpha / rgb / rgba pngs without interlacing. null for anything else
export function decodePng(png: Uint8Array): { width: number; height: number; gray: Uint8Array } | null {
  const sig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (png.length < 33 || sig.some((b, i) => png[i] !== b)) return null;
  const view = new DataView(png.buffer, png.byteOffset, png.byteLength);
  let pos = 8;
  let width = 0;
  let height = 0;
  let depth = 0;
  let color = 0;
  let interlace = 0;
  const idat: Uint8Array[] = [];
  while (pos + 8 <= png.length) {
    const len = view.getUint32(pos);
    const type = String.fromCharCode(png[pos + 4], png[pos + 5], png[pos + 6], png[pos + 7]);
    const data = png.subarray(pos + 8, pos + 8 + len);
    if (type === "IHDR") {
      width = view.getUint32(pos + 8);
      height = view.getUint32(pos + 12);
      depth = png[pos + 16];
      color = png[pos + 17];
      interlace = png[pos + 20];
    } else if (type === "IDAT") idat.push(data);
    else if (type === "IEND") break;
    pos += 12 + len;
  }
  const channels = ({ 0: 1, 2: 3, 4: 2, 6: 4 } as Record<number, number>)[color];
  if (!width || !height || depth !== 8 || !channels || interlace !== 0) return null;
  let raw: Uint8Array;
  try {
    raw = inflateSync(Buffer.concat(idat.map((d) => Buffer.from(d.buffer, d.byteOffset, d.byteLength))));
  } catch {
    return null;
  }
  const stride = width * channels;
  if (raw.length < (stride + 1) * height) return null;
  const cur = new Uint8Array(stride);
  const prev = new Uint8Array(stride);
  const gray = new Uint8Array(width * height);
  for (let y = 0; y < height; y++) {
    const f = raw[y * (stride + 1)];
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    for (let i = 0; i < stride; i++) {
      const a = i >= channels ? cur[i - channels] : 0;
      const b = prev[i];
      const c = i >= channels ? prev[i - channels] : 0;
      let v = line[i];
      if (f === 1) v += a;
      else if (f === 2) v += b;
      else if (f === 3) v += (a + b) >> 1;
      else if (f === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a);
        const pb = Math.abs(p - b);
        const pc = Math.abs(p - c);
        v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      cur[i] = v & 0xff;
    }
    for (let x = 0; x < width; x++) {
      const o = x * channels;
      gray[y * width + x] = channels >= 3 ? Math.round(0.299 * cur[o] + 0.587 * cur[o + 1] + 0.114 * cur[o + 2]) : cur[o];
    }
    prev.set(cur);
  }
  return { width, height, gray };
}

// cells of `cell` meters: free when most of its pixels are free and none is a wall, then walls grow by `clearance`
export function gridFromPng(png: Uint8Array, meta: FloorMeta, cell = 0.15, clearance = 0.25): Grid | null {
  const img = decodePng(png);
  if (!img || img.width !== meta.width || img.height !== meta.height) return null;
  const k = Math.max(1, Math.round(cell / meta.resolution));
  const size = k * meta.resolution;
  const cols = Math.floor(img.width / k);
  const rows = Math.floor(img.height / k);
  if (!cols || !rows || cols * rows > 2_000_000) return null;
  const open = new Uint8Array(cols * rows);
  for (let r = 0; r < rows; r++) {
    // row 0 = the bottom of the map (y up), the image's last pixel rows
    const v0 = img.height - (r + 1) * k;
    for (let c = 0; c < cols; c++) {
      let free = 0;
      let wall = false;
      for (let dv = 0; dv < k && !wall; dv++) {
        for (let du = 0; du < k; du++) {
          const g = img.gray[(v0 + dv) * img.width + c * k + du];
          if (g < WALL) {
            wall = true;
            break;
          }
          if (g > FREE) free++;
        }
      }
      open[r * cols + c] = !wall && free >= k * k * 0.6 ? 1 : 0;
    }
  }
  const grow = Math.max(0, Math.round(clearance / size));
  const freeCells = new Uint8Array(cols * rows);
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) {
      if (!open[r * cols + c]) continue;
      let ok = true;
      for (let dr = -grow; dr <= grow && ok; dr++) {
        for (let dc = -grow; dc <= grow; dc++) {
          const rr = r + dr;
          const cc = c + dc;
          if (rr < 0 || cc < 0 || rr >= rows || cc >= cols || !open[rr * cols + cc]) {
            ok = false;
            break;
          }
        }
      }
      freeCells[r * cols + c] = ok ? 1 : 0;
    }
  }
  return { cols, rows, cell: size, originX: meta.originX, originY: meta.originY, free: freeCells };
}

const toCell = (g: Grid, p: Pt) => ({ c: Math.floor((p.x - g.originX) / g.cell), r: Math.floor((p.y - g.originY) / g.cell) });
const toWorld = (g: Grid, c: number, r: number): Pt => ({ x: g.originX + (c + 0.5) * g.cell, y: g.originY + (r + 0.5) * g.cell });
const isFree = (g: Grid, c: number, r: number) => c >= 0 && r >= 0 && c < g.cols && r < g.rows && g.free[r * g.cols + c] === 1;

// a spot a little off the free space (on a wall line, a door): the nearest free cell within about a metre
function nearestFree(g: Grid, p: Pt) {
  const s = toCell(g, p);
  if (isFree(g, s.c, s.r)) return s;
  const max = Math.ceil(1.2 / g.cell);
  for (let d = 1; d <= max; d++) {
    for (let dr = -d; dr <= d; dr++) {
      for (let dc = -d; dc <= d; dc++) {
        if (Math.max(Math.abs(dr), Math.abs(dc)) === d && isFree(g, s.c + dc, s.r + dr)) return { c: s.c + dc, r: s.r + dr };
      }
    }
  }
  return null;
}

function lineFree(g: Grid, a: { c: number; r: number }, b: { c: number; r: number }) {
  const n = Math.max(Math.abs(b.c - a.c), Math.abs(b.r - a.r)) * 2;
  for (let i = 0; i <= n; i++) {
    const t = n ? i / n : 0;
    if (!isFree(g, Math.round(a.c + (b.c - a.c) * t), Math.round(a.r + (b.r - a.r) * t))) return false;
  }
  return true;
}

// a* over the free cells (8 neighbours), then the corners only (straight where the corridor allows).
// the way from `from` to `to` in map meters, both ends included. null when there is none
export function planPath(g: Grid, from: Pt, to: Pt): Pt[] | null {
  const s = nearestFree(g, from);
  const t = nearestFree(g, to);
  if (!s || !t) return null;
  const n = g.cols * g.rows;
  const cost = new Float64Array(n).fill(Infinity);
  const came = new Int32Array(n).fill(-1);
  const closed = new Uint8Array(n);
  const start = s.r * g.cols + s.c;
  const goal = t.r * g.cols + t.c;
  cost[start] = 0;
  // a binary heap of [f, index]
  const heap: number[][] = [[0, start]];
  const push = (item: number[]) => {
    heap.push(item);
    let i = heap.length - 1;
    while (i > 0) {
      const p = (i - 1) >> 1;
      if (heap[p][0] <= heap[i][0]) break;
      [heap[p], heap[i]] = [heap[i], heap[p]];
      i = p;
    }
  };
  const pop = () => {
    const top = heap[0];
    const last = heap.pop() as number[];
    if (heap.length) {
      heap[0] = last;
      let i = 0;
      for (;;) {
        const l = i * 2 + 1;
        const r = l + 1;
        let m = i;
        if (l < heap.length && heap[l][0] < heap[m][0]) m = l;
        if (r < heap.length && heap[r][0] < heap[m][0]) m = r;
        if (m === i) break;
        [heap[m], heap[i]] = [heap[i], heap[m]];
        i = m;
      }
    }
    return top;
  };
  const h = (i: number) => {
    const dc = Math.abs((i % g.cols) - t.c);
    const dr = Math.abs(Math.floor(i / g.cols) - t.r);
    return Math.max(dc, dr) + (Math.SQRT2 - 1) * Math.min(dc, dr);
  };
  const STEPS = [
    [1, 0, 1],
    [-1, 0, 1],
    [0, 1, 1],
    [0, -1, 1],
    [1, 1, Math.SQRT2],
    [1, -1, Math.SQRT2],
    [-1, 1, Math.SQRT2],
    [-1, -1, Math.SQRT2],
  ];
  while (heap.length) {
    const [, i] = pop();
    if (closed[i]) continue;
    if (i === goal) break;
    closed[i] = 1;
    const c = i % g.cols;
    const r = Math.floor(i / g.cols);
    for (const [dc, dr, w] of STEPS) {
      const nc = c + dc;
      const nr = r + dr;
      if (!isFree(g, nc, nr)) continue;
      // no cutting corners between two walls
      if (dc && dr && (!isFree(g, c + dc, r) || !isFree(g, c, r + dr))) continue;
      const j = nr * g.cols + nc;
      const nextCost = cost[i] + w;
      if (nextCost < cost[j]) {
        cost[j] = nextCost;
        came[j] = i;
        push([nextCost + h(j), j]);
      }
    }
  }
  if (goal !== start && came[goal] === -1) return null;
  const cells: { c: number; r: number }[] = [];
  for (let i = goal; i !== -1; i = i === start ? -1 : came[i]) cells.push({ c: i % g.cols, r: Math.floor(i / g.cols) });
  cells.reverse();
  // keep the corners: from each kept cell, the furthest one it can see in a straight line
  const kept = [cells[0]];
  let a = 0;
  while (a < cells.length - 1) {
    let b = cells.length - 1;
    while (b > a + 1 && !lineFree(g, cells[a], cells[b])) b--;
    kept.push(cells[b]);
    a = b;
  }
  const middle = kept.slice(1, -1).map((k) => toWorld(g, k.c, k.r));
  return [from, ...middle, to];
}
