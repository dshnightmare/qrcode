/*
 * Copyright 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.qrcode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.Writer;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.reedsolomon.GenericGF;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;
import com.google.zxing.qrcode.encoder.QRCodeBeautify;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.highgui.Highgui;
import org.opencv.imgproc.Imgproc;

import java.util.Map;

/**
 * This object renders a QR Code as a BitMatrix 2D array of greyscale values.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
public final class QRCodeWriter implements Writer {

    private static final int QUIET_ZONE_SIZE = 4;

    @Override
    public BitMatrix encode(String contents, BarcodeFormat format, int width, int height)
            throws WriterException {

        return encode(contents, format, width, height, null);
    }

    @Override
    public BitMatrix encode(String contents,
                            BarcodeFormat format,
                            int width,
                            int height,
                            Map<EncodeHintType, ?> hints) throws WriterException {

        if (contents.isEmpty()) {
            throw new IllegalArgumentException("Found empty contents");
        }

        if (format != BarcodeFormat.QR_CODE) {
            throw new IllegalArgumentException("Can only encode QR_CODE, but got " + format);
        }

        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Requested dimensions are too small: " + width + 'x' +
                    height);
        }

        ErrorCorrectionLevel errorCorrectionLevel = ErrorCorrectionLevel.L;
        int quietZone = QUIET_ZONE_SIZE;
        if (hints != null) {
            ErrorCorrectionLevel requestedECLevel = (ErrorCorrectionLevel) hints.get(EncodeHintType.ERROR_CORRECTION);
            if (requestedECLevel != null) {
                errorCorrectionLevel = requestedECLevel;
            }
            Integer quietZoneInt = (Integer) hints.get(EncodeHintType.MARGIN);
            if (quietZoneInt != null) {
                quietZone = quietZoneInt;
            }
        }

        QRCode code = Encoder.encode(contents, errorCorrectionLevel, hints);
        return renderResult(code, width, height, quietZone);
    }

    // Note that the input matrix uses 0 == white, 1 == black, while the output matrix uses
    // 0 == black, 255 == white (i.e. an 8 bit greyscale bitmap).
    private static BitMatrix renderResult(QRCode code, int width, int height, int quietZone) {
        ByteMatrix input = code.getMatrix();
        if (input == null) {
            throw new IllegalStateException();
        }
        int inputWidth = input.getWidth();
        int inputHeight = input.getHeight();
        int qrWidth = inputWidth + (quietZone * 2);
        int qrHeight = inputHeight + (quietZone * 2);
        int outputWidth = Math.max(width, qrWidth);
        int outputHeight = Math.max(height, qrHeight);

        int multiple = Math.min(outputWidth / qrWidth, outputHeight / qrHeight);
        // Padding includes both the quiet zone and the extra white pixels to accommodate the requested
        // dimensions. For example, if input is 25x25 the QR will be 33x33 including the quiet zone.
        // If the requested size is 200x160, the multiple will be 4, for a QR of 132x132. These will
        // handle all the padding from 100x100 (the actual QR) up to 200x160.
        int leftPadding = (outputWidth - (inputWidth * multiple)) / 2;
        int topPadding = (outputHeight - (inputHeight * multiple)) / 2;

        BitMatrix output = new BitMatrix(outputWidth, outputHeight);

        for (int inputY = 0, outputY = topPadding; inputY < inputHeight; inputY++, outputY += multiple) {
            // Write the contents of this row of the barcode
            for (int inputX = 0, outputX = leftPadding; inputX < inputWidth; inputX++, outputX += multiple) {
                if (input.get(inputX, inputY) == 1) {
                    output.setRegion(outputX, outputY, multiple, multiple);
                }
            }
        }

        return output;
    }

    public BitMatrix encode(String contents,
                            BarcodeFormat format,
                            int moduleSize,
                            Map<EncodeHintType, ?> hints) throws WriterException {

        if (contents.isEmpty()) {
            throw new IllegalArgumentException("Found empty contents");
        }

        if (format != BarcodeFormat.QR_CODE) {
            throw new IllegalArgumentException("Can only encode QR_CODE, but got " + format);
        }

        if (moduleSize < 0) {
            throw new IllegalArgumentException("Requested dimensions are too small: " + moduleSize + 'x' +
                    moduleSize);
        }

        ErrorCorrectionLevel errorCorrectionLevel = ErrorCorrectionLevel.L;
        int quietZone = QUIET_ZONE_SIZE;
        if (hints != null) {
            ErrorCorrectionLevel requestedECLevel = (ErrorCorrectionLevel) hints.get(EncodeHintType.ERROR_CORRECTION);
            if (requestedECLevel != null) {
                errorCorrectionLevel = requestedECLevel;
            }
            Integer quietZoneInt = (Integer) hints.get(EncodeHintType.MARGIN);
            if (quietZoneInt != null) {
                quietZone = quietZoneInt;
            }
        }

        QRCode code = Encoder.encode(contents, errorCorrectionLevel, hints);
        return renderResult(code, moduleSize, quietZone);
    }

    public static BitMatrix renderResult(QRCode code, int moduleSize, int quietZone){
        ByteMatrix input = code.getMatrix();
        if (input == null) {
            throw new IllegalStateException();
        }
        int inputWidth = input.getWidth();
        int inputHeight = input.getHeight();
        int qrWidth = (inputWidth + quietZone * 2) * moduleSize;
        int qrHeight = (inputHeight + quietZone * 2) * moduleSize;

        int leftPadding = quietZone * moduleSize;
        int topPadding = quietZone * moduleSize;

        BitMatrix output = new BitMatrix(qrWidth, qrHeight);

        for (int inputY = 0, outputY = topPadding; inputY < inputHeight; inputY++, outputY += moduleSize) {
            // Write the contents of this row of the barcode
            for (int inputX = 0, outputX = leftPadding; inputX < inputWidth; inputX++, outputX += moduleSize) {
                if (input.get(inputX, inputY) == 1) {
                    output.setRegion(outputX, outputY, moduleSize, moduleSize);
                }
            }
        }

        return output;
    }

    public Mat encode(String embedImg,
                            String contents,
                            BarcodeFormat format,
                            int moduleSize,
                            Map<EncodeHintType, ?> hints) throws Exception {

        if (contents.isEmpty()) {
            throw new IllegalArgumentException("Found empty contents");
        }

        if (format != BarcodeFormat.QR_CODE) {
            throw new IllegalArgumentException("Can only encode QR_CODE, but got " + format);
        }

        if (moduleSize < 0) {
            throw new IllegalArgumentException("Requested dimensions are too small: " + moduleSize + 'x' +
                    moduleSize);
        }

        ErrorCorrectionLevel errorCorrectionLevel = ErrorCorrectionLevel.L;
        int quietZone = QUIET_ZONE_SIZE;
        if (hints != null) {
            ErrorCorrectionLevel requestedECLevel = (ErrorCorrectionLevel) hints.get(EncodeHintType.ERROR_CORRECTION);
            if (requestedECLevel != null) {
                errorCorrectionLevel = requestedECLevel;
            }
            Integer quietZoneInt = (Integer) hints.get(EncodeHintType.MARGIN);
            if (quietZoneInt != null) {
                quietZone = quietZoneInt;
            }
        }

        QRCode basicCode = Encoder.encode(contents, errorCorrectionLevel, hints);
        //Load the embedImg
        Mat src = Highgui.imread(embedImg);
        Mat out = new Mat();
        Size size = new Size(basicCode.getMatrix().getWidth() * moduleSize,
                             basicCode.getMatrix().getHeight() * moduleSize);
        Imgproc.resize(src, out, size);
        Highgui.imwrite("result/logo_change_size.bmp", out);
        QRCodeBeautify beautify = new QRCodeBeautify();
        QRCode ideal = beautify.idealCode(embedImg, contents, errorCorrectionLevel, moduleSize, hints);
        Mat idealImage = renderResult(out, ideal, moduleSize, quietZone, beautify.basicNotChange);
        Highgui.imwrite("result/logo_ideal.bmp", idealImage);
        QRCode code = beautify.encode(embedImg, contents, errorCorrectionLevel, moduleSize, hints);
        Mat real = renderResult(out, code, moduleSize, quietZone, beautify.basicNotChange, beautify.basicChange, beautify.mIdeal);
        return real;
    }

    public static Mat renderResult(Mat embed, QRCode code, int moduleSize, int quietZone, ByteMatrix flag){
        ByteMatrix input = code.getMatrix();
        if (input == null) {
            throw new IllegalStateException();
        }
        int inputWidth = input.getWidth();
        int inputHeight = input.getHeight();
        int qrWidth = (inputWidth + quietZone * 2) * moduleSize;
        int qrHeight = (inputHeight + quietZone * 2) * moduleSize;

        assert embed.width() == inputWidth * moduleSize;
        Mat output = new Mat(qrWidth, qrHeight, embed.type(), new Scalar(255, 255, 255));

        //For each module
        for(int i = 0; i < inputHeight; i++) {
            for (int j = 0; j < inputWidth; j++) {
                int moduleX = (j + quietZone) * moduleSize;
                int moduleY = (i + quietZone) * moduleSize;
                if (flag.get(j, i) != -1) {
                    if (input.get(j, i) == 0) {
                        // white

                    } else {
                        // black
                        for (int m = 0; m < moduleSize; m++) {
                            for (int n = 0; n < moduleSize; n++) {
                                output.col(moduleX + m).row(moduleY + n).setTo(new Scalar(0, 0, 0));
                            }
                        }
                    }
                } else {
                    for (int m = 0; m < moduleSize; m++) {
                        for (int n = 0; n < moduleSize; n++) {
                            embed.col(moduleX + m - quietZone * moduleSize).row(moduleY + n - quietZone * moduleSize).copyTo(output.col(moduleX + m).row(moduleY + n));
                        }
                    }
                }
            }
        }
        return output;
    }

    public static Mat renderResult(Mat embed, QRCode code, int moduleSize, int quietZone, ByteMatrix flag1, ByteMatrix flag2, BitMatrix ideal){
        ByteMatrix input = code.getMatrix();
        if (input == null) {
            throw new IllegalStateException();
        }
        int inputWidth = input.getWidth();
        int inputHeight = input.getHeight();
        int qrWidth = (inputWidth + quietZone * 2) * moduleSize;
        int qrHeight = (inputHeight + quietZone * 2) * moduleSize;

        assert embed.width() == inputWidth * moduleSize;
        Mat output = new Mat(qrWidth, qrHeight, embed.type(), new Scalar(255, 255, 255));

        //For each module
        for(int i = 0; i < inputHeight; i++) {
            for (int j = 0; j < inputWidth; j++) {
                int moduleX = (j + quietZone) * moduleSize;
                int moduleY = (i + quietZone) * moduleSize;
                if (flag1.get(j, i) != -1) {
                    if (input.get(j, i) == 0) {
                        // white

                    } else {
                        // black
                        for (int m = 0; m < moduleSize; m++) {
                            for (int n = 0; n < moduleSize; n++) {
                                output.col(moduleX + m).row(moduleY + n).setTo(new Scalar(0, 0, 0));
                            }
                        }
                    }
                } else if (flag2.get(j, i) != -1 || (input.get(j, i) == 1) != ideal.get(j, i)) {
                    for (int m = 0; m < moduleSize; m++) {
                        for (int n = 0; n < moduleSize; n++) {
                            if(m >= moduleSize / 3 && m <= moduleSize * 2 / 3 && n >= moduleSize / 3 && n <= moduleSize * 2 / 3){
                                output.col(moduleX + m).row(moduleY + n).setTo(input.get(j, i) == 0 ? new Scalar(255, 255, 255) : new Scalar(0, 0, 0));
                            }
                            else
                                embed.col(moduleX + m - quietZone * moduleSize).row(moduleY + n - quietZone * moduleSize).copyTo(output.col(moduleX + m).row(moduleY + n));
                        }
                    }
                } else {
                    for (int m = 0; m < moduleSize; m++) {
                        for (int n = 0; n < moduleSize; n++) {
                            embed.col(moduleX + m - quietZone * moduleSize).row(moduleY + n - quietZone * moduleSize).copyTo(output.col(moduleX + m).row(moduleY + n));
                        }
                    }
                }
            }
        }
        return output;
    }
}
