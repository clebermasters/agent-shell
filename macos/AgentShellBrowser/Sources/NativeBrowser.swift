import AppKit
import ApplicationServices
import ScreenCaptureKit
import CoreMedia
import CoreVideo
import CoreImage
import ImageIO
import CryptoKit
import Darwin

struct Permissions {
    let screenCapture: Bool
    let accessibility: Bool
    static var current: Permissions { Permissions(screenCapture:CGPreflightScreenCaptureAccess(),accessibility:AXIsProcessTrusted()) }
    var ready: Bool { screenCapture && accessibility }
    var json: [String:Any] { ["screenCapture":screenCapture,"accessibility":accessibility] }
    var code: String { screenCapture ? "ACCESSIBILITY_PERMISSION_REQUIRED" : "SCREEN_CAPTURE_PERMISSION_REQUIRED" }
}

private struct Identity: Codable {
    let chromePid: Int32
    let chromeStart: UInt64
    var windowId: UInt32?
    let profilePath: String
}

private final class FrameOutput: NSObject, SCStreamOutput {
    private let wire: WireOutput
    private let context = CIContext(options:[.cacheIntermediates:false])
    private let lock = NSLock()
    private var active = false
    private var dimensions = PixelSize(width:1280,height:900)
    private var screenRect:WindowRect?
    private var sequence: UInt64 = 0
    private var owner: Identity?
    var invalidated: (() -> Void)?
    init(_ wire:WireOutput) { self.wire = wire }
    func enable(_ value:Bool) { lock.lock(); active = value; lock.unlock() }
    func pin(_ value:Identity) { lock.lock(); owner=value; lock.unlock() }
    var size: PixelSize { lock.lock(); defer { lock.unlock() }; return dimensions }
    var mapping:(size:PixelSize,screen:WindowRect?) { lock.lock();defer { lock.unlock() };return (dimensions,screenRect) }
    func stream(_ stream:SCStream,didOutputSampleBuffer sampleBuffer:CMSampleBuffer,of type:SCStreamOutputType) {
        lock.lock(); let enabled = active;let pin=owner; lock.unlock()
        guard enabled else { return }
        let permission=Permissions.current
        let validWindow:Bool
        var capturedScreen:WindowRect?
        if let pin=pin,let id=pin.windowId,
           let rows=CGWindowListCopyWindowInfo([.optionIncludingWindow],id) as? [[String:Any]],
           let row=rows.first {
            validWindow=(row[kCGWindowNumber as String] as? NSNumber)?.uint32Value==id
                && (row[kCGWindowOwnerPID as String] as? NSNumber)?.int32Value==pin.chromePid
                && ASProcessBirth(pin.chromePid)==pin.chromeStart
            if let bounds=row[kCGWindowBounds as String] as? [String:Any],let rect=CGRect(dictionaryRepresentation:bounds as CFDictionary) {
                capturedScreen=WindowRect(x:rect.minX,y:rect.minY,width:rect.width,height:rect.height)
            }
        } else { validWindow=false }
        guard permission.ready && validWindow else {
            lock.lock();let notify=active;active=false;lock.unlock()
            wire.discardFrames();if notify { invalidated?() };return
        }
        guard enabled, type == .screen, sampleBuffer.isValid,
              let buffer = CMSampleBufferGetImageBuffer(sampleBuffer),
              let rows = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer,createIfNecessary:false) as? [[SCStreamFrameInfo:Any]],
              let metadata = rows.first, let raw = metadata[.status] as? Int,
              SCFrameStatus(rawValue:raw) == .complete else { return }
        if #available(macOS 13.1, *),let value=metadata[.screenRect] as? [String:Any],
           let rect=CGRect(dictionaryRepresentation:value as CFDictionary),let owner=capturedScreen {
            let precise=WindowRect(x:rect.minX,y:rect.minY,width:rect.width,height:rect.height)
            // Capture is deliberately one main window. A larger union could
            // include a child/XPC dialog and is not a safe pointer target.
            if #available(macOS 14.0, *) {
                // Shadows are disabled on this path, so the captured metadata
                // is authoritative even if the window moved after the sample.
                if precise.width>0 && precise.height>0 && [precise.x,precise.y,precise.width,precise.height].allSatisfy({$0.isFinite}) {
                    capturedScreen=precise
                }
            } else if Validation.sameWindow(precise,owner) { capturedScreen=precise }
        }
        autoreleasepool {
            var image = CIImage(cvPixelBuffer:buffer)
            // ScreenCaptureKit attaches the actual content rectangle, excluding
            // letterboxing. Core Image's origin is at the lower left.
            if let dictionary = metadata[.contentRect] as? [String:Any],
               let rect = CGRect(dictionaryRepresentation:dictionary as CFDictionary),
               rect.width > 0, rect.height > 0 {
                let crop = CGRect(x:rect.minX,y:image.extent.height-rect.maxY,width:rect.width,height:rect.height).intersection(image.extent)
                if !crop.isEmpty { image = image.cropped(to:crop) }
            }
            guard image.extent.width > 0, image.extent.height > 0 else { return }
            let scale = min(1,sqrt(Double(Validation.maximumPixels)/Double(image.extent.width*image.extent.height)))
            if scale < 1 { image = image.transformed(by:CGAffineTransform(scaleX:scale,y:scale)) }
            image = image.transformed(by:CGAffineTransform(translationX:-image.extent.minX,y:-image.extent.minY))
            guard let cg = context.createCGImage(image,from:image.extent),
                  cg.width*cg.height <= Validation.maximumPixels else { return }
            let encoded = NSMutableData()
            guard let destination = CGImageDestinationCreateWithData(encoded,"public.jpeg" as CFString,1,nil) else { return }
            CGImageDestinationAddImage(destination,cg,[kCGImageDestinationLossyCompressionQuality:0.72] as CFDictionary)
            guard CGImageDestinationFinalize(destination), encoded.length <= Validation.maximumJpegBytes else { return }
            lock.lock()
            guard active else { lock.unlock(); return }
            sequence &+= 1; let number = sequence
            dimensions = PixelSize(width:cg.width,height:cg.height);screenRect=capturedScreen; lock.unlock()
            wire.frame(["type":"frame","width":cg.width,"height":cg.height,
                        "sequence":number,"data":(encoded as Data).base64EncodedString()])
        }
    }
}

