package main;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.nio.file.Paths;
import java.util.Hashtable;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

/**
 * Created by Mac on 15/1/29.
 */
public class Main {
    public static void main(String[] args){
        String content = "test";
        Hashtable<EncodeHintType, Object> hints = new Hashtable<EncodeHintType, Object>();
        hints.put(EncodeHintType.CHARACTER_SET, "GBK");
        hints.put(EncodeHintType.MARGIN, 0);
        BitMatrix matrix = null;
        try{
            matrix = new QRCodeWriter().encode("test", BarcodeFormat.QR_CODE, 255, 255, hints);
        }catch (Exception e) {
            e.printStackTrace();
        }


        if(null != matrix){
            try{
                MatrixToImageWriter.writeToPath(matrix, "PNG", Paths.get("result/test.png"));
            }catch (Exception e){
                e.printStackTrace();
            }
        }

        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        Mat m = Mat.eye(3, 3, CvType.CV_8UC1);
        System.out.println("m = " + m.dump());
    }
}
