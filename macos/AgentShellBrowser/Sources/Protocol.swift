import Foundation
import CoreFoundation

func boundedData(_ url:URL,maximum:Int) throws -> Data {
    let file=try FileHandle(forReadingFrom:url);defer { try? file.close() }
    let data=try file.read(upToCount:maximum+1) ?? Data()
    guard data.count<=maximum else { throw BrowserFailure("FILE_TOO_LARGE","Native browser metadata exceeds its size limit") }
    return data
}

struct BrowserFailure: Error {
    let code: String
    let message: String
    init(_ code: String, _ message: String) { self.code = code; self.message = message }
    var json: [String: Any] { ["code": code, "message": message] }
}

struct PixelSize: Equatable { let width: Int; let height: Int }
struct WindowRect { let x: Double; let y: Double; let width: Double; let height: Double }

enum Validation {
    static let maximumCommandBytes = 64 * 1024
    static let maximumFrameBytes = 8 * 1024 * 1024
    static let maximumJpegBytes = 5_500_000
    static let maximumPixels = 6_000_000

    static func number(_ value: Any?, _ name: String) throws -> Double {
        guard let value = value as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID(), value.doubleValue.isFinite else {
            throw BrowserFailure("INVALID_INPUT", "\(name) must be a finite number")
        }
        return value.doubleValue
    }
    static func integer(_ value: Any?, _ name: String) throws -> Int {
        let result = try number(value, name)
        guard result.rounded(.towardZero) == result, abs(result) <= 1_000_000_000 else {
            throw BrowserFailure("INVALID_INPUT", "\(name) must be an integer")
        }
        return Int(result)
    }
    static func text(_ value: Any?) throws -> String {
        guard let value = value as? String, !value.isEmpty, value.utf8.count <= 32 * 1024, !value.contains("\0") else {
            throw BrowserFailure("INVALID_INPUT", "Text must contain 1–32768 UTF-8 bytes without NUL")
        }
        return value
    }
    static func size(_ width: Int, _ height: Int) throws -> PixelSize {
        guard (320...8192).contains(width), (240...8192).contains(height) else {
            throw BrowserFailure("INVALID_SIZE", "Browser size must be between 320×240 and 8192×8192")
        }
        let factor = min(1, 4096.0 / Double(width), 4096.0 / Double(height),
                         sqrt(Double(maximumPixels) / (Double(width) * Double(height))))
        return PixelSize(width: max(2, Int(floor(Double(width) * factor / 2)) * 2),
                         height: max(2, Int(floor(Double(height) * factor / 2)) * 2))
    }
    static func point(_ x: Double, _ y: Double, frame: PixelSize, window: WindowRect) throws -> (Double, Double) {
        guard x.isFinite, y.isFinite, frame.width > 0, frame.height > 0,
              x >= 0, y >= 0, x < Double(frame.width), y < Double(frame.height),
              window.x.isFinite, window.y.isFinite, window.width > 0, window.height > 0,
              window.width.isFinite, window.height.isFinite else {
            throw BrowserFailure("INVALID_POINTER", "Pointer must be inside the current browser frame")
        }
        return (window.x + x * window.width / Double(frame.width),
                window.y + y * window.height / Double(frame.height))
    }
    static func zoomSteps(_ value: Int) throws -> Int {
        switch value {
        case 75: return 2
        case 100: return 0
        case 125: return -2
        case 150: return -4
        case 200: return -6
        default: throw BrowserFailure("INVALID_DENSITY", "Unsupported content size")
        }
    }
    static func profileComponent(_ value: String?) throws -> String? {
        guard let value = value else { return nil }
        guard !value.isEmpty, value.utf8.count <= 256, !value.contains("/"),
              !value.contains("\\"), value != ".", value != "..", !value.contains("\0") else {
            throw BrowserFailure("INVALID_PROFILE", "Profile directory must be a single directory name")
        }
        return value
    }
}