@MainActor
final class NativeBrowser: NSObject, SCStreamDelegate {
    private let config: Configuration
    private let wire: WireOutput
    private let frames: FrameOutput
    private let videoQueue = DispatchQueue(label:"com.agentshell.browser.capture",qos:.userInitiated)
    private var stream: SCStream?
    private var disposition = "native"
    private var guardTask: Task<Void,Never>?
    private var identity: Identity?
    private var launch: Process?
    private var keyCodes = Set<CGKeyCode>()
    private var buttons = Set<Int>()
    private var lastPointer = CGPoint.zero
    private var flags: CGEventFlags = []
    private var inputReceipts: [String:(String,[String:Any])] = [:]
    private var receiptOrder: [String] = []
    private var fallbackSteps: Int?
    private var profileURL: URL { URL(fileURLWithPath:config.userDataDir).resolvingSymlinksInPath() }
    private var recordURL: URL { URL(fileURLWithPath:config.runtimeDir).appendingPathComponent("native-chrome.json") }
    private var chromeURL: URL { URL(fileURLWithPath:config.chromePath).resolvingSymlinksInPath() }
    private var bundleURL: URL { chromeURL.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent() }

    init(config:Configuration,wire:WireOutput) {
        self.config = config; self.wire = wire; self.frames = FrameOutput(wire)
        super.init()
        frames.invalidated = { [weak self] in
            Task { @MainActor in
                guard let browser=self else { return }
                browser.release();await browser.endCapture()
                var value=browser.status();value["type"]="state";browser.wire.event(value)
            }
        }
        if let bytes = try? boundedData(recordURL,maximum:16*1024),
           let old = try? JSONDecoder().decode(Identity.self,from:bytes), old.profilePath == profileURL.path {
            identity = old
        }
    }
    private func privateDirectory(_ url:URL) throws {
        let manager = FileManager.default
        try manager.createDirectory(at:url,withIntermediateDirectories:true,attributes:[.posixPermissions:0o700])
        let attributes = try manager.attributesOfItem(atPath:url.path)
        guard (attributes[.ownerAccountID] as? NSNumber)?.uint32Value == getuid(),
              ((attributes[.posixPermissions] as? NSNumber)?.intValue ?? 0o777) & 0o077 == 0 else {
            throw BrowserFailure("PRIVATE_DIRECTORY_REQUIRED","AgentShell's browser directories must be private to this user")
        }
    }
    private func validateProfile(create:Bool) throws {
        let manager = FileManager.default
        let personal = manager.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Google/Chrome").resolvingSymlinksInPath().path
        guard profileURL.path != personal, !profileURL.path.hasPrefix(personal+"/") else {
            throw BrowserFailure("PERSONAL_PROFILE_REJECTED","Use AgentShell's separate browser profile")
        }
        if create { try privateDirectory(profileURL); try privateDirectory(URL(fileURLWithPath:config.runtimeDir)) }
        let marker = profileURL.appendingPathComponent(".agentshell-native-profile.json")
        if !manager.fileExists(atPath:marker.path), create {
            guard try manager.contentsOfDirectory(atPath:profileURL.path).isEmpty else {
                throw BrowserFailure("UNOWNED_PROFILE","This Chrome profile was not created for AgentShell")
            }
            let value: [String:Any] = ["provider":"macos-native","sessionId":config.sessionId ?? UUID().uuidString]
            try JSONSerialization.data(withJSONObject:value).write(to:marker,options:.atomic)
            try manager.setAttributes([.posixPermissions:0o600],ofItemAtPath:marker.path)
        }
        guard let bytes = try? boundedData(marker,maximum:4096),
              let value = try? JSONSerialization.jsonObject(with:bytes) as? [String:Any],
              value["provider"] as? String == "macos-native",
              let session = value["sessionId"] as? String, !session.isEmpty,
              config.sessionId == nil || config.sessionId == session else {
            throw BrowserFailure("UNOWNED_PROFILE","AgentShell browser profile ownership could not be verified")
        }
    }
    private func saveIdentity() throws {
        guard let identity = identity else { return }
        try JSONEncoder().encode(identity).write(to:recordURL,options:.atomic)
        try FileManager.default.setAttributes([.posixPermissions:0o600],ofItemAtPath:recordURL.path)
    }
    private func verifiedOwner() throws -> Identity {
        try validateProfile(create:false)
        guard let identity = identity, identity.chromePid > 0, identity.chromeStart > 0,
              ASProcessBirth(identity.chromePid) == identity.chromeStart,
              profileURL.path.withCString({ ASProcessUsesProfile(identity.chromePid,$0) }) != 0,
              let app = NSRunningApplication(processIdentifier:identity.chromePid),
              app.bundleURL?.resolvingSymlinksInPath().path == bundleURL.path,
              app.bundleIdentifier?.hasPrefix("com.google.Chrome") == true else {
            throw BrowserFailure("NATIVE_CHROME_CLOSED","The owned Chrome process is no longer running")
        }
        return identity
    }
    private func windowInfo() throws -> (Identity,CGRect,String) {
        let owner = try verifiedOwner()
        guard let id = owner.windowId,
              let list = CGWindowListCopyWindowInfo([.optionIncludingWindow],id) as? [[String:Any]],
              let value = list.first,
              (value[kCGWindowNumber as String] as? NSNumber)?.uint32Value == id,
              (value[kCGWindowOwnerPID as String] as? NSNumber)?.int32Value == owner.chromePid,
              let bounds = value[kCGWindowBounds as String] as? [String:Any],
              let rect = CGRect(dictionaryRepresentation:bounds as CFDictionary), rect.width > 0,rect.height > 0 else {
            throw BrowserFailure("NATIVE_CHROME_WINDOW_CLOSED","The selected owned Chrome window is no longer open")
        }
        return (owner,rect,value[kCGWindowName as String] as? String ?? "")
    }
    func status() -> [String:Any] {
        let permission = Permissions.current
        var result: [String:Any] = ["state":"native-closed","permissions":permission.json]
        if !permission.ready { result["state"]="permission-required";result["code"]=permission.code }
        if let id = identity {
            result["chromePid"]=id.chromePid;result["chromeStart"]=id.chromeStart
            if let window = id.windowId { result["windowId"]=window }
        }
        if let (_,rect,_) = try? windowInfo() {
            if permission.ready { result["state"] = stream == nil ? disposition : "ready" }
            let size = frames.size
            result["width"] = stream == nil ? Int(rect.width) : size.width
            result["height"] = stream == nil ? Int(rect.height) : size.height
        }
        return result
    }
    private func selectWindow() async throws -> SCWindow {
        let owner = try verifiedOwner()
        let contents = try await SCShareableContent.excludingDesktopWindows(true,onScreenWindowsOnly:false)
        let candidates = contents.windows.filter { window in
            window.owningApplication?.processID == owner.chromePid && window.windowLayer == 0
                && window.frame.width >= 320 && window.frame.height >= 200
        }
        if let pinned = owner.windowId {
            guard let window = candidates.first(where:{$0.windowID == pinned}) else {
                throw BrowserFailure("NATIVE_CHROME_WINDOW_CLOSED","The selected Chrome window was closed")
            }
            return window
        }
        guard !candidates.isEmpty else { throw BrowserFailure("NATIVE_CHROME_WINDOW_PENDING","Waiting for AgentShell's Chrome window") }
        guard candidates.count == 1, let window = candidates.first else {
            throw BrowserFailure("AMBIGUOUS_CHROME_WINDOW","Open exactly one window in AgentShell's Chrome profile")
        }
        identity?.windowId = window.windowID;try saveIdentity()
        return window
    }
    private func captureConfiguration(_ size:PixelSize) -> SCStreamConfiguration {
        let result = SCStreamConfiguration()
        result.width=size.width;result.height=size.height
        result.minimumFrameInterval=CMTime(value:1,timescale:8)
        result.queueDepth=3;result.pixelFormat=kCVPixelFormatType_32BGRA
        result.showsCursor=true;result.capturesAudio=false
        if #available(macOS 14.0, *) { result.ignoreShadowsSingleWindow=true }
        return result
    }
    private func beginCapture() async throws {
        if stream != nil { return }
        let window = try await selectWindow()
        let size = try Validation.size(max(320,Int(window.frame.width)),max(240,Int(window.frame.height)))
        let value = SCStream(filter:SCContentFilter(desktopIndependentWindow:window),
            configuration:captureConfiguration(size),delegate:self)
        try value.addStreamOutput(frames,type:.screen,sampleHandlerQueue:videoQueue)
        frames.pin(try verifiedOwner())
        frames.enable(true)
        do { try await value.startCapture();stream=value;watchOwner() }
        catch { frames.enable(false);throw error }
    }
    private func endCapture() async {
        guardTask?.cancel();guardTask=nil
        frames.enable(false);wire.discardFrames()
        let previous = stream;stream=nil
        if let previous = previous { try? await previous.stopCapture() }
    }
    private func watchOwner() {
        guardTask?.cancel()
        guardTask=Task { @MainActor [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(nanoseconds:1_000_000_000) } catch { return }
                guard let browser=self,browser.stream != nil else { return }
                if !Permissions.current.ready || (try? browser.windowInfo()) == nil {
                    browser.release();await browser.endCapture()
                    var value=browser.status();value["type"]="state";browser.wire.event(value)
                    return
                }
            }
        }
    }
    func start() async throws -> [String:Any] {
        disposition="native"
        guard Permissions.current.ready else { return status() }
        try validateProfile(create:true)
        let existingOwner=try? verifiedOwner()
        if existingOwner != nil && (try? windowInfo()) == nil {
            // Retarget only for an explicit start, after checking the pinned
            // profile/process. An ongoing stream never follows another window.
            await endCapture();identity?.windowId=nil;try saveIdentity()
        }
        if existingOwner == nil {
            guard FileManager.default.isExecutableFile(atPath:chromeURL.path),
                  Bundle(url:bundleURL)?.bundleIdentifier?.hasPrefix("com.google.Chrome") == true else {
                throw BrowserFailure("CHROME_NOT_INSTALLED","Install native Google Chrome or configure its executable path")
            }
            let process = Process();process.executableURL=chromeURL
            var arguments = ["--no-first-run","--no-default-browser-check","--user-data-dir=\(profileURL.path)","--new-window"]
            if let profile = config.profileDirectory { arguments.append("--profile-directory=\(profile)") }
            arguments.append(config.startUrl);process.arguments=arguments
            process.standardInput=FileHandle.nullDevice;process.standardOutput=FileHandle.nullDevice;process.standardError=FileHandle.nullDevice
            try process.run();launch=process
            let deadline=Date().addingTimeInterval(10)
            while Date()<deadline {
                let birth=ASProcessBirth(process.processIdentifier)
                if process.isRunning, birth>0, NSRunningApplication(processIdentifier:process.processIdentifier)?.bundleURL != nil {
                    identity=Identity(chromePid:process.processIdentifier,chromeStart:birth,windowId:nil,profilePath:profileURL.path)
                    try saveIdentity();break
                }
                if !process.isRunning { throw BrowserFailure("OWNED_PROFILE_IN_USE","AgentShell's profile is already open without a verifiable owner") }
                try await Task.sleep(nanoseconds:100_000_000)
            }
        } else if identity?.windowId == nil {
            let contents=try await SCShareableContent.excludingDesktopWindows(true,onScreenWindowsOnly:false)
            let count=contents.windows.filter { $0.owningApplication?.processID==existingOwner?.chromePid && $0.windowLayer==0
                && $0.frame.width>=320 && $0.frame.height>=200 }.count
            if count==0 {
                // Chromium forwards this request to the already-verified owned
                // profile process; the short launcher PID is never adopted.
                let request=Process();request.executableURL=chromeURL
                var arguments=["--no-first-run","--no-default-browser-check","--user-data-dir=\(profileURL.path)","--new-window"]
                if let profile=config.profileDirectory { arguments.append("--profile-directory=\(profile)") }
                arguments.append(config.startUrl);request.arguments=arguments
                request.standardInput=FileHandle.nullDevice;request.standardOutput=FileHandle.nullDevice;request.standardError=FileHandle.nullDevice
                try request.run();launch=request
            }
        }
        let deadline=Date().addingTimeInterval(10)
        while true {
            do { try await beginCapture();break }
            catch let error as BrowserFailure where error.code == "NATIVE_CHROME_WINDOW_PENDING" && Date()<deadline {
                try await Task.sleep(nanoseconds:150_000_000)
            }
        }
        return status()
    }
    nonisolated func stream(_ stream:SCStream,didStopWithError error:Error) {
        Task { @MainActor [weak self] in
            guard let browser=self else { return }
            browser.release();await browser.endCapture()
            var value=browser.status();value["type"]="state";value["code"]="CAPTURE_INTERRUPTED"
            browser.wire.event(value)
        }
    }

    private func axValue(_ element:AXUIElement,_ name:CFString) -> CFTypeRef? {
        var value:CFTypeRef?
        return AXUIElementCopyAttributeValue(element,name,&value) == .success ? value : nil
    }
    private func axPoint(_ element:AXUIElement,_ name:CFString,_ type:AXValueType) -> CGPoint? {
        guard let raw=axValue(element,name), CFGetTypeID(raw)==AXValueGetTypeID() else { return nil }
        var point=CGPoint.zero
        return AXValueGetValue(unsafeBitCast(raw,to:AXValue.self),type,&point) ? point : nil
    }
    private func axSize(_ element:AXUIElement) -> CGSize? {
        guard let raw=axValue(element,kAXSizeAttribute as CFString),CFGetTypeID(raw)==AXValueGetTypeID() else { return nil }
        var size=CGSize.zero
        return AXValueGetValue(unsafeBitCast(raw,to:AXValue.self),.cgSize,&size) ? size : nil
    }
    private func focus() throws -> (Identity,CGRect,AXUIElement) {
        guard Permissions.current.ready else { throw BrowserFailure(Permissions.current.code,"Grant AgentShell Browser Screen Recording and Accessibility access") }
        let (owner,rect,title)=try windowInfo()
        let application=AXUIElementCreateApplication(owner.chromePid)
        guard let windows=axValue(application,kAXWindowsAttribute as CFString) as? [AXUIElement] else {
            throw BrowserFailure("ACCESSIBILITY_UNAVAILABLE","Chrome windows are not accessible")
        }
        let sameGeometry=windows.filter { window in
            guard let point=axPoint(window,kAXPositionAttribute as CFString,.cgPoint),let size=axSize(window) else { return false }
            return abs(point.x-rect.minX)<3 && abs(point.y-rect.minY)<3 && abs(size.width-rect.width)<3 && abs(size.height-rect.height)<3
        }
        // Public AX APIs do not expose CGWindowID. Require a unique matching
        // native frame; use a matching title only to disambiguate overlaps.
        let matching=sameGeometry.count==1 ? sameGeometry : sameGeometry.filter {
            !title.isEmpty && (axValue($0,kAXTitleAttribute as CFString) as? String) == title
        }
        guard matching.count==1,let window=matching.first else { throw BrowserFailure("AMBIGUOUS_ACCESSIBILITY_WINDOW","The selected Chrome window cannot be focused safely") }
        _=NSRunningApplication(processIdentifier:owner.chromePid)?.activate(options:[.activateIgnoringOtherApps])
        _=AXUIElementPerformAction(window,kAXRaiseAction as CFString)
        _=AXUIElementSetAttributeValue(application,kAXFocusedWindowAttribute as CFString,window)
        _=AXUIElementSetAttributeValue(window,kAXMainAttribute as CFString,kCFBooleanTrue)
        guard let focused=axValue(application,kAXFocusedWindowAttribute as CFString),CFEqual(focused,window),
              ASProcessBirth(owner.chromePid)==owner.chromeStart else {
            throw BrowserFailure("FOCUS_NOT_CONFIRMED","Chrome did not confirm focus for the selected window")
        }
        return (owner,rect,window)
    }
    private func inputOwner() throws -> (Identity,CGRect,AXUIElement) {
        guard stream != nil else { throw BrowserFailure("CAPTURE_PAUSED","Use the phone viewer before sending input") }
        return try focus()
    }
    private func postKey(_ code:CGKeyCode,_ down:Bool,_ owner:Identity,_ eventFlags:CGEventFlags?=nil) throws {
        guard ASProcessBirth(owner.chromePid)==owner.chromeStart,
              let event=CGEvent(keyboardEventSource:nil,virtualKey:code,keyDown:down) else {
            throw BrowserFailure("INPUT_OWNER_CHANGED","Chrome changed before input was delivered")
        }
        event.flags=eventFlags ?? flags;event.postToPid(owner.chromePid)
    }
    private func keyCode(_ keysym:Int) -> CGKeyCode? {
        let special:[Int:CGKeyCode]=[65288:51,65289:48,65293:36,65307:53,65535:117,65360:115,65367:119,
            65361:123,65362:126,65363:124,65364:125,65365:116,65366:121,65421:76,
            65505:56,65506:60,65507:59,65508:62,65513:58,65514:61,65511:55,65512:54,65515:55,65516:54]
        if let code=special[keysym] { return code }
        let function:[CGKeyCode]=[122,120,99,118,96,97,98,100,101,109,103,111]
        if (65470...65481).contains(keysym) { return function[keysym-65470] }
        let letters:[Character:CGKeyCode]=["a":0,"s":1,"d":2,"f":3,"h":4,"g":5,"z":6,"x":7,"c":8,"v":9,"b":11,
            "q":12,"w":13,"e":14,"r":15,"y":16,"t":17,"1":18,"2":19,"3":20,"4":21,"6":22,"5":23,"=":24,"9":25,
            "7":26,"-":27,"8":28,"0":29,"]":30,"o":31,"u":32,"[":33,"i":34,"p":35,"l":37,"j":38,"'":39,
            "k":40,";":41,"\\":42,",":43,"/":44,"n":45,"m":46,".":47," ":49,"`":50]
        guard let scalar=UnicodeScalar(keysym),let character=String(scalar).lowercased().first else { return nil }
        return letters[character]
    }
    private func updateFlags() {
        flags=[]
        if !keyCodes.isDisjoint(with:[56,60]) { flags.insert(.maskShift) }
        if !keyCodes.isDisjoint(with:[59,62]) { flags.insert(.maskControl) }
        if !keyCodes.isDisjoint(with:[58,61]) { flags.insert(.maskAlternate) }
        if !keyCodes.isDisjoint(with:[55,54]) { flags.insert(.maskCommand) }
    }
    func release() {
        let owner=try? verifiedOwner()
        flags=[]
        if let owner=owner {
            for code in keyCodes { try? postKey(code,false,owner,[]) }
            for button in buttons { try? sendMouse(kind:"up",button:button,point:lastPointer,owner:owner) }
        }
        keyCodes.removeAll();buttons.removeAll();flags=[]
    }
    private func sendMouse(kind:String,button:Int,point:CGPoint,owner:Identity) throws {
        guard ASProcessBirth(owner.chromePid)==owner.chromeStart else { throw BrowserFailure("INPUT_OWNER_CHANGED","Chrome changed before pointer delivery") }
        let mouseButton:CGMouseButton=button==2 ? .right : (button==1 ? .center : .left)
        let type:CGEventType
        switch kind {
        case "down":type=button==2 ? .rightMouseDown : (button==1 ? .otherMouseDown : .leftMouseDown)
        case "up":type=button==2 ? .rightMouseUp : (button==1 ? .otherMouseUp : .leftMouseUp)
        default:type=buttons.contains(0) ? .leftMouseDragged : (buttons.contains(2) ? .rightMouseDragged : (buttons.contains(1) ? .otherMouseDragged : .mouseMoved))
        }
        guard let event=CGEvent(mouseEventSource:nil,mouseType:type,mouseCursorPosition:point,mouseButton:mouseButton) else { throw BrowserFailure("POINTER_UNAVAILABLE","Could not create the pointer event") }
        event.flags=flags
        if kind=="down" || kind=="up" { event.setIntegerValueField(.mouseEventClickState,value:1) }
        event.postToPid(owner.chromePid)
    }
    private func pointer(_ command:Command) throws {
        let raw=command.raw
        guard let kind=raw["kind"] as? String,["move","down","up","scroll"].contains(kind) else { throw BrowserFailure("INVALID_POINTER","Unsupported pointer event") }
        let button=try Validation.integer(raw["button"] ?? 0,"button")
        guard (0...2).contains(button) else { throw BrowserFailure("INVALID_POINTER","Unsupported mouse button") }
        let x=try Validation.number(raw["x"],"x"),y=try Validation.number(raw["y"],"y")
        let (owner,rect,_)=try inputOwner();let mapping=frames.mapping
        guard let screen=mapping.screen,Validation.sameWindow(screen,WindowRect(x:rect.minX,y:rect.minY,width:rect.width,height:rect.height)) else {
            throw BrowserFailure("FRAME_UPDATING","Wait for the moved or resized Chrome frame before pointer input")
        }
        if raw["frameWidth"] != nil || raw["frameHeight"] != nil {
            guard try Validation.integer(raw["frameWidth"],"frameWidth")==mapping.size.width,
                  try Validation.integer(raw["frameHeight"],"frameHeight")==mapping.size.height else {
                throw BrowserFailure("FRAME_UPDATING","The displayed frame changed before pointer input")
            }
        }
        let mapped=try Validation.point(x,y,frame:mapping.size,window:screen)
        lastPointer=CGPoint(x:mapped.0,y:mapped.1)
        if kind=="scroll" {
            let dx=try Validation.number(raw["deltaX"] ?? 0,"deltaX"),dy=try Validation.number(raw["deltaY"] ?? 0,"deltaY")
            guard abs(dx)<=10000,abs(dy)<=10000,
                  let event=CGEvent(scrollWheelEvent2Source:nil,units:.pixel,wheelCount:2,wheel1:Int32(-dy),wheel2:Int32(-dx),wheel3:0) else {
                throw BrowserFailure("INVALID_POINTER","Scroll delta exceeds the supported range")
            }
            guard ASProcessBirth(owner.chromePid)==owner.chromeStart else { throw BrowserFailure("INPUT_OWNER_CHANGED","Chrome changed before scroll delivery") }
            event.location=lastPointer;event.flags=flags;event.postToPid(owner.chromePid)
        } else {
            try sendMouse(kind:kind,button:button,point:lastPointer,owner:owner)
            if kind=="down" { buttons.insert(button) };if kind=="up" { buttons.remove(button) }
        }
    }
    private func key(_ command:Command) throws {
        let keysym=try Validation.integer(command.raw["keysym"],"keysym")
        guard let down=command.raw["down"] as? Bool,let code=keyCode(keysym) else { throw BrowserFailure("UNSUPPORTED_KEY","Use text input for this Unicode key") }
        let (owner,_,_)=try inputOwner()
        if down { keyCodes.insert(code) } else { keyCodes.remove(code) };updateFlags()
        try postKey(code,down,owner)
    }
    private func shortcut(_ code:CGKeyCode,_ owner:Identity) throws {
        try postKey(55,true,owner,.maskCommand);try postKey(code,true,owner,.maskCommand)
        try postKey(code,false,owner,.maskCommand);try postKey(55,false,owner,[])
    }
    private func paste(_ text:String) async throws {
        let (owner,_,_)=try inputOwner();release()
        let board=NSPasteboard.general
        var originals:[NSPasteboardItem]=[];var bytes=0
        for item in board.pasteboardItems ?? [] {
            let copy=NSPasteboardItem()
            for type in item.types {
                guard let data=item.data(forType:type) else { continue }
                bytes+=data.count
                guard bytes<=8*1024*1024 else { throw BrowserFailure("CLIPBOARD_TOO_LARGE","Clipboard is too large to preserve safely") }
                copy.setData(data,forType:type)
            }
            originals.append(copy)
        }
        board.clearContents();board.setString(text,forType:.string);let written=board.changeCount
        defer {
            release()
            if board.changeCount==written { board.clearContents();if !originals.isEmpty { board.writeObjects(originals) } }
        }
        _=try focus();try shortcut(9,owner)
        try await Task.sleep(nanoseconds:350_000_000)
    }
    private func unicode(_ text:String) throws {
        let (owner,_,_)=try inputOwner();release()
        for units in Validation.unicodeChunks(text) {
            _=try focus()
            guard ASProcessBirth(owner.chromePid)==owner.chromeStart,
                  let down=CGEvent(keyboardEventSource:nil,virtualKey:0,keyDown:true),
                  let up=CGEvent(keyboardEventSource:nil,virtualKey:0,keyDown:false) else {
                throw BrowserFailure("INPUT_OWNER_CHANGED","Chrome changed before Unicode input")
            }
            units.withUnsafeBufferPointer { buffer in
                down.keyboardSetUnicodeString(stringLength:buffer.count,unicodeString:buffer.baseAddress)
                up.keyboardSetUnicodeString(stringLength:buffer.count,unicodeString:buffer.baseAddress)
            }
            down.flags=[];up.flags=[];down.postToPid(owner.chromePid)
            if ASProcessBirth(owner.chromePid)==owner.chromeStart { up.postToPid(owner.chromePid) }
        }
    }
    private func resize(_ command:Command) async throws -> [String:Any] {
        let size=try Validation.size(Validation.integer(command.raw["width"],"width"),Validation.integer(command.raw["height"],"height"))
        let density=try Validation.integer(command.raw["contentDensity"] ?? 100,"contentDensity")
        let steps=try Validation.zoomSteps(density)
        let (owner,_,window)=try inputOwner();release()
        var requested=CGSize(width:CGFloat(size.width),height:CGFloat(size.height))
        guard let value=AXValueCreate(.cgSize,&requested),
              AXUIElementSetAttributeValue(window,kAXSizeAttribute as CFString,value) == .success else {
            throw BrowserFailure("RESIZE_UNAVAILABLE","Chrome did not accept the requested window size")
        }
        try await Task.sleep(nanoseconds:100_000_000)
        guard let actual=axSize(window),actual.width>0,actual.height>0 else { throw BrowserFailure("RESIZE_UNAVAILABLE","Chrome window size could not be verified") }
        let clamped=abs(actual.width-requested.width)>3 || abs(actual.height-requested.height)>3
        let applied=clamped ? steps : 0
        if (fallbackSteps != nil && fallbackSteps != applied) || (applied != 0 && fallbackSteps != applied) {
            _=try focus();try shortcut(29,owner) // Cmd+0, then Chrome's page zoom steps.
            for _ in 0..<abs(applied) { try shortcut(applied>0 ? 24 : 27,owner) }
            fallbackSteps=applied
        }
        let actualSize=try Validation.size(max(320,Int(actual.width)),max(240,Int(actual.height)))
        if let stream=stream { try await stream.updateConfiguration(captureConfiguration(actualSize)) }
        var result=status();result["resolutionClamped"]=clamped;result["contentDensity"]=density;result["pageZoomFallback"]=clamped
        return result
    }
    func stop() async -> [String:Any] { disposition="stopped";release();await endCapture();return status() }
    func handle(_ command:Command) async -> [String:Any] {
        let receiptKey=(command.id is String ? "s:" : "n:")+String(describing:command.id)
        var textDigest:String?
        do {
            if command.type=="text" {
                let text=try Validation.text(command.raw["text"])
                let method=try Validation.textMethod(command.raw["method"])
                let digest=SHA256.hash(data:Data((method+"\0"+text).utf8)).map { String(format:"%02x",$0) }.joined();textDigest=digest
                if let previous=inputReceipts[receiptKey] {
                    guard previous.0==digest else {
                        return ["id":command.id,"ok":false,"error":BrowserFailure("INPUT_ID_CONFLICT","This input ID belongs to different text").json]
                    }
                    return previous.1
                }
            }
            let result:[String:Any]
            switch command.type {
            case "status": result=status()
            case "start": result=try await start()
            case "stop": result=await stop()
            case "release": release();result=status()
            case "pointer": try pointer(command);result=["delivered":true]
            case "key": try key(command);result=["delivered":true]
            case "text":
                let text=try Validation.text(command.raw["text"])
                if try Validation.textMethod(command.raw["method"])=="unicode" { try unicode(text) }
                else { try await paste(text) }
                result=["delivered":true]
            case "resize": result=try await resize(command)
            case "handoff":
                guard let destination=command.raw["destination"] as? String,["desktop","phone"].contains(destination) else { throw BrowserFailure("INVALID_HANDOFF","Choose desktop or phone") }
                if destination=="desktop" { _=try focus();release();await endCapture();disposition="native";result=status() }
                else { result=try await start() }
            default: throw BrowserFailure("UNKNOWN_COMMAND","Unsupported native browser command")
            }
            let reply:[String:Any]=["id":command.id,"ok":true,"result":result]
            if let digest=textDigest { remember(receiptKey,digest,reply) }
            return reply
        } catch {
            release()
            let failure=(error as? BrowserFailure) ?? BrowserFailure("NATIVE_BROWSER_ERROR","The native Chrome operation failed")
            let reply:[String:Any]=["id":command.id,"ok":false,"error":failure.json]
            if let digest=textDigest { remember(receiptKey,digest,reply) }
            return reply
        }
    }
    private func remember(_ id:String,_ digest:String,_ reply:[String:Any]) {
        if inputReceipts[id]==nil { receiptOrder.append(id) }
        inputReceipts[id]=(digest,reply)
        while receiptOrder.count>64 { inputReceipts.removeValue(forKey:receiptOrder.removeFirst()) }
    }
}
