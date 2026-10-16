import type { MaskMode } from "./mask";

/** Every user-facing setting. Defaults mirror the macOS app's (and deface's where an equivalent exists). */
export interface Options {
  maskPlates: boolean;
  maskFaces: boolean;
  mode: MaskMode;
  mosaicSize: number;
  faceShape: "ellipse" | "box";
  drawScores: boolean;
  plateConfidence: number;
  faceThreshold: number;
  plateMaskScale: number;
  faceMaskScale: number;
  /** 0 = full resolution (up to 2048 px), otherwise the longest side faces are analyzed at. */
  faceResolution: number;
  smallObjects: "off" | "photos" | "all";
  keepAudio: boolean;
}

export const DEFAULTS: Options = {
  maskPlates: true,
  maskFaces: true,
  mode: "blur",
  mosaicSize: 20,
  faceShape: "ellipse",
  drawScores: false,
  plateConfidence: 0.4,
  faceThreshold: 0.2,
  plateMaskScale: 1.15,
  faceMaskScale: 1.3,
  faceResolution: 0,
  smallObjects: "photos",
  keepAudio: true,
};

const KEY = "multiblur.options";

export function loadOptions(): Options {
  try {
    // Saved values over the defaults, so settings added later keep their default.
    return { ...DEFAULTS, ...JSON.parse(localStorage.getItem(KEY) ?? "{}") };
  } catch {
    return { ...DEFAULTS };
  }
}

export function saveOptions(options: Options) {
  try {
    localStorage.setItem(KEY, JSON.stringify(options));
  } catch {
    // Storage unavailable: settings last for this visit only.
  }
}
