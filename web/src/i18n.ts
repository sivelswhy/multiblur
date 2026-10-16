/** Interface strings in English and French, like the macOS app. */
const strings = {
  en: {
    tagline: "Masks license plates and faces in photos and videos, on your device. Nothing is uploaded.",
    source: "Source & macOS app",
    language: "Language",
    "models.title": "Models",
    "models.runtime": "Detection engine",
    "models.plates": "License plate model",
    "models.faces": "Face model",
    "models.waiting": "Waiting…",
    "models.preparing": "Preparing…",
    "models.ready": "Ready",
    "models.cached": "Ready · from cache",
    "models.failed": "Failed",
    "models.allReady": "Models ready",
    "models.error": "The models couldn't be loaded ({error}). Check your connection and reload the page.",
    "backend.gpu": "GPU",
    "backend.cpu": "CPU · slower",
    "backend.gpuTitle": "Running on the GPU through WebGPU",
    "backend.cpuTitle": "This browser doesn't offer WebGPU: running on the CPU through WebAssembly",
    "drop.title": "Drop photos or videos",
    "drop.sub": "or click to choose files",
    "drop.privacy": "Processed in this tab: your files never leave your device.",
    "settings.mask": "Mask",
    "settings.plates": "License plates",
    "settings.faces": "Faces",
    "settings.style": "Style",
    "mode.blur": "Blur",
    "mode.mosaic": "Mosaic",
    "mode.solid": "Box",
    "mode.image": "Image",
    "settings.blockSize": "Block size",
    "settings.replacement": "Replacement image",
    "settings.chooseImage": "Choose…",
    "settings.unreadableImage": "Can't read this image",
    "settings.replacementNote": "Transparent areas show a blur, never the original.",
    "settings.faceShape": "Face shape",
    "shape.ellipse": "Ellipse",
    "shape.box": "Box",
    "settings.detection": "Detection",
    "settings.plateConfidence": "Plate confidence",
    "settings.plateConfidenceNote": "Lower finds more plates, but also flags ordinary text such as signs.",
    "settings.faceConfidence": "Face confidence",
    "settings.plateMargin": "Plate margin",
    "settings.faceMargin": "Face margin",
    "settings.faceResolution": "Face resolution",
    "resolution.full": "Full",
    "resolution.fast": "640 px (fastest)",
    "settings.smallObjects": "Small, distant objects",
    "small.off": "Don't search",
    "small.photos": "In photos",
    "small.all": "In photos & videos (slow)",
    "settings.scores": "Show detection scores",
    "settings.output": "Output",
    "settings.keepAudio": "Keep the sound of videos",
    "settings.outputNote": "Photos are saved without their EXIF and GPS metadata. Videos are saved as MP4.",
    "settings.reset": "Restore defaults",
    "queue.done": "{done} of {total} done",
    "queue.files": "{count} file(s)",
    "queue.downloadAll": "Download all",
    "queue.clear": "Clear finished",
    "item.open": "Open the result",
    "item.waiting": "Waiting for the models…",
    "item.queued": "Waiting…",
    "item.starting": "Starting…",
    "item.detecting": "Detecting…",
    "item.progress": "{percent} %",
    "item.progressLeft": "{percent} % · about {time} left",
    "item.download": "Download",
    "item.share": "Save…",
    "item.stop": "Stop",
    "item.remove": "Remove",
    "item.stopped": "Stopped",
    "item.plates": "{count} plate(s)",
    "item.faces": "{count} face(s)",
    "item.nothing": "Nothing to mask",
    "error.noTarget": "Choose what to mask: plates, faces or both.",
    "error.noReplacement": "Choose a replacement image under Style.",
    "error.noEncoder": "This browser can't encode video. Try a recent Chrome, Edge or Safari.",
    "error.noVideoTrack": "No video track found.",
    "error.cantConvert": "This browser can't convert this video ({reasons}).",
    "warning.audioDropped": "This browser can't encode the sound of this video: it was removed.",
    close: "Close",
    footer: "Plates: YOLOv11 by morsetechlab · Faces: CenterFace via deface · ONNX Runtime Web",
  },
  fr: {
    tagline: "Masque les plaques d'immatriculation et les visages des photos et vidéos, sur votre appareil. Rien n'est envoyé.",
    source: "Code source et app macOS",
    language: "Langue",
    "models.title": "Modèles",
    "models.runtime": "Moteur de détection",
    "models.plates": "Modèle des plaques",
    "models.faces": "Modèle des visages",
    "models.waiting": "En attente…",
    "models.preparing": "Préparation…",
    "models.ready": "Prêt",
    "models.cached": "Prêt · depuis le cache",
    "models.failed": "Échec",
    "models.allReady": "Modèles prêts",
    "models.error": "Impossible de charger les modèles ({error}). Vérifiez votre connexion et rechargez la page.",
    "backend.gpu": "GPU",
    "backend.cpu": "CPU · plus lent",
    "backend.gpuTitle": "Exécution sur le GPU via WebGPU",
    "backend.cpuTitle": "Ce navigateur ne propose pas WebGPU : exécution sur le CPU via WebAssembly",
    "drop.title": "Déposez des photos ou des vidéos",
    "drop.sub": "ou cliquez pour choisir des fichiers",
    "drop.privacy": "Traité dans cet onglet : vos fichiers ne quittent jamais votre appareil.",
    "settings.mask": "Masquer",
    "settings.plates": "Plaques",
    "settings.faces": "Visages",
    "settings.style": "Style",
    "mode.blur": "Flou",
    "mode.mosaic": "Mosaïque",
    "mode.solid": "Boîte",
    "mode.image": "Image",
    "settings.blockSize": "Taille des blocs",
    "settings.replacement": "Image de remplacement",
    "settings.chooseImage": "Choisir…",
    "settings.unreadableImage": "Image illisible",
    "settings.replacementNote": "Les zones transparentes montrent un flou, jamais l'original.",
    "settings.faceShape": "Forme des visages",
    "shape.ellipse": "Ellipse",
    "shape.box": "Rectangle",
    "settings.detection": "Détection",
    "settings.plateConfidence": "Confiance des plaques",
    "settings.plateConfidenceNote": "Plus bas trouve plus de plaques, mais aussi du texte ordinaire comme des panneaux.",
    "settings.faceConfidence": "Confiance des visages",
    "settings.plateMargin": "Marge des plaques",
    "settings.faceMargin": "Marge des visages",
    "settings.faceResolution": "Résolution des visages",
    "resolution.full": "Complète",
    "resolution.fast": "640 px (plus rapide)",
    "settings.smallObjects": "Petits objets lointains",
    "small.off": "Ne pas chercher",
    "small.photos": "Dans les photos",
    "small.all": "Photos et vidéos (lent)",
    "settings.scores": "Afficher les scores de détection",
    "settings.output": "Sortie",
    "settings.keepAudio": "Garder le son des vidéos",
    "settings.outputNote": "Les photos sont enregistrées sans leurs métadonnées EXIF et GPS. Les vidéos sont enregistrées en MP4.",
    "settings.reset": "Rétablir les réglages par défaut",
    "queue.done": "{done} / {total} terminés",
    "queue.files": "{count} fichier(s)",
    "queue.downloadAll": "Tout télécharger",
    "queue.clear": "Effacer les terminés",
    "item.open": "Ouvrir le résultat",
    "item.waiting": "En attente des modèles…",
    "item.queued": "En attente…",
    "item.starting": "Démarrage…",
    "item.detecting": "Détection…",
    "item.progress": "{percent} %",
    "item.progressLeft": "{percent} % · environ {time} restant",
    "item.download": "Télécharger",
    "item.share": "Enregistrer…",
    "item.stop": "Arrêter",
    "item.remove": "Retirer",
    "item.stopped": "Arrêté",
    "item.plates": "{count} plaque(s)",
    "item.faces": "{count} visage(s)",
    "item.nothing": "Rien à masquer",
    "error.noTarget": "Choisissez quoi masquer : plaques, visages ou les deux.",
    "error.noReplacement": "Choisissez une image de remplacement sous Style.",
    "error.noEncoder": "Ce navigateur ne sait pas encoder de vidéo. Essayez un Chrome, Edge ou Safari récent.",
    "error.noVideoTrack": "Aucune piste vidéo trouvée.",
    "error.cantConvert": "Ce navigateur ne peut pas convertir cette vidéo ({reasons}).",
    "warning.audioDropped": "Ce navigateur ne sait pas encoder le son de cette vidéo : il a été retiré.",
    close: "Fermer",
    footer: "Plaques : YOLOv11 de morsetechlab · Visages : CenterFace via deface · ONNX Runtime Web",
  },
} as const;

