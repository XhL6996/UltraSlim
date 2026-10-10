import SwiftUI
import AppKit
import PhotosUI
import UniformTypeIdentifiers
import LivePhotoCore

@main struct UltraSlimLiveApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate
    var body: some Scene { WindowGroup("UltraSlim Live") { ImportView() }.defaultSize(width:640,height:640) }
}
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) { NSApp.setActivationPolicy(.regular); NSApp.activate(ignoringOtherApps:true) }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication)->Bool { true }
}
@MainActor final class ImportModel: ObservableObject {
    @Published var photo: PHLivePhoto?
    @Published var status="在 Android 成品详情中选择「导出 Apple Live Photo」，然后在这里打开导出的 ZIP。"
    @Published var busy=false
    @Published var imported=false
    @Published var name=""
    private var prepared: PreparedLivePhoto?
    deinit { prepared?.removeTemporaryFiles() }
    func open(_ url: URL) {
        guard !busy else { return }; busy=true; photo=nil; imported=false; name=url.deletingPathExtension().lastPathComponent
        prepared?.removeTemporaryFiles(); prepared=nil; status="正在配对照片与视频，检查 Live Photo…"
        Task {
            var next: PreparedLivePhoto?
            let access=url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() }; busy=false }
            do {
                next=try await LivePhotoImport.prepare(bundle:url)
                photo=try await LivePhotoImport.preview(next!)
                prepared=next
                status=next!.usedEstimatedTimestamp ? "已识别为 Live Photo。原图未提供拍摄时间点，使用视频中点；请先播放检查。":"已识别为 Live Photo。可播放预览，确认后导入「照片」。"
            } catch { next?.removeTemporaryFiles(); status="无法准备：\(error.localizedDescription)" }
        }
    }
    func save() {
        guard let prepared,!busy,!imported else { return }; busy=true; status="正在导入「照片」…"
        Task { defer { busy=false }; do { try await LivePhotoImport.save(prepared); imported=true; status="已导入为 Live Photo。开启 Mac 与 iPhone 的 iCloud 照片后会自动同步。" } catch { status="导入失败：\(error.localizedDescription)" } }
    }
}
struct LivePreview: NSViewRepresentable {
    let photo: PHLivePhoto
    func makeNSView(context: Context)->PHLivePhotoView { let view=PHLivePhotoView(); view.contentMode = .aspectFit; view.livePhoto=photo; return view }
    func updateNSView(_ view: PHLivePhotoView,context: Context) { if view.livePhoto !== photo { view.livePhoto=photo } }
}
struct ImportView: View {
    @StateObject private var model=ImportModel()
    var body: some View {
        VStack(alignment:.leading,spacing:18) {
            HStack(spacing:12) {
                Image(systemName:"livephoto").font(.system(size:36)).foregroundStyle(.blue)
                VStack(alignment:.leading) { Text("把动态带进「照片」").font(.title2.bold()); Text("UltraSlim Live · Mac 导入工具").foregroundStyle(.secondary) }
                Spacer()
            }
            ZStack {
                RoundedRectangle(cornerRadius:16).fill(Color.primary.opacity(0.035))
                if let photo=model.photo { LivePreview(photo:photo).padding(8) }
                else { VStack(spacing:12) { Image(systemName:"photo.on.rectangle.angled").font(.system(size:48)).foregroundStyle(.secondary); Text(model.busy ? "正在准备…":"打开 Android 导出的 ZIP").foregroundStyle(.secondary) } }
            }.frame(minHeight:280,maxHeight:.infinity)
            if !model.name.isEmpty { Text(model.name).font(.headline).lineLimit(1) }
            Text(model.status).font(.callout).textSelection(.enabled).fixedSize(horizontal:false,vertical:true)
            HStack {
                Button("打开导入包…") { let p=NSOpenPanel(); p.allowedContentTypes=[.zip]; p.allowsMultipleSelection=false; p.canChooseDirectories=false; if p.runModal() == .OK,let url=p.url { model.open(url) } }.disabled(model.busy)
                if model.photo != nil { Text("按住预览播放").font(.caption).foregroundStyle(.secondary) }
                Spacer()
                if model.busy { ProgressView().controlSize(.small) }
                Button(model.imported ? "已导入":"导入「照片」") { model.save() }.buttonStyle(.borderedProminent).disabled(model.photo==nil || model.busy || model.imported)
            }
            Text("照片与视频只在本机处理。同步到 iPhone 由你的 iCloud 照片设置控制。").font(.caption).foregroundStyle(.secondary)
        }.padding(24).frame(minWidth:580,minHeight:600).onOpenURL { model.open($0) }
    }
}
