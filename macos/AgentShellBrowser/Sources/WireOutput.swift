import Foundation

/// At most one encoded frame is waiting, plus the frame being written. Input
/// processing waits for its reply to drain, so a stalled pipe cannot grow a queue.
final class WireOutput {
    private let queue = DispatchQueue(label:"com.agentshell.browser.stdout")
    private let lock = NSLock()
    private var latest: Data?
    private var scheduled = false
    private var closed = false
    func discardFrames() { lock.lock(); latest = nil; lock.unlock() }
    func event(_ object:[String:Any]) { frame(object) }
    func frame(_ object: [String: Any]) {
        guard let bytes = try? JSONSerialization.data(withJSONObject:object), bytes.count < Validation.maximumFrameBytes else { return }
        lock.lock(); defer { lock.unlock() }
        guard !closed else { return }
        latest = bytes
        if !scheduled { scheduled = true; queue.async { self.drainFrame() } }
    }
    private func write(_ bytes: Data) {
        do {
            var line = bytes; line.append(10)
            try FileHandle.standardOutput.write(contentsOf:line)
        } catch { lock.lock(); closed = true; latest = nil; lock.unlock() }
    }
    private func drainFrame() {
        lock.lock(); let bytes = latest; latest = nil; lock.unlock()
        if let bytes = bytes { write(bytes) }
        lock.lock()
        if latest != nil && !closed { lock.unlock(); queue.async { self.drainFrame() } }
        else { scheduled = false; lock.unlock() }
    }
    func reply(_ object: [String: Any]) async {
        guard let data = try? JSONSerialization.data(withJSONObject:object) else { return }
        await withCheckedContinuation { (done:CheckedContinuation<Void,Never>) in
            queue.async { self.write(data); done.resume() }
        }
    }
}

final class CommandReader {
    private var buffered = Data()
    func next() throws -> Data? {
        while true {
            if let newline = buffered.firstIndex(of:10) {
                let line = Data(buffered[..<newline]); buffered.removeSubrange(...newline)
                guard line.count <= Validation.maximumCommandBytes else { throw BrowserFailure("COMMAND_TOO_LARGE","Command exceeds 64 KiB") }
                if line.isEmpty { continue }; return line
            }
            guard buffered.count <= Validation.maximumCommandBytes else { throw BrowserFailure("COMMAND_TOO_LARGE","Command exceeds 64 KiB") }
            guard let chunk = try FileHandle.standardInput.read(upToCount:4096), !chunk.isEmpty else {
                if buffered.isEmpty { return nil }
                throw BrowserFailure("INCOMPLETE_COMMAND","Command must end with a newline")
            }
            buffered.append(chunk)
        }
    }
}
