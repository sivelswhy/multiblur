import Photos
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// One photo or video in the list.
@MainActor
final class Job: ObservableObject, Identifiable {
    enum Status: Equatable {
        case queued, processing, done, failed(String), canceled
    }

    let id = UUID()
    let source: URL
    let isVideo: Bool
    @Published var status = Status.queued
    /// 0…1 for videos; nil while unknown.
    @Published var progress: Double?
    @Published var secondsLeft: Double?
    @Published var thumbnail: UIImage?
    @Published var output: URL?
    @Published var count = DetectionCount()
    /// Whether the result reached the photo library (false when access was refused).
    @Published var savedToPhotos = false
    var task: Task<Void, Never>?

    var name: String { source.lastPathComponent }

    init(source: URL) {
        self.source = source
        isVideo = UTType(filenameExtension: source.pathExtension)?.conforms(to: .movie) ?? false
    }
}

/// The app's state: settings, the detection engine and the list of files, processed one at a time.
@MainActor
final class Store: ObservableObject {
    enum EngineState: Equatable { case loading, ready, failed(String) }

    @Published var options = Options.load() {
        didSet { options.save() }
    }
    @Published private(set) var engineState = EngineState.loading
    @Published private(set) var jobs: [Job] = []

    private var engine: BlurEngine?
    private var running = false

    /// Files picked or shared in are copied here, then deleted with their job.
    nonisolated static let inbox = FileManager.default.temporaryDirectory.appendingPathComponent("Inbox", isDirectory: true)
    nonisolated static let outbox = FileManager.default.temporaryDirectory.appendingPathComponent("Anonymized", isDirectory: true)

    var busy: Bool { jobs.contains { $0.status == .queued || $0.status == .processing } }

    init() {
        for folder in [Self.inbox, Self.outbox] {
            try? FileManager.default.removeItem(at: folder)
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        }
        loadEngine()
    }

    func loadEngine() {
        engineState = .loading
        let compute = options.compute
        Task {
            do {
                let engine = try await Task.detached(priority: .userInitiated) { try BlurEngine(compute: compute) }.value
                self.engine = engine
                engineState = .ready
                runQueue()
            } catch {
                engineState = .failed(error.localizedDescription)
            }
        }
    }

    /// Copies a file into the inbox under its own name, made unique.
    nonisolated static func importCopy(of url: URL) throws -> URL {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        var destination = inbox.appendingPathComponent(url.lastPathComponent)
        var copy = 1
        while FileManager.default.fileExists(atPath: destination.path) {
            destination = inbox.appendingPathComponent("\(url.deletingPathExtension().lastPathComponent) (\(copy)).\(url.pathExtension)")
            copy += 1
        }
        try FileManager.default.copyItem(at: url, to: destination)
        return destination
    }

    func add(_ urls: [URL]) {
        for url in urls {
            let job = Job(source: url)
            jobs.append(job)
            if !job.isVideo {
                Task.detached(priority: .utility) {
                    let thumbnail = UIImage(contentsOfFile: url.path)?.preparingThumbnail(of: CGSize(width: 240, height: 240))
                    await MainActor.run { if job.status == .queued { job.thumbnail = thumbnail } }
                }
            }
        }
        runQueue()
    }

    func remove(_ job: Job) {
        job.task?.cancel()
        jobs.removeAll { $0.id == job.id }
        try? FileManager.default.removeItem(at: job.source)
        if let output = job.output { try? FileManager.default.removeItem(at: output) }
    }

    func stop(_ job: Job) {
        job.task?.cancel()
    }

    func clearFinished() {
        for job in jobs where job.status != .queued && job.status != .processing { remove(job) }
    }

    private func runQueue() {
        guard !running, let engine, let job = jobs.first(where: { $0.status == .queued }) else {
            UIApplication.shared.isIdleTimerDisabled = busy
            return
        }
        running = true
        // Processing stops when the phone locks: keep the screen on meanwhile.
        UIApplication.shared.isIdleTimerDisabled = true
        job.task = Task {
            await process(job, with: engine)
            running = false
            runQueue()
        }
    }

    private func process(_ job: Job, with engine: BlurEngine) async {
        // Settings are read when a file starts, so changing them affects the files that follow.
        var used = options
        used.livePreview = true
        if !used.maskPlates && !used.maskFaces {
            job.status = .failed(String(localized: "Choose what to mask: plates, faces or both."))
            return
        }
        job.status = .processing
        let destination = BlurEngine.outputURL(for: job.source, folder: Self.outbox)
        let started = Date()
        // A little extra time to finish if the app goes to the background.
        let background = UIApplication.shared.beginBackgroundTask(withName: "MultiBlur export")
        defer { UIApplication.shared.endBackgroundTask(background) }
        do {
            let count = try await engine.process(job.source, to: destination, options: used, progress: { fraction in
                Task { @MainActor in
                    guard job.isVideo else { return }
                    job.progress = fraction
                    let elapsed = Date().timeIntervalSince(started)
                    job.secondsLeft = fraction > 0.02 ? elapsed / fraction * (1 - fraction) : nil
                }
            }, preview: { frame in
                let image = UIImage(cgImage: frame)
                Task { @MainActor in job.thumbnail = image }
            })
            job.count = count
            job.output = destination
            job.progress = 1
            job.status = .done
            job.savedToPhotos = await Self.saveToPhotos(destination, isVideo: job.isVideo)
        } catch is CancellationError {
            job.status = .canceled
        } catch {
            job.status = Task.isCancelled ? .canceled : .failed(error.localizedDescription)
        }
    }

    /// Adds the result to the photo library (add-only access: the app can't read the library).
    private static func saveToPhotos(_ url: URL, isVideo: Bool) async -> Bool {
        guard await PHPhotoLibrary.requestAuthorization(for: .addOnly) == .authorized else { return false }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetCreationRequest.forAsset().addResource(with: isVideo ? .video : .photo, fileURL: url, options: nil)
            }
            return true
        } catch {
            return false
        }
    }

    /// The replacement image is copied into the app's own storage, so it survives restarts.
    func setReplacementImage(from url: URL) {
        let folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let destination = folder.appendingPathComponent("replacement.\(url.pathExtension.isEmpty ? "png" : url.pathExtension)")
        try? FileManager.default.removeItem(at: destination)
        guard (try? FileManager.default.copyItem(at: url, to: destination)) != nil else { return }
        options.replacementImagePath = destination.path
    }
}

/// A photo or video handed over by the photo picker, as a file copied into the inbox.
struct PickedMedia: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { PickedMedia(url: try Store.importCopy(of: $0.file)) }
        FileRepresentation(importedContentType: .image) { PickedMedia(url: try Store.importCopy(of: $0.file)) }
    }
}
