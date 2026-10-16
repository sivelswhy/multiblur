import { type Detection, type Rect, iou, midX, midY, offset } from "./geometry";

interface Track {
  id: number;
  detection: Detection;
  /** The detected box (without the mask's margin), used for matching. */
  core: Rect;
  vx: number;
  vy: number;
  missed: number;
  /** Frames in which the object was actually detected. */
  hits: number;
}

/** Minimum overlap between a track's predicted box and a detection to continue the track. */
const MINIMUM_OVERLAP = 0.1;
/** How much a new detection changes the mask's size: detectors' box sizes flicker from frame to frame. */
const SIZE_RESPONSE = 0.3;
/** A smoothed mask is never smaller than this share of the current detection, so lag can't uncover it. */
const MINIMUM_COVERAGE = 0.85;
/** Share of the velocity kept for each frame without a detection (total drift stays within ~1.5 mask sizes). */
const MISSED_VELOCITY_DECAY = 0.8;

/** Follows plates and faces across video frames so each object keeps exactly one steady mask, and that
 *  mask keeps following the object for a few frames when the detector misses it. Port of Tracker.swift. */
export class Tracker {
  private tracks: Track[] = [];
  private nextID = 0;

  /** `maxMissed`: frames a track survives without a matching detection before it's dropped. */
  constructor(private readonly maxMissed: number) {}

  /** Forgets every track, e.g. at a cut between shots. */
  reset() {
    this.tracks = [];
  }

  /** Feeds one frame's detections; returns the areas to mask in that frame. */
  update(detections: Detection[]): Detection[] {
    // Predict where each tracked object is now.
    for (const t of this.tracks) {
      t.detection = { ...t.detection, rect: offset(t.detection.rect, t.vx, t.vy) };
      t.core = offset(t.core, t.vx, t.vy);
    }

    // Candidate pairs, compared on detected boxes (not enlarged masks): clear overlap, or, for fast
    // movers, a nearby center when there's no other candidate on either side (never between neighbors).
    type Pair = { track: number; detection: number; score: number };
    const overlapping: Pair[] = [];
    const nearby: (Pair & { close: boolean })[] = [];
    this.tracks.forEach((track, t) => {
      detections.forEach((detection, d) => {
        if (detection.isFace !== track.detection.isFace) return;
        const core = detection.core;
        const overlap = iou(track.core, core);
        if (overlap >= MINIMUM_OVERLAP) {
          overlapping.push({ track: t, detection: d, score: overlap });
        } else {
          const distance = Math.hypot(midX(track.core) - midX(core), midY(track.core) - midY(core));
          const size = Math.max(track.core.width, track.core.height, core.width, core.height);
          if (distance < size) {
            nearby.push({ track: t, detection: d, score: 0.05 * (1 - distance / size), close: distance < 0.5 * size });
          }
        }
      });
    });
    // Close centers (under half a face) are accepted; farther ones (up to a face) only when there's
    // no other candidate on either side, so a track never reaches over to a neighbor.
    const candidates = [...overlapping, ...nearby];
    const accepted = nearby.filter(
      (pair) =>
        pair.close ||
        (candidates.filter((c) => c.track === pair.track).length === 1 &&
          candidates.filter((c) => c.detection === pair.detection).length === 1),
    );
    const matchedTracks = new Set<number>(), matchedDetections = new Set<number>();
    for (const pair of [...overlapping, ...accepted].sort((a, b) => b.score - a.score)) {
      if (matchedTracks.has(pair.track) || matchedDetections.has(pair.detection)) continue;
      matchedTracks.add(pair.track);
      matchedDetections.add(pair.detection);
      this.follow(this.tracks[pair.track], detections[pair.detection]);
    }

    this.tracks.forEach((track, t) => {
      if (matchedTracks.has(t)) return;
      // Unseen: keep the size and let the motion fade out, so the mask can't fly off or zoom.
      track.missed += 1;
      track.vx *= MISSED_VELOCITY_DECAY;
      track.vy *= MISSED_VELOCITY_DECAY;
    });
    // Objects seen only once or twice (crowds, flickering false detections) are kept briefly; well
    // established ones keep the full memory.
    this.tracks = this.tracks.filter((t) => t.missed <= Math.min(this.maxMissed, 2 + 2 * t.hits));
    detections.forEach((detection, d) => {
      if (matchedDetections.has(d)) return;
      this.tracks.push({ id: this.nextID++, detection, core: detection.core, vx: 0, vy: 0, missed: 0, hits: 1 });
    });
    return this.tracks.map((t) => ({ ...t.detection, id: t.id }));
  }

  private follow(track: Track, detection: Detection) {
    const predicted = track.detection.rect, measured = detection.rect;
    // The center follows the detection exactly: smoothing it makes masks lag behind moving faces.
    const cx = midX(measured), cy = midY(measured);
    const width = Math.max(predicted.width + SIZE_RESPONSE * (measured.width - predicted.width), measured.width * MINIMUM_COVERAGE);
    const height = Math.max(predicted.height + SIZE_RESPONSE * (measured.height - predicted.height), measured.height * MINIMUM_COVERAGE);

    // Velocity from the motion between detections, capped to a plausible speed per frame.
    const previousX = midX(predicted) - track.vx, previousY = midY(predicted) - track.vy;
    const limit = 0.3 * Math.max(width, height);
    const clamp = (v: number) => Math.min(Math.max(v, -limit), limit);
    track.vx = clamp(0.5 * track.vx + 0.5 * (cx - previousX));
    track.vy = clamp(0.5 * track.vy + 0.5 * (cy - previousY));
    track.detection = { ...detection, rect: { x: cx - width / 2, y: cy - height / 2, width, height } };
    track.core = detection.core;
    track.missed = 0;
    track.hits += 1;
  }
}

/** Spots hard cuts between video shots, so tracking restarts instead of handing one person's track to
 *  whoever stands at the same place in the next shot. Port of CutDetector.swift. */
export class CutDetector {
  private static readonly width = 32;
  private static readonly height = 18;
  private static readonly spike = 6;
  private static readonly minimumChange = 0.08;
  private static readonly memory = 8;

  private readonly context = new OffscreenCanvas(CutDetector.width, CutDetector.height).getContext("2d", {
    willReadFrequently: true,
  })!;
  private previous?: Uint8Array;
  private recentChanges: number[] = [];

  /** Feeds the next frame; true when it starts a new shot. */
  isCut(frame: CanvasImageSource): boolean {
    const { width, height } = CutDetector;
    this.context.drawImage(frame, 0, 0, width, height);
    const pixels = this.context.getImageData(0, 0, width, height).data;
    const gray = new Uint8Array(width * height);
    for (let i = 0; i < gray.length; i++) {
      gray[i] = (pixels[4 * i] * 0.299 + pixels[4 * i + 1] * 0.587 + pixels[4 * i + 2] * 0.114) | 0;
    }
    const previous = this.previous;
    this.previous = gray;
    if (!previous) return false;

    let sum = 0;
    for (let i = 0; i < gray.length; i++) sum += Math.abs(gray[i] - previous[i]);
    const change = sum / (gray.length * 255);
    const usual = this.recentChanges.length ? this.recentChanges.reduce((a, b) => a + b, 0) / this.recentChanges.length : 0;
    this.recentChanges.push(change);
    if (this.recentChanges.length > CutDetector.memory) this.recentChanges.shift();
    const cut = change > CutDetector.minimumChange && change > CutDetector.spike * (usual + 0.01);
    if (cut) this.recentChanges = [];
    return cut;
  }
}
