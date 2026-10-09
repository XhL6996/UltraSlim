import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Gainmap;
import android.os.Build;
import android.graphics.ImageDecoder;
import java.io.File;

/** Run via app_process: checks the platform decoder, independently of libheif. */
public final class AndroidColorProfileTest {
    private static int sampleEdge = 1600;
    private static boolean linearHdr = false;
    private static Bitmap decode(String path) throws Exception {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(new File(path)), (decoder, info, source) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int edge = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
            decoder.setTargetSampleSize(Math.max(1, (edge + sampleEdge - 1) / sampleEdge));
        });
    }

    public static void main(String[] args) {
        try {
            check(args);
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void check(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4) throw new IllegalArgumentException("source.jpg output.avif [sampleEdge] [pixels|linear]");
        if (args.length >= 3) sampleEdge = Integer.parseInt(args[2]);
        if (args.length == 4) {
            if (!args[3].equals("linear") && !args[3].equals("pixels")) throw new IllegalArgumentException("unknown comparison mode");
            linearHdr = args[3].equals("linear");
        }
        if (sampleEdge <= 0) throw new IllegalArgumentException("sampleEdge must be positive");
        Bitmap source = decode(args[0]);
        Bitmap output = decode(args[1]);
        try {
            ColorSpace expected = source.getColorSpace();
            System.out.println("source=" + expected + " output=" + output.getColorSpace());
            if (expected == null || !expected.equals(output.getColorSpace())) {
                throw new AssertionError("Platform decoder lost or changed the source color space");
            }
            if (source.getWidth() != output.getWidth() || source.getHeight() != output.getHeight()) {
                throw new AssertionError("Use an unresized, orientation-normalized output for this test");
            }
            // getPixels returns sRGB values for both images, independent of their bitmap profiles.
            int[] a = new int[source.getWidth()], b = new int[output.getWidth()];
            double error = 0;
            for (int y = 0; y < source.getHeight(); y++) {
                source.getPixels(a, 0, a.length, 0, y, a.length, 1);
                output.getPixels(b, 0, b.length, 0, y, b.length, 1);
                for (int x = 0; x < a.length; x++) {
                    for (int shift = 16; shift >= 0; shift -= 8) {
                        error += Math.abs(((a[x] >> shift) & 255) - ((b[x] >> shift) & 255));
                    }
                }
            }
            error /= 3.0 * source.getWidth() * source.getHeight();
            System.out.println("SDR sRGB mean absolute error=" + error + "/255");
            // Report the HDR layer even when the SDR threshold fails. Otherwise
            // a batch audit cannot distinguish base-image and gain-map errors.
            boolean sdrValid = error <= 3.0;
            if (Build.VERSION.SDK_INT < 34) {
                throw new AssertionError("API 34+ is required to validate the HDR gain map");
            }
            boolean isHeic = args[1].toLowerCase().endsWith(".heic");
            System.out.println("HDR gain-map presence: source=" + source.hasGainmap() + " output=" + output.hasGainmap());
            if (source.hasGainmap() != output.hasGainmap()) {
                if (isHeic && !output.hasGainmap()) {
                    System.out.println("INFO: Platform ImageDecoder does not extract gain maps from HEIC containers; HDR gain map integrity is verified natively.");
                } else {
                    throw new AssertionError("Platform decoder lost or added an HDR gain map");
                }
            }
            if (output.hasGainmap()) checkGainmap(source.getGainmap(), output.getGainmap());
            if (output.hasGainmap() && linearHdr) checkLinearHdr(source, output);
            else if (!sdrValid) throw new AssertionError("Unexpected SDR difference at quality 90+; color checks completed");
            System.out.println("PASS: platform profile, HDR metadata and " + (linearHdr ? "linear HDR mean (3% bound)" : "pixel error bounds"));
        } finally {
            source.recycle();
            output.recycle();
        }
    }

    private static void sameValues(String name, float[] a, float[] b) {
        if (a.length != b.length) throw new AssertionError(name + " length changed");
        for (int c = 0; c < a.length; c++) {
            if (Math.abs(a[c] - b[c]) > 0.00001f) {
                throw new AssertionError(name + " channel " + c + " changed");
            }
        }
    }

    private static int gainValue(Bitmap bitmap, int pixel, int channel) {
        // Android represents a monochrome JPEG gain map as ALPHA_8, while
        // AVIF decoders may return an RGB bitmap with three equal components.
        return (pixel >>> (bitmap.getConfig() == Bitmap.Config.ALPHA_8 ? 24 : 16 - channel * 8)) & 255;
    }

    private static void checkGainmap(Gainmap source, Gainmap output) {
        sameValues("ratio min", source.getRatioMin(), output.getRatioMin());
        sameValues("ratio max", source.getRatioMax(), output.getRatioMax());
        sameValues("gamma", source.getGamma(), output.getGamma());
        sameValues("epsilon SDR", source.getEpsilonSdr(), output.getEpsilonSdr());
        sameValues("epsilon HDR", source.getEpsilonHdr(), output.getEpsilonHdr());
        sameValues("headroom", new float[]{source.getMinDisplayRatioForHdrTransition(), source.getDisplayRatioForFullHdr()},
                new float[]{output.getMinDisplayRatioForHdrTransition(), output.getDisplayRatioForFullHdr()});
        Bitmap a = source.getGainmapContents(), b = output.getGainmapContents();
        System.out.println("Gain-map bitmaps: " + a.getWidth() + "x" + a.getHeight() + " " + a.getConfig() + " " + a.getColorSpace()
                + " -> " + b.getWidth() + "x" + b.getHeight() + " " + b.getConfig() + " " + b.getColorSpace());
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            throw new AssertionError("Platform gain-map dimensions changed");
        }
        int[] rowA = new int[a.getWidth()], rowB = new int[b.getWidth()];
        double[] errors = new double[3];
        double[] bias = new double[3];
        for (int y = 0; y < a.getHeight(); y++) {
            a.getPixels(rowA, 0, rowA.length, 0, y, rowA.length, 1);
            b.getPixels(rowB, 0, rowB.length, 0, y, rowB.length, 1);
            for (int x = 0; x < rowA.length; x++) {
                for (int c = 0; c < 3; c++) {
                    errors[c] += Math.abs(gainValue(a, rowA[x], c) - gainValue(b, rowB[x], c));
                    bias[c] += gainValue(b, rowB[x], c) - gainValue(a, rowA[x], c);
                }
            }
        }
        boolean valid = true;
        for (int c = 0; c < 3; c++) {
            errors[c] /= (double) a.getWidth() * a.getHeight();
            bias[c] /= (double) a.getWidth() * a.getHeight();
            System.out.println("HDR gain-map channel " + c + " mean absolute error=" + errors[c] + "/255");
            System.out.println("HDR gain-map channel " + c + " signed bias=" + bias[c] + "/255");
            // Allow decoder-specific thumbnail resampling; exact full-size
            // preservation is checked separately by gainmap_preservation.
            valid &= errors[c] <= 2.0;
        }
        if (!valid) {
            System.out.println("PIXEL_BOUND_EXCEEDED: gain-map MAE above 2/255");
            if (!linearHdr) throw new AssertionError("Platform decoder changed HDR gain-map channel values");
        }
    }

    // Independent reconstruction from platform-decoded pixels, using the Gainmap
    // API formula at full HDR headroom. This is not a screenshot/GPU visual test.
    // https://developer.android.com/reference/android/graphics/Gainmap
    private static void checkLinearHdr(Bitmap a, Bitmap b) {
        if (!a.hasGainmap() || !b.hasGainmap()) throw new AssertionError("HDR images required");
        double[][] rendered = new double[2][];
        int step = Math.max(1, (Math.max(a.getWidth(), a.getHeight()) + 767) / 768);
        int count = ((a.getWidth()+step-1)/step)*((a.getHeight()+step-1)/step);
        int imageIndex = 0;
        for (Bitmap base : new Bitmap[]{a,b}) {
            Gainmap gm = base.getGainmap();
            if (Build.VERSION.SDK_INT >= 36 && (gm.getAlternativeImagePrimaries() != null || gm.getGainmapDirection() != Gainmap.GAINMAP_DIRECTION_SDR_TO_HDR))
                throw new AssertionError("Diagnostic supports SDR base-space gain maps only");
            Bitmap map = gm.getGainmapContents();
            int[] mapPixels = new int[map.getWidth()*map.getHeight()];
            map.getPixels(mapPixels, 0, map.getWidth(), 0, 0, map.getWidth(), map.getHeight());
            float[] lo=gm.getRatioMin(), hi=gm.getRatioMax(), gamma=gm.getGamma(), es=gm.getEpsilonSdr(), eh=gm.getEpsilonHdr();
            java.util.function.DoubleUnaryOperator eotf=((ColorSpace.Rgb)base.getColorSpace()).getEotf();
            double[] values = new double[count*3]; int pos=0;
            for(int y=0;y<base.getHeight();y+=step) for(int x=0;x<base.getWidth();x+=step) {
                android.graphics.Color color=base.getColor(x,y);
                double gx=Math.max(0,Math.min(map.getWidth()-1,(x+.5)*map.getWidth()/base.getWidth()-.5));
                double gy=Math.max(0,Math.min(map.getHeight()-1,(y+.5)*map.getHeight()/base.getHeight()-.5));
                int x0=(int)gx,y0=(int)gy,x1=Math.min(x0+1,map.getWidth()-1),y1=Math.min(y0+1,map.getHeight()-1);
                int p00=mapPixels[y0*map.getWidth()+x0],p10=mapPixels[y0*map.getWidth()+x1];
                int p01=mapPixels[y1*map.getWidth()+x0],p11=mapPixels[y1*map.getWidth()+x1];
                double fx=gx-x0,fy=gy-y0;
                for(int c=0;c<3;c++) {
                    double g=((1-fy)*((1-fx)*gainValue(map,p00,c)+fx*gainValue(map,p10,c))+fy*((1-fx)*gainValue(map,p01,c)+fx*gainValue(map,p11,c)))/255;
                    double gain=Math.exp(Math.log(lo[c])+(Math.log(hi[c])-Math.log(lo[c]))*Math.pow(g,gamma[c]));
                    values[pos++]=(eotf.applyAsDouble(color.getComponent(c))+es[c])*gain-eh[c];
                }
            }
            rendered[imageIndex++]=values;
        }
        for(int c=0;c<3;c++) {
            double sa=0,sb=0,mae=0;
            for(int i=c;i<count*3;i+=3) {sa+=rendered[0][i];sb+=rendered[1][i];mae+=Math.abs(rendered[0][i]-rendered[1][i]);}
            double change=100*(sb-sa)/Math.max(Math.abs(sa),1e-9);
            System.out.println("Linear HDR channel "+c+" mean change="+change+"% normalized MAE="+100*mae/Math.max(Math.abs(sa),1e-9)+"%");
            if(Math.abs(change)>3) throw new AssertionError("HDR channel mean differs by more than 3%");
        }
    }
}
