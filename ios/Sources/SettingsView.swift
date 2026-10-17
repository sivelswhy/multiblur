import PhotosUI
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var replacementItem: PhotosPickerItem?

    var body: some View {
        NavigationStack {
            Form {
                Section("Mask") {
                    Toggle("Plates", isOn: $store.options.maskPlates)
                    Toggle("Faces", isOn: $store.options.maskFaces)
                }

                Section {
                    Picker("Style", selection: $store.options.mode) {
                        ForEach(MaskMode.allCases) { Text($0.label).tag($0) }
                    }
                    if store.options.mode == .mosaic {
                        LabeledSlider(title: "Block size", value: $store.options.mosaicSize, range: 4...60, step: 1) { "\(Int($0)) px" }
                    }
                    if store.options.mode == .image {
                        PhotosPicker(selection: $replacementItem, matching: .images) {
                            LabeledContent("Replacement image") {
                                Text(store.options.replacementImagePath == nil ? String(localized: "Choose Image…") : String(localized: "Change…"))
                            }
                        }
                    }
                    Picker("Face mask", selection: $store.options.faceShape) {
                        ForEach(MaskShape.allCases) { Text($0.label).tag($0) }
                    }
                } header: {
                    Text("Style")
                } footer: {
                    if store.options.mode == .image {
                        Text("Transparent areas show a blur, never the original.")
                    }
                }

                Section {
                    Picker("Voices", selection: $store.options.voice) {
                        ForEach(VoiceEffect.allCases) { Text($0.label).tag($0) }
                    }
                    Toggle("Keep audio", isOn: $store.options.keepAudio)
                } header: {
                    Text("Videos")
                } footer: {
                    Text("Whisper and synthetic voice can't be reversed: pitch and timbre are discarded. Words stay understandable.")
                }

                Section("Detection") {
                    LabeledSlider(title: "Plate confidence", value: $store.options.plateConfidence, range: 0.05...0.9, step: 0.05) { String(format: "%.2f", $0) }
                    LabeledSlider(title: "Face threshold", value: $store.options.faceThreshold, range: 0.05...0.9, step: 0.05) { String(format: "%.2f", $0) }
                    LabeledSlider(title: "Plate mask scale", value: $store.options.plateMaskScale, range: 1...1.6, step: 0.05) { String(format: "×%.2f", $0) }
                    LabeledSlider(title: "Face mask scale", value: $store.options.faceMaskScale, range: 1...1.8, step: 0.05) { String(format: "×%.2f", $0) }
                    Picker("Detection resolution", selection: $store.options.faceResolution) {
                        ForEach(DetectionResolution.allCases) { Text($0.label).tag($0) }
                    }
                    Picker("Small objects", selection: $store.options.smallObjects) {
                        ForEach(SmallObjectSearch.allCases) { Text($0.label).tag($0) }
                    }
                    Toggle("Show detection scores", isOn: $store.options.drawScores)
                }

                Section("Output") {
                    Picker("Video codec", selection: $store.options.videoCodec) {
                        ForEach(VideoCodec.allCases) { Text($0.label).tag($0) }
                    }
                    Toggle("Keep metadata", isOn: $store.options.keepMetadata)
                    Picker("Run models on", selection: $store.options.compute) {
                        ForEach(ComputeMode.allCases) { Text($0.label).tag($0) }
                    }
                }

                Section {
                    // iOS sets each app's language in Settings › Apps › MultiBlur › Language.
                    Button("Change Language…") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                    Button("Restore Defaults", role: .destructive) {
                        store.options = Options()
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .onChange(of: store.options.compute) { _, _ in store.loadEngine() }
            .onChange(of: replacementItem) { _, item in
                guard let item else { return }
                Task {
                    if let media = try? await item.loadTransferable(type: PickedMedia.self) {
                        store.setReplacementImage(from: media.url)
                        try? FileManager.default.removeItem(at: media.url)
                    }
                    replacementItem = nil
                }
            }
        }
    }
}

private struct LabeledSlider: View {
    let title: LocalizedStringKey
    @Binding var value: Double
    let range: ClosedRange<Double>
    let step: Double
    let format: (Double) -> String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            LabeledContent(title) { Text(format(value)).monospacedDigit() }
            Slider(value: $value, in: range, step: step)
        }
    }
}