struct Command {
    let id: Any
    let type: String
    let raw: [String: Any]
    init(_ bytes: Data) throws {
        guard bytes.count <= Validation.maximumCommandBytes,
              let object = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              let type = object["type"] as? String, type.utf8.count <= 32 else {
            throw BrowserFailure("INVALID_COMMAND", "Expected one bounded JSON command")
        }
        if let id = object["id"] as? String, !id.isEmpty, id.utf8.count <= 128 { self.id = id }
        else if let id = object["id"] as? NSNumber, CFGetTypeID(id) != CFBooleanGetTypeID(),
                id.doubleValue.isFinite, abs(id.doubleValue) <= 9_007_199_254_740_991,
                id.doubleValue.rounded(.towardZero) == id.doubleValue { self.id = id }
        else { throw BrowserFailure("INVALID_COMMAND", "Command ID must be a bounded string or integer") }
        self.type = type; self.raw = object
    }
}

struct Configuration: Decodable {
    let sessionId: String?
    let chromePath: String
    let userDataDir: String
    let profileDirectory: String?
    let startUrl: String
    let runtimeDir: String
    enum CodingKeys: String, CodingKey { case sessionId, chromePath, userDataDir, profileDirectory, startUrl, runtimeDir }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        sessionId = try values.decodeIfPresent(String.self, forKey:.sessionId)
        chromePath = try values.decodeIfPresent(String.self, forKey: .chromePath)
            ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
        userDataDir = try values.decode(String.self, forKey: .userDataDir)
        profileDirectory = try Validation.profileComponent(values.decodeIfPresent(String.self, forKey: .profileDirectory))
        startUrl = try values.decodeIfPresent(String.self, forKey: .startUrl) ?? "about:blank"
        runtimeDir = try values.decode(String.self, forKey: .runtimeDir)
        guard chromePath.hasPrefix("/"), userDataDir.hasPrefix("/"), runtimeDir.hasPrefix("/"),
              [chromePath,userDataDir,runtimeDir].allSatisfy({ !$0.contains("\0") }),
              startUrl == "about:blank" || ["http", "https"].contains(URL(string: startUrl)?.scheme?.lowercased() ?? "") else {
            throw BrowserFailure("INVALID_CONFIG", "Use absolute native paths and an HTTP(S) start URL")
        }
    }
}

enum PureTests {
    static func run() throws -> [String: Bool] {
        var results: [String: Bool] = [:]
        func check(_ name: String, _ condition: Bool) throws {
            guard condition else { throw BrowserFailure("SELF_TEST_FAILED", name) }
            results[name] = true
        }
        let capped = try Validation.size(8192,8192)
        try check("frame_budget_and_aspect", capped.width*capped.height <= Validation.maximumPixels && capped.width == capped.height)
        let point = try Validation.point(500,250,frame: PixelSize(width:1000,height:500),
            window:WindowRect(x:-800,y:40,width:800,height:400))
        try check("retina_and_negative_display_coordinates", point.0 == -400 && point.1 == 240)
        for value in [-1.0,Double.nan,Double.infinity,1000] {
            do { _ = try Validation.point(value,0,frame:PixelSize(width:1000,height:500),window:WindowRect(x:0,y:0,width:1000,height:500))
                throw BrowserFailure("SELF_TEST_FAILED", "Invalid pointer accepted")
            } catch let error as BrowserFailure where error.code == "INVALID_POINTER" { }
        }
        results["invalid_pointer_rejected"] = true
        try check("density_fallback_steps", try [75,100,125,150,200].map(Validation.zoomSteps) == [2,0,-2,-4,-6])
        try check("unicode_text_preserved", try Validation.text("ação 🌈\nκόσμε") == "ação 🌈\nκόσμε")
        do { _ = try Validation.number(true,"width"); throw BrowserFailure("SELF_TEST_FAILED","Boolean coordinate accepted") }
        catch let error as BrowserFailure where error.code == "INVALID_INPUT" { results["boolean_numeric_input_rejected"] = true }
        do { _ = try Validation.profileComponent("../Default"); throw BrowserFailure("SELF_TEST_FAILED","Traversal accepted") }
        catch let error as BrowserFailure where error.code == "INVALID_PROFILE" { results["profile_traversal_rejected"] = true }
        let command = try Command(Data("{\"id\":1,\"type\":\"status\"}".utf8))
        try check("wire_command_decodes", command.type == "status")
        do { _ = try Command(Data(repeating:32,count:Validation.maximumCommandBytes+1)); throw BrowserFailure("SELF_TEST_FAILED","Oversized command accepted") }
        catch let error as BrowserFailure where error.code == "INVALID_COMMAND" { results["command_budget_enforced"] = true }
        return results
    }
}
