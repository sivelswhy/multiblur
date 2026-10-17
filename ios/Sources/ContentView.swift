import PhotosUI
import QuickLook
import SwiftUI

@main
struct MultiBlurApp: App {
    @StateObject private var store = Store()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                // "Open in MultiBlur" from Files or another app.
                .onOpenURL { url in
                    if let copy = try? Store.importCopy(of: url) { store.add([copy]) }
                }
        }
    }
}

struct ContentView: View {
    @EnvironmentObject private var store: Store
    @State private var picked: [PhotosPickerItem] = []
    @State private var importing = false
    @State private var showSettings = false
    @State private var preview: URL?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    EngineRow(state: store.engineState, compute: store.options.compute)
                }

                Section {
                    PhotosPicker(selection: $picked, maxSelectionCount: 50, matching: .any(of: [.images, .videos]),
                                 preferredItemEncoding: .current) {
                        Label("Add Photos or Videos", systemImage: "photo.badge.plus")
                            .font(.headline)
                            .padding(.vertical, 6)
                    }
                    Button {
                        importing = true
                    } label: {
                        Label("Import from Files", systemImage: "folder")
                    }
                } footer: {
                    Label("Processed on this iPhone: your photos never leave it. Results are saved to your photo library.",
                          systemImage: "lock.shield")
                        .font(.footnote)
                }

                if !store.jobs.isEmpty {
                    Section {
                        ForEach(store.jobs) { job in
                            JobRow(job: job) { preview = job.output }
                                .swipeActions {
                                    if job.status != .processing {
                                        Button("Remove", role: .destructive) { store.remove(job) }
                                    }
                                }
                        }
                    } header: {
                        HStack {
                            let done = store.jobs.filter { $0.status == .done }.count
                            Text("\(done) of \(store.jobs.count) done")
                            Spacer()
                            if store.jobs.contains(where: { $0.status != .queued && $0.status != .processing }) {
                                Button("Clear Finished") { store.clearFinished() }
                                    .font(.footnote)
                                    .textCase(nil)
                            }
                        }
                    }
                }
            }
            .navigationTitle("MultiBlur")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showSettings = true
                    } label: {
                        Image(systemName: "slider.horizontal.3")
                    }
                    .accessibilityLabel(Text("Settings"))
                }
            }
            .sheet(isPresented: $showSettings) {
                SettingsView()
                    .environmentObject(store)
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.image, .movie], allowsMultipleSelection: true) { result in
                if case .success(let urls) = result {
                    store.add(urls.compactMap { try? Store.importCopy(of: $0) })
                }
            }
            .onChange(of: picked) { _, items in
                guard !items.isEmpty else { return }
                picked = []
                Task {
                    var urls: [URL] = []
                    for item in items {
                        if let media = try? await item.loadTransferable(type: PickedMedia.self) { urls.append(media.url) }
                    }
                    store.add(urls)
                }
            }
            .quickLookPreview($preview)
        }
    }
}

private struct EngineRow: View {
    let state: Store.EngineState
    let compute: ComputeMode

    var body: some View {
        HStack(spacing: 12) {
            switch state {
            case .loading:
                ProgressView()
                Text("Loading detection models…")
            case .ready:
                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).font(.title3)
                VStack(alignment: .leading) {
                    Text("Models ready")
                    Text(compute == .all ? "Neural Engine" : compute == .gpu ? "GPU" : "CPU")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            case .failed(let message):
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red).font(.title3)
                Text(message).foregroundStyle(.red)
            }
        }
    }
}

private struct JobRow: View {
    @ObservedObject var job: Job
    let open: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                Button(action: open) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 10).fill(Color(.secondarySystemFill))
                        if let thumbnail = job.thumbnail {
                            Image(uiImage: thumbnail).resizable().scaledToFill()
                        } else {
                            Image(systemName: job.isVideo ? "video" : "photo").foregroundStyle(.secondary)
                        }
                    }
                    .frame(width: 60, height: 60)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
                .disabled(job.status != .done)

                VStack(alignment: .leading, spacing: 3) {
                    Text(job.name).font(.subheadline.weight(.semibold)).lineLimit(1).truncationMode(.middle)
                    detail.font(.footnote).foregroundStyle(isError ? .red : .secondary)
                    if job.status == .processing {
                        if let progress = job.progress {
                            ProgressView(value: progress)
                        } else {
                            ProgressView().progressViewStyle(.linear)
                        }
                    }
                }
            }
            HStack {
                Spacer()
                switch job.status {
                case .processing:
                    Button("Stop", role: .cancel) { job.task?.cancel() }
                        .buttonStyle(.bordered)
                case .done:
                    if let output = job.output {
                        ShareLink(item: output) { Label("Share", systemImage: "square.and.arrow.up") }
                            .buttonStyle(.bordered)
                        Button("Open", action: open)
                            .buttonStyle(.borderedProminent)
                    }
                default:
                    EmptyView()
                }
            }
            .controlSize(.small)
        }
        .padding(.vertical, 4)
    }

    private var isError: Bool {
        if case .failed = job.status { return true }
        return false
    }

    @ViewBuilder private var detail: some View {
        switch job.status {
        case .queued:
            Text("Waiting…")
        case .processing:
            if let progress = job.progress {
                let percent = Int((progress * 100).rounded())
                if let left = job.secondsLeft {
                    Text(verbatim: "\(percent) % · ") + Text("about \(Self.duration(left)) left")
                } else {
                    Text(verbatim: "\(percent) %")
                }
            } else {
                Text("Detecting…")
            }
        case .done:
            let size = (try? job.output?.resourceValues(forKeys: [.fileSizeKey]).fileSize).flatMap { $0 }.map {
                ByteCountFormatter.string(fromByteCount: Int64($0), countStyle: .file)
            } ?? ""
            VStack(alignment: .leading, spacing: 2) {
                Text("^[\(job.count.plates) plate](inflect: true), ^[\(job.count.faces) face](inflect: true) · \(size)")
                if job.savedToPhotos {
                    Label("Saved to Photos", systemImage: "checkmark").labelStyle(.titleAndIcon)
                } else {
                    Text("Not saved to Photos: allow access in Settings, or use Share.")
                }
            }
        case .failed(let message):
            Text(message)
        case .canceled:
            Text("Stopped")
        }
    }

    private static func duration(_ seconds: Double) -> String {
        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = seconds < 60 ? [.second] : seconds < 3600 ? [.minute] : [.hour, .minute]
        formatter.unitsStyle = .short
        return formatter.string(from: max(1, seconds)) ?? ""
    }
}
