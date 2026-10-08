// Apple ImageIO/Core Image diagnostic for the source and its unresized export.
// Run: xcrun swift native/host_test/AppleColorAudit.swift source.jpg output.avif
// SDR/HDR comparisons use floating-point extended linear sRGB, without display
// tone mapping. Gain-map samples are read without color management. Statistics
// diagnose differences; they are not proof of visual equivalence or conformance.
import Foundation
import CoreImage
import ImageIO
let space = CGColorSpace(name: CGColorSpace.extendedLinearSRGB)!
let ctx = CIContext(options: [.workingColorSpace: space, .workingFormat: CIFormat.RGBAf])
func render(_ image: CIImage, raw: Bool = false) -> (Int,Int,[Float]) {
 let scale = min(1, 768 / max(image.extent.width, image.extent.height))
 let im = image.transformed(by: CGAffineTransform(scaleX: scale,y: scale))
 let w=Int(im.extent.width), h=Int(im.extent.height)
 var data=[Float](repeating:0,count:w*h*4)
 let context = raw ? CIContext(options:[.workingColorSpace:NSNull()]) : ctx
 data.withUnsafeMutableBytes { p in context.render(im,toBitmap:p.baseAddress!,rowBytes:w*16,bounds:CGRect(x:im.extent.minX,y:im.extent.minY,width:CGFloat(w),height:CGFloat(h)),format:.RGBAf,colorSpace:raw ? nil : space) }
 return(w,h,data)
}
let a=CommandLine.arguments
 guard a.count == 3 else { fatalError("usage: AppleColorAudit source output") }

for mode in ["sdr","hdr","gainmap"] {
 var all:[[Float]]=[];var sizes:[String]=[]
 for p in a.dropFirst() {
  let url=URL(fileURLWithPath:p)
  var opts:[CIImageOption:Any]=[.applyOrientationProperty:true,.expandToHDR:mode=="hdr"]
  if mode=="gainmap" {opts[.auxiliaryHDRGainMap]=true; opts[.colorSpace]=NSNull()}
  guard let im=CIImage(contentsOf:url,options:opts) else { fatalError("Cannot decode \(mode): \(p)") }
  let(w,h,d)=render(im,raw:mode=="gainmap");all.append(d);sizes.append("\(w)x\(h)")
  var means=[Double](repeating:0,count:3);var peaks=means
  for i in stride(from:0,to:d.count,by:4) {for c in 0..<3 {means[c]+=Double(d[i+c]);peaks[c]=max(peaks[c],Double(d[i+c]))}}
  means=means.map{$0/Double(w*h)}
  print(mode,url.lastPathComponent,"size",sizes.last!,"profile",im.colorSpace?.name as String? ?? "none","headroom",im.contentHeadroom,"mean",means,"max",peaks)

 }
 if all.count==2 && sizes[0]==sizes[1] {
  var mae=[Double](repeating:0,count:3)
  for i in stride(from:0,to:all[0].count,by:4){for c in 0..<3{mae[c]+=Double(abs(all[0][i+c]-all[1][i+c]))}}
  print(mode,"MAE",mae.map{$0/Double(all[0].count/4)})
 }
}
