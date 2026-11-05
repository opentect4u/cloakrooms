/**
 * Server-side geometry for the panel's column charts (no client chart library: the CSP allows no
 * inline script and the view stays declarative). Marks follow the house spec: columns ≤ 24 px,
 * 4 px rounded data-end, square at the baseline, hairline gridlines, labels only on the extreme
 * and the latest column; every column gets a full-height hover target with a tooltip.
 */

export interface ChartPoint {
  /** Axis label, e.g. "05 Nov". */
  label: string;
  /** Tooltip heading, e.g. "Wed, 05 Nov 2026". */
  title: string;
  value: number;
  /** Extra tooltip line, e.g. "12 bills". */
  detail?: string;
}

export interface ColumnChart {
  width: number;
  height: number;
  baseline: number;
  plotLeft: number;
  plotRight: number;
  ticks: { y: number; label: string }[];
  columns: {
    x: number;
    cx: number;
    path: string;
    hit: { x: number; y: number; w: number; h: number };
    label: string;
    title: string;
    valueText: string;
    /** Compact form for the direct label above the column. */
    valueShort: string;
    detail: string;
    showValue: boolean;
    valueY: number;
    isEmpty: boolean;
  }[];
  max: number;
  empty: boolean;
}

const W = 720;
const H = 260;
const PAD = { top: 26, right: 8, bottom: 30, left: 56 };
const BAR_MAX = 24;
const RADIUS = 4;

/** 1, 2, 2.5, 5 × 10^k at or above `v` (clean axis maxima). */
export function niceCeil(v: number): number {
  if (v <= 0) return 0;
  const exp = 10 ** Math.floor(Math.log10(v));
  for (const m of [1, 2, 2.5, 5, 10]) if (m * exp >= v) return m * exp;
  return 10 * exp;
}

/** Column whose top corners are rounded (radius clipped to the column height). */
function columnPath(x: number, y: number, w: number, h: number): string {
  const r = Math.min(RADIUS, h, w / 2);
  const b = y + h;
  return [
    `M${x},${b}`,
    `V${y + r}`,
    `Q${x},${y} ${x + r},${y}`,
    `H${x + w - r}`,
    `Q${x + w},${y} ${x + w},${y + r}`,
    `V${b}`,
    'Z',
  ].join(' ');
}

export function buildColumnChart(
  points: ChartPoint[],
  format: (v: number) => string,
  formatAxis: (v: number) => string = format,
): ColumnChart {
  const plotW = W - PAD.left - PAD.right;
  const plotH = H - PAD.top - PAD.bottom;
  const baseline = PAD.top + plotH;
  const rawMax = Math.max(0, ...points.map((p) => p.value));
  const max = niceCeil(rawMax) || 100;
  const band = plotW / Math.max(points.length, 1);
  const barW = Math.min(BAR_MAX, Math.max(4, band - 8));

  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => ({
    y: Math.round(baseline - f * plotH) + 0.5,
    label: formatAxis(max * f),
  }));

  let peak = -1;
  points.forEach((p, i) => {
    if (p.value > 0 && (peak < 0 || p.value > (points[peak]?.value ?? 0))) peak = i;
  });
  const last = points.length - 1;

  const columns = points.map((p, i) => {
    const x = PAD.left + band * i + (band - barW) / 2;
    const h = rawMax > 0 ? (p.value / max) * plotH : 0;
    const y = baseline - h;
    return {
      x,
      cx: x + barW / 2,
      path: h > 0 ? columnPath(x, y, barW, h) : '',
      hit: { x: PAD.left + band * i, y: PAD.top, w: band, h: plotH },
      label: p.label,
      title: p.title,
      valueText: format(p.value),
      valueShort: formatAxis(p.value),
      detail: p.detail ?? '',
      showValue: p.value > 0 && (i === peak || i === last),
      valueY: y - 8,
      isEmpty: h === 0,
    };
  });

  return {
    width: W,
    height: H,
    baseline,
    plotLeft: PAD.left,
    plotRight: W - PAD.right,
    ticks,
    columns,
    max,
    empty: rawMax === 0,
  };
}