export type Language = keyof typeof strings;
export type Key = keyof (typeof strings)["en"];

const KEY = "multiblur.language";

function initialLanguage(): Language {
  try {
    const saved = localStorage.getItem(KEY);
    if (saved === "en" || saved === "fr") return saved;
  } catch {
    // Storage unavailable: follow the browser.
  }
  return navigator.language.toLowerCase().startsWith("fr") ? "fr" : "en";
}

let current: Language = initialLanguage();
const listeners = new Set<() => void>();

export const language = () => current;

export function setLanguage(next: Language) {
  if (next === current) return;
  current = next;
  try {
    localStorage.setItem(KEY, next);
  } catch {
    // Remembered for this visit only.
  }
  listeners.forEach((listener) => listener());
}

export function onLanguageChange(listener: () => void) {
  listeners.add(listener);
}

/** Translates `key`, filling in `{name}` placeholders. "(s)" becomes a plural ending when `count` isn't 1. */
export function t(key: Key, params: Record<string, string | number> = {}): string {
  let text: string = strings[current][key];
  for (const [name, value] of Object.entries(params)) text = text.replaceAll(`{${name}}`, String(value));
  if ("count" in params) text = text.replaceAll("(s)", Number(params.count) === 1 ? "" : "s");
  return text;
}

/** Fills every [data-i18n] element (text) and [data-i18n-*] attribute in `root`. */
export function translate(root: ParentNode = document) {
  document.documentElement.lang = current;
  for (const element of root.querySelectorAll<HTMLElement>("[data-i18n]")) element.textContent = t(element.dataset.i18n as Key);
  for (const attribute of ["title", "aria-label"]) {
    const data = `data-i18n-${attribute}`;
    for (const element of root.querySelectorAll<HTMLElement>(`[${data}]`)) {
      element.setAttribute(attribute, t(element.getAttribute(data) as Key));
    }
  }
}
