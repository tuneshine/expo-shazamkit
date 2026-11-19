import ExpoModulesCore
import ShazamKit

public class ShazamKitModule: Module, ResultHandler {
    private let session = SHSession()
    private var delegate: ShazamDelegate?

    // Replaced on every listen (see startEngine), so a var.
    private var audioEngine = AVAudioEngine()
    private var tapInstalled = false

    private var pendingPromise: Promise?

    private var latestResults = [SHMediaItem]()

    public func definition() -> ModuleDefinition {
        Name("ExpoShazamKit")

        OnCreate {
            delegate = ShazamDelegate(resultHandler: self)
            session.delegate = delegate
        }

        Function("isAvailable") {
            return true
        }

        AsyncFunction("startListening") { (promise: Promise) in
            if pendingPromise != nil {
                promise.reject(SearchInProgressException())
                return
            }

            pendingPromise = promise

            do {
                try findMatch()
            } catch {
                promise.reject(error)
                pendingPromise = nil
            }
        }

        AsyncFunction("addToShazamLibrary") { (promise: Promise) in
            if latestResults.isEmpty {
                promise.resolve(["success": false])
                return
            }

            SHMediaLibrary.default.add(latestResults) { [weak self] error in
                if error != nil {
                    promise.resolve(["success": false])
                }

                self?.latestResults.removeAll()
                promise.resolve(["success": true])
            }
        }

        Function("stopListening") {
            stopListening()
        }

        Function("setDeveloperToken") { (token: String) in
            log.warn("⚠️ Warning: setDeveloperToken called on iOS - developer tokens are not needed on iOS platform")
        }
    }

    func didFind(match: SHMatch) {
        guard let promise = pendingPromise else {
            log.error("Shazam module: promise has been lost")
            stopListening()
            return
        }

        stopListening()

        let items = match.mediaItems.map { item in
            MatchedItem(
                isrc: item.isrc,
                title: item.title,
                artist: item.artist,
                shazamID: item.shazamID,
                appleMusicID: item.appleMusicID,
                appleMusicURL: item.appleMusicURL?.absoluteString,
                artworkURL: item.artworkURL?.absoluteString,
                genres: item.genres,
                webURL: item.webURL?.absoluteString,
                subtitle: item.subtitle,
                videoURL: item.videoURL?.absoluteString,
                explicitContent: item.explicitContent,
                matchOffset: Double(item.matchOffset.description) ?? 0.0
            )
        }

        latestResults = match.mediaItems
        promise.resolve(items)
    }

    func didNotFind(match: SHSignature) {
        guard let promise = pendingPromise else {
            log.error("ExpoShazamKit: promise has been lost")
            return
        }

        promise.reject(NoMatchException())
        stopListening()
    }

    private func findMatch() throws {
        guard !audioEngine.isRunning else { return }
        let audioSession = AVAudioSession.sharedInstance()
        try audioSession.setCategory(.playAndRecord)

        // Ask for the mic before touching the input node. Until access is
        // granted the input can report an empty format (0 Hz, 0 channels;
        // always the case for an iPhone app on a Mac), and AVAudioEngine
        // raises an Objective-C exception for that, which Swift cannot catch:
        // the app aborts.
        audioSession.requestRecordPermission { [weak self] granted in
            guard let self else { return }
            guard granted else {
                self.rejectPending(MicrophonePermissionDeniedException())
                return
            }
            do {
                try self.startEngine(audioSession)
            } catch let error as Exception {
                self.rejectPending(error)
            } catch {
                NSLog("[ExpoShazamKit] could not start listening: \(error)")
                self.rejectPending(FailedToStartAudioEngine())
            }
        }
    }

    private func rejectPending(_ exception: Exception) {
        audioEngine.stop()
        pendingPromise?.reject(exception)
        pendingPromise = nil
    }

    private func stopListening() {
        audioEngine.stop()
        pendingPromise = nil
    }

    // Tap the mic directly. The old graph routed input -> mixer -> outputNode,
    // which played the microphone back out of the device. On iPhone that went
    // to the earpiece (barely audible); on devices without one (iPad, Mac, and
    // iPhones routed to the speaker or an external output) it went to the
    // speaker and fed back. A tap on the input node needs no output
    // connection, so nothing is ever played.
    private func startEngine(_ audioSession: AVAudioSession) throws {
        // An iPhone app on a Mac only gets a microphone in its audio device
        // once the session is active; on iPhone, starting the engine activates
        // it implicitly, so this changes nothing there.
        try audioSession.setActive(true)

        var format = freshInputFormat()
        logAudioState(audioSession, format: format, attempt: 1)

        if !isValid(format), let mic = preferredMic(audioSession) {
            try audioSession.setPreferredInput(mic)
            format = freshInputFormat()
            logAudioState(audioSession, format: format, attempt: 2)
        }

        guard isValid(format) else {
            throw NoAudioInputException()
        }

        audioEngine.inputNode.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, audioTime in
            self?.addAudio(buffer: buffer, audioTime: audioTime)
        }
        tapInstalled = true
        audioEngine.prepare()
        try audioEngine.start()
    }

    // A new engine per listen: an engine builds its I/O unit the first time
    // its input node is touched and keeps it, so one created before the mic
    // was available (or before the input device changed) never sees the mic.
    private func freshInputFormat() -> AVAudioFormat {
        if tapInstalled {
            audioEngine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        audioEngine.stop()
        audioEngine = AVAudioEngine()
        return audioEngine.inputNode.outputFormat(forBus: 0)
    }

    private func isValid(_ format: AVAudioFormat) -> Bool {
        format.sampleRate > 0 && format.channelCount > 0
    }

    private func preferredMic(_ audioSession: AVAudioSession) -> AVAudioSessionPortDescription? {
        let inputs = audioSession.availableInputs ?? []
        return inputs.first { $0.portType == .builtInMic } ?? inputs.first
    }

    private func logAudioState(_ audioSession: AVAudioSession, format: AVAudioFormat, attempt: Int) {
        let route = audioSession.currentRoute
        let available = (audioSession.availableInputs ?? []).map { "\($0.portName) [\($0.portType.rawValue)]" }
        NSLog(
            "[ExpoShazamKit] attempt \(attempt): format=\(format.sampleRate) Hz/\(format.channelCount) ch, "
                + "category=\(audioSession.category.rawValue), inputAvailable=\(audioSession.isInputAvailable), "
                + "routeInputs=\(route.inputs.map { $0.portName }), routeOutputs=\(route.outputs.map { $0.portName }), "
                + "availableInputs=\(available), iOSAppOnMac=\(ProcessInfo.processInfo.isiOSAppOnMac)"
        )
    }

    private func addAudio(buffer: AVAudioPCMBuffer, audioTime: AVAudioTime) {
        session.matchStreamingBuffer(buffer, at: audioTime)
    }
}
