import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;

/** Test helper only: independently decode the displayed pixels, never print a ticket secret. */
class VerifyTicketQr {
    static String decode(String path) throws Exception {
        BufferedImage image = ImageIO.read(Path.of(path).toFile());
        int width = image.getWidth(), height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
            new RGBLuminanceSource(width, height, pixels))), Map.of(DecodeHintType.TRY_HARDER, true)).getText();
    }
    public static void main(String[] args) throws Exception {
        String before = decode(args[0]), after = decode(args[1]);
        if (!before.startsWith("eventpass-demo-ticket:") || !before.equals(after))
            throw new AssertionError("Rendered ticket payload changed after process restoration");
        System.out.println("PASS: screenshots decode to the same issued QR payload");
    }
}
