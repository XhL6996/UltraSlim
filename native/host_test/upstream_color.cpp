// Regression coverage for google/libultrahdr 924967a759, 2472ff7ad1, b4880a1c21.
#include "ultrahdr_api.h"
#include "ultrahdr/gainmapmath.h"
#include <libheif/heif.h>
#include <array>
#include <vector>
#include <cstdio>
#include <cstdlib>
#include <cmath>
using namespace ultrahdr;
static void check(bool ok,const char* message){if(!ok){fprintf(stderr,"FAIL: %s\n",message);std::exit(1);}}
static void api(uhdr_error_info_t result){if(result.error_code!=UHDR_CODEC_OK){fprintf(stderr,"%s\n",result.detail);std::exit(1);}}
int main(){
 uint8_t rgb[3]={};uhdr_raw_image_t image{};image.fmt=UHDR_IMG_FMT_24bppRGB888;image.w=image.h=1;image.planes[0]=rgb;image.stride[0]=1;
 Color color={{{.1f,.5f,.9f}}};putRgb888Pixel(&image,0,0,color);check(rgb[0]==26&&rgb[1]==128&&rgb[2]==230,"distinct RGB channels survive writer");
 std::array<std::array<uint8_t,4>,4> colors{{{{255,0,0,255}},{{0,255,0,255}},{{0,0,255,255}},{{37,149,233,255}}}};
 std::vector<uint8_t> rgba(32*32*4);for(size_t i=0;i<32*32;i++)for(int c=0;c<4;c++)rgba[i*4+c]=colors[i%4][c];
 uhdr_raw_image_t sdr{};sdr.fmt=UHDR_IMG_FMT_32bppRGBA8888;sdr.ct=UHDR_CT_SRGB;sdr.range=UHDR_CR_FULL_RANGE;sdr.w=sdr.h=32;sdr.planes[0]=rgba.data();sdr.stride[0]=32;
#if defined(__ARM_NEON__) || defined(__ARM_NEON)
 for(auto gamut:{UHDR_CG_BT_709,UHDR_CG_DISPLAY_P3,UHDR_CG_BT_2100}){
  sdr.cg=gamut;auto scalar=convert_raw_input_to_ycbcr(&sdr),neon=convert_raw_input_to_ycbcr_neon(&sdr);check(scalar&&neon,"RGB conversion allocated");
  for(int c=0;c<3;c++)for(size_t y=0;y<32;y++)for(size_t x=0;x<32;x++){
   int a=static_cast<uint8_t*>(scalar->planes[c])[y*scalar->stride[c]+x],b=static_cast<uint8_t*>(neon->planes[c])[y*neon->stride[c]+x];check(std::abs(a-b)<=1,"ARM NEON matches scalar for 709/P3/2020");
  }
 }
#endif
 sdr.cg=UHDR_CG_BT_709;
 std::vector<uint32_t> hdrPixels(32*32,512u|(512u<<10)|(512u<<20)|(3u<<30));
 auto hdr=sdr;hdr.fmt=UHDR_IMG_FMT_32bppRGBA1010102;hdr.ct=UHDR_CT_HLG;hdr.planes[0]=hdrPixels.data();
 for(auto codec:{UHDR_CODEC_HEIF,UHDR_CODEC_AVIF}){
  auto*enc=uhdr_create_encoder();api(uhdr_enc_set_raw_image(enc,&hdr,UHDR_HDR_IMG));api(uhdr_enc_set_raw_image(enc,&sdr,UHDR_SDR_IMG));api(uhdr_enc_set_output_format(enc,codec));api(uhdr_encode(enc));
  auto*out=uhdr_get_encoded_stream(enc);check(out&&out->data_sz,"encoded stream");
  auto*ctx=heif_context_alloc();check(heif_context_read_from_memory_without_copy(ctx,out->data,out->data_sz,nullptr).code==heif_error_Ok,"read HEIC/AVIF");
  heif_image_handle*h=nullptr;check(heif_context_get_primary_image_handle(ctx,&h).code==heif_error_Ok,"primary handle");
  heif_color_profile_nclx*n=nullptr;check(heif_image_handle_get_nclx_color_profile(h,&n).code==heif_error_Ok,"NCLX present");check(n->transfer_characteristics==13,"sRGB encoded with transfer 13");
  heif_nclx_color_profile_free(n);heif_image_handle_release(h);heif_context_free(ctx);uhdr_release_encoder(enc);
 }
 puts("PASS: upstream RGB, ARM gamut and HEIC/AVIF sRGB fixes");
}
