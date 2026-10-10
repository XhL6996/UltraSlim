import Foundation
import LivePhotoCore
@main struct Check {
    static func main() async {
        guard CommandLine.arguments.count==3 else { print("usage: livephoto-check input.zip output-directory"); exit(2) }
        do {
            let p=try await LivePhotoImport.prepare(bundle:URL(fileURLWithPath:CommandLine.arguments[1])); defer { p.removeTemporaryFiles() }
            _=try await LivePhotoImport.preview(p)
            let out=URL(fileURLWithPath:CommandLine.arguments[2],isDirectory:true)
            try FileManager.default.createDirectory(at:out,withIntermediateDirectories:true)
            for f in [p.photo,p.video] { try FileManager.default.copyItem(at:f,to:out.appendingPathComponent(f.lastPathComponent)) }
            print("PASS: PhotoKit recognized HEIC + MOV as a Live Photo. Photos library not modified.")
        } catch { fputs("FAIL: \(error.localizedDescription)\n",stderr); exit(1) }
    }
}
