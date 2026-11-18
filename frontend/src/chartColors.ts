/** Categorical chart colors (one per service). The palette itself lives in src/index.css (--chart-1..8). */
const CHART_COLOR_COUNT = 8;

export function chartColor(index: number): string {
  return `var(--chart-${(index % CHART_COLOR_COUNT) + 1})`;
}
