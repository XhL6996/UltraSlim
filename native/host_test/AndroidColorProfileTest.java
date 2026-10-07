import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import java.io.File;

/** Run via app_process: checks the platform decoder, independently of libheif. */
public final class AndroidColorProfileTest {
    private static Bitmap decode(String path) throws Exception {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(new File(path)), (decoder, info, source) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int edge = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
            decoder.setTargetSampleSize(Math.max(1, (edge + 1599) / 1600));
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
        if (args.length != 2) throw new IllegalArgumentException("source.jpg output.avif");
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
            if (error > 3.0) throw new AssertionError("Unexpected color difference at quality 90+");
            System.out.println("PASS: platform color profile and SDR rendition");
        } finally {
            source.recycle();
            output.recycle();
        }
    }
}
