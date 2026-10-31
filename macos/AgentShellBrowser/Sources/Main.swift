import AppKit
import ApplicationServices
import Darwin

@main
struct AgentShellBrowserMain {
    @MainActor
    static func main() async {
        signal(SIGPIPE,SIG_IGN)
        let arguments=Array(CommandLine.arguments.dropFirst())
        let wire=WireOutput()
        do {
            if arguments.contains("--self-test") {
                let checks=try PureTests.run()
                guard ASProcessBirth(getpid())>0 else { throw BrowserFailure("SELF_TEST_FAILED","Native process generation unavailable") }
                await wire.reply(["ok":true,"checks":checks,"processIdentity":true])
                return
            }
            if arguments.contains("--check") {
                let permissions=Permissions.current
                var status:[String:Any]=["available":true,"state":permissions.ready ? "native-closed" : "permission-required",
                    "permissions":permissions.json]
                if !permissions.ready { status["code"]=permissions.code }
                await wire.reply(status)
                return
            }
            if arguments.contains("--setup") {
                _=NSApplication.shared
                NSApplication.shared.setActivationPolicy(.regular)
                NSApplication.shared.activate(ignoringOtherApps:true)
                _=CGRequestScreenCaptureAccess()
                let prompt=[kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String:true] as CFDictionary
                _=AXIsProcessTrustedWithOptions(prompt)
                let alert=NSAlert()
                alert.messageText="AgentShell Browser permissions"
                alert.informativeText="Enable AgentShell Browser in System Settings → Privacy & Security → Screen Recording and Accessibility. Then close and reopen AgentShell Browser. Only AgentShell's separate Chrome profile is shared."
                alert.addButton(withTitle:"OK");alert.runModal()
                await wire.reply(["permissions":Permissions.current.json])
                return
            }
            guard arguments.contains("--stdio"), let flag=arguments.firstIndex(of:"--config"),arguments.indices.contains(flag+1) else {
                throw BrowserFailure("USAGE","Use --check, --setup, --self-test, or --config PATH --stdio")
            }
            let configURL=URL(fileURLWithPath:arguments[flag+1])
            let config=try JSONDecoder().decode(Configuration.self,from:boundedData(configURL,maximum:Validation.maximumCommandBytes))
            _=NSApplication.shared
            NSApplication.shared.setActivationPolicy(.accessory)
            let browser=NativeBrowser(config:config,wire:wire)
            var signals:[DispatchSourceSignal]=[]
            for number in [SIGTERM,SIGINT] {
                signal(number,SIG_IGN)
                let source=DispatchSource.makeSignalSource(signal:number,queue:.main)
                source.setEventHandler { Task { @MainActor in _=await browser.stop();exit(0) } }
                source.resume();signals.append(source)
            }
            let reader=CommandReader()
            await Task.detached {
                do {
                    while let bytes=try reader.next() {
                        do {
                            let command=try Command(bytes)
                            await wire.reply(await browser.handle(command))
                        } catch {
                            let failure=(error as? BrowserFailure) ?? BrowserFailure("INVALID_COMMAND","Could not decode the JSON command")
                            await wire.reply(["id":NSNull(),"ok":false,"error":failure.json])
                        }
                    }
                } catch {
                    let failure=(error as? BrowserFailure) ?? BrowserFailure("STDIO_CLOSED","Native command input closed")
                    await wire.reply(["id":NSNull(),"ok":false,"error":failure.json])
                }
                _=await browser.stop()
            }.value
            withExtendedLifetime(signals) {}
        } catch {
            let failure=(error as? BrowserFailure) ?? BrowserFailure("INVALID_CONFIG","Could not load the native browser configuration")
            await wire.reply(["ok":false,"error":failure.json])
            exit(1)
        }
    }
}
