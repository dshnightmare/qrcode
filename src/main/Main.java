package main;

import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.DetectorResult;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.detector.Detector;
import com.google.zxing.qrcode.encoder.QRCodeBeautify;
import com.google.zxing.qrcode.QRCodeWriter;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Paths;
import java.util.Hashtable;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.highgui.Highgui;

import javax.imageio.ImageIO;

/**
 * Created by Mac on 15/1/29.
 */
public class Main {
    public static void main(String[] args){
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        String content = "test";
        Hashtable<EncodeHintType, Object> hints = new Hashtable<EncodeHintType, Object>();
        hints.put(EncodeHintType.CHARACTER_SET, "GBK");
        hints.put(EncodeHintType.QR_VERSION, 5);
        BitMatrix matrix = null;
        try{
            matrix = new QRCodeWriter().encode("https://we.yiqixie.com/", BarcodeFormat.QR_CODE, 20, hints);
            Highgui.imwrite("result/logo_final.bmp", new QRCodeWriter().encode("result/logo.bmp", "https://we.yiqixie.com/", BarcodeFormat.QR_CODE, 20, hints));
        }catch (Exception e) {
            e.printStackTrace();
        }


        if(null != matrix){
            try{
                MatrixToImageWriter.writeToPath(matrix, "BMP", Paths.get("result/logo_standard.bmp"));
            }catch (Exception e){
                e.printStackTrace();
            }
        }


        try{
            String file = "result/logo_final.bmp";
            Result result = null;
            BufferedImage image = null;

            image = ImageIO.read(new File(file));
            LuminanceSource source = new BufferedImageLuminanceSource(image);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Hashtable<DecodeHintType, Object> hints2 = new Hashtable<DecodeHintType, Object>();
            hints2.put(DecodeHintType.CHARACTER_SET, "GBK");
            result = new MultiFormatReader().decode(bitmap, hints2);
            String rtn = result.getText();
            System.out.println(rtn);

        }catch(Exception ex){
            System.out.println(ex.toString());
        }

    }
}
