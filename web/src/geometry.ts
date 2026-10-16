/** A box in pixels, top-left origin. */
export interface Rect {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** A detected area, already enlarged by its mask scale. */
export interface Detection {
  rect: Rect;
  score: number;
  isFace: boolean;
  /** The detected box itself, before the mask scale enlarges it. Tracking compares these: enlarged
   *  masks of faces side by side overlap, which let a track jump to the neighbor. */
  core: Rect;
  /** Track identifier in videos. */
  id?: number;
}

export interface Found {
  rect: Rect;
  score: number;
}

export const area = (r: Rect) => Math.max(0, r.width) * Math.max(0, r.height);
export const midX = (r: Rect) => r.x + r.width / 2;
export const midY = (r: Rect) => r.y + r.height / 2;
export const offset = (r: Rect, dx: number, dy: number): Rect => ({ ...r, x: r.x + dx, y: r.y + dy });

export function intersection(a: Rect, b: Rect): Rect | null {
  const x = Math.max(a.x, b.x), y = Math.max(a.y, b.y);
  const width = Math.min(a.x + a.width, b.x + b.width) - x;
  const height = Math.min(a.y + a.height, b.y + b.height) - y;
  return width > 0 && height > 0 ? { x, y, width, height } : null;
}

export function iou(a: Rect, b: Rect): number {
  const overlap = intersection(a, b);
  if (!overlap) return 0;
  const inter = area(overlap);
  return inter / (area(a) + area(b) - inter);
}

/** deface's mask scale: each side grows by (scale - 1) × the box size. */
export function enlarge(r: Rect, scale: number): Rect {
  const s = scale - 1;
  return { x: r.x - r.width * s, y: r.y - r.height * s, width: r.width * (1 + 2 * s), height: r.height * (1 + 2 * s) };
}

/** Tiles of `size` overlapping by 25%; none when the image is barely larger than a tile. */
export function tiles(width: number, height: number, size: number): Rect[] {
  if (Math.max(width, height) <= size * 1.25) return [];
  const step = size * 0.75;
  const starts = (length: number) => {
    if (length <= size) return [0];
    const count = Math.ceil((length - size) / step) + 1;
    return Array.from({ length: count }, (_, i) => Math.round(Math.min(i * step, length - size)));
  };
  return starts(height).flatMap((y) =>
    starts(width).map((x) => ({ x, y, width: Math.min(size, width), height: Math.min(size, height) })),
  );
}

/** Keeps the best of overlapping boxes, including a partial box cut by a tile edge inside a full one. */
export function suppressDuplicates(found: Found[]): Found[] {
  const kept: Found[] = [];
  for (const candidate of [...found].sort((a, b) => b.score - a.score)) {
    const duplicate = kept.some((other) => {
      const overlap = intersection(other.rect, candidate.rect);
      if (!overlap) return false;
      const inter = area(overlap);
      return iou(other.rect, candidate.rect) >= 0.4 || inter / Math.min(area(other.rect), area(candidate.rect)) >= 0.7;
    });
    if (!duplicate) kept.push(candidate);
  }
  return kept;
}

/** Plain non-maximum suppression at `threshold` IoU. */
export function nonMaximumSuppression(found: Found[], threshold: number): Found[] {
  const kept: Found[] = [];
  for (const box of [...found].sort((a, b) => b.score - a.score)) {
    if (!kept.some((other) => iou(box.rect, other.rect) >= threshold)) kept.push(box);
  }
  return kept;
}
