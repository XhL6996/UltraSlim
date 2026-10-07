#include "../jni/transcode.h"
#include "../jni/image_metadata.h"
#include <fcntl.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <thread>
#include <jpeglib.h>
#include <libheif/heif.h>

static void check(bool b,const char* msg){if(!b){fprintf(stderr,"FAIL: %s\n",msg);exit(1);}}
static ultraslim::TranscodeResult convert(const std::string& in,const std::string& out,ultraslim::TranscodeOptions o={}) {
  int a=open(in.c_str(),O_RDONLY),b=open(out.c_str(),O_CREAT|O_TRUNC|O_RDWR,0600);
  check(a>=0&&b>=0,"open fixture");auto r=ultraslim::transcodeImage(a,b,o);close(a);
  if(r.errorCode)fprintf(stderr,"%s\n",r.error.c_str());check(r.errorCode==0,"transcode");
  auto p=ultraslim::probeImage(b);check(p.errorCode==0,"output probe");
  check(r.width==p.width&&r.height==p.height&&r.gainmapWidth==p.gainmapWidth&&r.gainmapHeight==p.gainmapHeight,"reported dimensions match output");
  check(!r.inputHadGainMap||p.hasGainMap,"gain map retained");
  check(!ultraslim::decodePreviewJpeg(b,64).empty(),"output decodable");close(b);return r;
}
static bool exif(const std::string& file) {int fd=open(file.c_str(),O_RDONLY);auto p=ultraslim::probeImage(fd);close(fd);return p.hasExif;}
static std::vector<uint8_t> icc(const std::string& file){
 auto*c=heif_context_alloc();check(heif_context_read_from_file(c,file.c_str(),nullptr).code==heif_error_Ok,"ICC open");
 heif_image_handle*h=nullptr;heif_context_get_primary_image_handle(c,&h);std::vector<uint8_t> v(heif_image_handle_get_raw_color_profile_size(h));
 if(!v.empty())heif_image_handle_get_raw_color_profile(h,v.data());heif_image_handle_release(h);heif_context_free(c);return v;
}
static void orientedJpeg(const std::string& path,int orientation,int width=64,int height=32){
 FILE*f=fopen(path.c_str(),"wb");jpeg_compress_struct c{};jpeg_error_mgr e{};c.err=jpeg_std_error(&e);jpeg_create_compress(&c);jpeg_stdio_dest(&c,f);
 c.image_width=width;c.image_height=height;c.input_components=3;c.in_color_space=JCS_RGB;jpeg_set_defaults(&c);jpeg_start_compress(&c,TRUE);
 unsigned char marker[]={'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,1,0,0x12,1,3,0,1,0,0,0,1,0,0,0,0,0,0,0};marker[24]=orientation;
 jpeg_write_marker(&c,JPEG_APP0+1,marker,sizeof(marker));std::vector<unsigned char> row(width*3);
 for(int x=0;x<width*3;++x) row[x]=(x*17)%256;
 while(c.next_scanline<c.image_height){JSAMPROW r=row.data();jpeg_write_scanlines(&c,&r,1);}jpeg_finish_compress(&c);jpeg_destroy_compress(&c);fclose(f);
}
int main(int argc,char**argv){
 check(argc==3,"usage regression <fixtures> <output-dir>");std::string dir=argv[1],out=argv[2];
 ultraslim::TranscodeOptions o;o.codecThreads=2;
 for(int codec=0;codec<4;codec++){
  o.outCodec=codec;o.resizePercent=50;
  auto target=out+"/hdr-"+std::to_string(codec);
  convert(dir+"/apple_gainmap_new.jpg",target,o);check(exif(target),"HDR EXIF retained");
  o.resizePercent=100;convert(target,target+"-round",o);check(exif(target+"-round"),"HDR EXIF retained on re-encode");
  o.keepExif=false;convert(target,target+"-private",o);check(!exif(target+"-private"),"EXIF removed");o.keepExif=true;
 }
 o.outCodec=1;convert(dir+"/minnie-320x240-yuv-icc.jpg",out+"/icc.heic",o);check(!icc(out+"/icc.heic").empty(),"SDR ICC retained");
 convert(out+"/icc.heic",out+"/icc-round.heic",o);check(icc(out+"/icc.heic")==icc(out+"/icc-round.heic"),"ICC preserved byte-for-byte");
 // P3 must survive both the initial AVIF encode and a container-to-container retry.
 o.outCodec=2;
 convert(dir+"/apple_gainmap_new.jpg",out+"/p3.avif",o);
 check(!icc(out+"/p3.avif").empty(),"P3 AVIF ICC present");
 convert(out+"/p3.avif",out+"/p3-round.avif",o);
 check(icc(out+"/p3.avif")==icc(out+"/p3-round.avif"),"AVIF P3 ICC preserved byte-for-byte");
 o.outCodec=1;
 for(int orientation: {6,8}){
  auto file=out+"/rotate-"+std::to_string(orientation)+".jpg";orientedJpeg(file,orientation);
  for(bool keep:{false,true}){o.keepExif=keep;auto r=convert(file,file+(keep?"-exif":"-none"),o);check(r.width==32&&r.height==64,"orientation normalized independently of EXIF retention");}
 }
 // Exercise independent codec settings concurrently (TLS must not bleed across calls).
 std::thread a([&]{auto x=o;x.outCodec=1;x.speedLevel=0;convert(dir+"/apple_gainmap_new.jpg",out+"/parallel-a",x);});
 std::thread b([&]{auto x=o;x.outCodec=2;x.speedLevel=2;convert(dir+"/apple_gainmap_new.jpg",out+"/parallel-b",x);});a.join();b.join();
 // Odd-sized images exercise parallel resize/YUV row boundaries and scalar tails.
 auto large=out+"/parallel-pixels.jpg";orientedJpeg(large,1,2051,1537);
 auto pixelOptions=o;pixelOptions.outCodec=1;pixelOptions.speedLevel=2;pixelOptions.resizePercent=70;
 pixelOptions.codecThreads=1;convert(large,large+"-serial",pixelOptions);
 pixelOptions.codecThreads=4;convert(large,large+"-parallel",pixelOptions);
 auto decodedRgb=[](const std::string& path){
  auto*ctx=heif_context_alloc();check(heif_context_read_from_file(ctx,path.c_str(),nullptr).code==heif_error_Ok,"read pixel fixture");
  heif_image_handle*h=nullptr;heif_context_get_primary_image_handle(ctx,&h);heif_image*i=nullptr;
  check(heif_decode_image(h,&i,heif_colorspace_RGB,heif_chroma_interleaved_RGB,nullptr).code==heif_error_Ok,"decode pixel fixture");
  int stride;auto*p=heif_image_get_plane_readonly(i,heif_channel_interleaved,&stride);
  const int w=heif_image_get_primary_width(i),height=heif_image_get_primary_height(i);
  std::vector<uint8_t> bytes(size_t(w)*height*3);
  for(int y=0;y<height;++y)memcpy(bytes.data()+size_t(y)*w*3,p+size_t(y)*stride,w*3);
  heif_image_release(i);heif_image_handle_release(h);heif_context_free(ctx);return bytes;
 };
 check(decodedRgb(large+"-serial")==decodedRgb(large+"-parallel"),"parallel preprocessing is pixel exact");
 auto bad=out+"/bad.jpg";FILE*f=fopen(bad.c_str(),"wb");fwrite("\xff\xd8" "bad",1,5,f);fclose(f);
 int fd=open(bad.c_str(),O_RDONLY);check(ultraslim::probeImage(fd).errorCode!=0,"corrupt file rejected");close(fd);
 // GPS payload must be erased, not merely detached from its directory.
 std::vector<uint8_t> gps(80,0);memcpy(gps.data(),"Exif\0\0II",8);gps[8]=42;gps[10]=8;gps[14]=1;
 gps[16]=0x25;gps[17]=0x88;gps[18]=4;gps[20]=1;gps[24]=26;
 gps[32]=1;gps[34]=2;gps[36]=5;gps[38]=3;gps[42]=44;
 std::fill(gps.begin()+50,gps.begin()+74,0x7b);ultraslim::ExifEditor(gps).normalize(64,32,false);
 check(gps[14]==0,"GPS directory removed");for(int i=32;i<74;i++)check(gps[i]==0,"GPS payload erased");
 std::vector<uint8_t> cyclic(40,0);memcpy(cyclic.data(),"Exif\0\0II",8);cyclic[8]=42;cyclic[10]=8;cyclic[14]=1;
 cyclic[16]=0x25;cyclic[17]=0x88;cyclic[18]=4;cyclic[20]=1;cyclic[24]=8;
 ultraslim::ExifEditor(cyclic).normalize(64,32,false);check(cyclic[14]==0,"cyclic GPS pointer safely removed");
 puts("PASS: format roundtrips, gain maps, output dimensions, EXIF, ICC, orientation, concurrency and corrupt input");
}
