package com.google.zxing.qrcode.encoder;

import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.common.reedsolomon.GenericGF;
import com.google.zxing.common.reedsolomon.ReedSolomonEncoder;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.decoder.Mode;
import com.google.zxing.qrcode.decoder.Version;
import com.google.zxing.qrcode.detector.Detector;
import com.google.zxing.main.BufferedImageLuminanceSource;
import org.opencv.core.*;
import org.opencv.highgui.Highgui;
import org.opencv.imgproc.Imgproc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;

/**
 * Created by dengshihong on 15/1/31.
 */
public class QRCodeBeautify{
    // The original table is defined in the table 5 of JISX0510:2004 (p.19).
    private static final int[] ALPHANUMERIC_TABLE = {
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,  // 0x00-0x0f
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,  // 0x10-0x1f
            36, -1, -1, -1, 37, 38, -1, -1, -1, -1, 39, 40, -1, 41, 42, 43,  // 0x20-0x2f
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 44, -1, -1, -1, -1, -1,  // 0x30-0x3f
            -1, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,  // 0x40-0x4f
            25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, -1, -1, -1, -1, -1,  // 0x50-0x5f
    };

    static final String DEFAULT_BYTE_MODE_ENCODING = "ISO-8859-1";
    static float pEdge = 1.0f;
    static float pSalient = 0.0f;

    public ByteMatrix basicNotChange;
    public ByteMatrix basicChange;
    public BitMatrix mIdeal;
    private int mHeadAndDataLength;
    private int mPaddingLength;
    private int mRSCodeLength;
    private int mTotalLength;
    private int[] mFinalDataToData;
    private int[] mDataToFinalData;

    public QRCodeBeautify() {
    }

    // The mask penalty calculation is complicated.  See Table 21 of JISX0510:2004 (p.45) for details.
    // Basically it applies four rules and summate all penalties.
    private int calculateMaskPenalty(ByteMatrix matrix) {
        return MaskUtil.applyMaskPenaltyRule1(matrix)
                + MaskUtil.applyMaskPenaltyRule2(matrix)
                + MaskUtil.applyMaskPenaltyRule3(matrix)
                + MaskUtil.applyMaskPenaltyRule4(matrix);
    }

    /**
     * @param content text to encode
     * @param ecLevel error correction level to use
     * @return {@link com.google.zxing.qrcode.encoder.QRCode} representing the encoded QR code
     * @throws com.google.zxing.WriterException if encoding can't succeed, because of for example invalid content
     *                         or configuration
     */
    public QRCode encode(String embedImg, String content, ErrorCorrectionLevel ecLevel, int moduleSize) throws Exception {
        return encode(embedImg, content, ecLevel, moduleSize, null);
    }

    public QRCode idealCode(String embedImg,
                           String content,
                           ErrorCorrectionLevel ecLevel,
                           int moduleSize,
                           Map<EncodeHintType, ?> hints) throws WriterException {
        // Determine what character encoding has been specified by the caller, if any
        String encoding = hints == null ? null : (String) hints.get(EncodeHintType.CHARACTER_SET);
        if (encoding == null) {
            encoding = DEFAULT_BYTE_MODE_ENCODING;
        }

        // Pick an encoding mode appropriate for the content. Note that this will not attempt to use
        // multiple modes / segments even if that were more efficient. Twould be nice.
        Mode mode = chooseMode(content, encoding);

        // This will store the header information, like mode and
        // length, as well as "header" segments like an ECI segment.
        BitArray headerBits = new BitArray();

        // Append ECI segment if applicable
        if (mode == Mode.BYTE && !DEFAULT_BYTE_MODE_ENCODING.equals(encoding)) {
            CharacterSetECI eci = CharacterSetECI.getCharacterSetECIByName(encoding);
            if (eci != null) {
                appendECI(eci, headerBits);
            }
        }

        // (With ECI in place,) Write the mode marker
        appendModeInfo(mode, headerBits);

        // Collect data within the com.google.zxing.main segment, separately, to count its size if needed. Don't add it to
        // com.google.zxing.main payload yet.
        BitArray dataBits = new BitArray();
        appendBytes(content, mode, dataBits, encoding);

        // Hard part: need to know version to know how many bits length takes. But need to know how many
        // bits it takes to know version. First we take a guess at version by assuming version will be
        // the minimum, 1:

        int provisionalBitsNeeded = headerBits.getSize()
                + mode.getCharacterCountBits(Version.getVersionForNumber(1))
                + dataBits.getSize();
        Version provisionalVersion = chooseVersion(provisionalBitsNeeded, ecLevel);

        // Use that guess to calculate the right version. I am still not sure this works in 100% of cases.

        int bitsNeeded = headerBits.getSize()
                + mode.getCharacterCountBits(provisionalVersion)
                + dataBits.getSize();
        Version version;
        Integer num;
        if(hints != null && (num = (Integer)hints.get(EncodeHintType.QR_VERSION)) != null)
            version = Version.getVersionForNumber(num);
        else
            version = chooseVersion(bitsNeeded, ecLevel);

        QRCode qrCode = new QRCode();

        qrCode.setECLevel(ecLevel);
        qrCode.setMode(mode);
        qrCode.setVersion(version);

        //  Choose the mask pattern (Which one is ok)
        int dimension = version.getDimensionForVersion();
        ByteMatrix matrix = new ByteMatrix(dimension, dimension);
        basicChange = new ByteMatrix(dimension, dimension);
        MatrixUtil.clearMatrix(basicChange);
        basicNotChange = new ByteMatrix(dimension, dimension);
        MatrixUtil.clearMatrix(basicNotChange);

        int maskPattern = 0;
        qrCode.setMaskPattern(maskPattern);

        // Build the matrix and set it to "qrCode".
        MatrixUtil.clearMatrix(matrix);
        MatrixUtil.embedBasicPatterns(version, matrix);
        MatrixUtil.embedBasicPatterns(version, basicNotChange);
        // Type information appear with any version.
        MatrixUtil.embedTypeInfo(ecLevel, maskPattern, matrix);
        MatrixUtil.embedTypeInfo(ecLevel, maskPattern, basicChange);
        MatrixUtil.embedTypeInfo(ecLevel, maskPattern, basicNotChange);
        // Version info appear if version >= 7.
        MatrixUtil.maybeEmbedVersionInfo(version, matrix);
        MatrixUtil.maybeEmbedVersionInfo(version, basicChange);
        MatrixUtil.embedTypeInfo(ecLevel, maskPattern, basicNotChange);
        qrCode.setMatrix(matrix);

        return qrCode;
    }

    public QRCode encode(String embedImg,
                                String content,
                                ErrorCorrectionLevel ecLevel,
                                int moduleSize,
                                Map<EncodeHintType, ?> hints) throws Exception {

        // Determine what character encoding has been specified by the caller, if any
        String encoding = hints == null ? null : (String) hints.get(EncodeHintType.CHARACTER_SET);
        if (encoding == null) {
            encoding = DEFAULT_BYTE_MODE_ENCODING;
        }

        // Pick an encoding mode appropriate for the content. Note that this will not attempt to use
        // multiple modes / segments even if that were more efficient. Twould be nice.
        Mode mode = chooseMode(content, encoding);

        // This will store the header information, like mode and
        // length, as well as "header" segments like an ECI segment.
        BitArray headerBits = new BitArray();

        // Append ECI segment if applicable
        if (mode == Mode.BYTE && !DEFAULT_BYTE_MODE_ENCODING.equals(encoding)) {
            CharacterSetECI eci = CharacterSetECI.getCharacterSetECIByName(encoding);
            if (eci != null) {
                appendECI(eci, headerBits);
            }
        }

        // (With ECI in place,) Write the mode marker
        appendModeInfo(mode, headerBits);

        // Collect data within the com.google.zxing.main segment, separately, to count its size if needed. Don't add it to
        // com.google.zxing.main payload yet.
        BitArray dataBits = new BitArray();
        appendBytes(content, mode, dataBits, encoding);

        // Hard part: need to know version to know how many bits length takes. But need to know how many
        // bits it takes to know version. First we take a guess at version by assuming version will be
        // the minimum, 1:

        int provisionalBitsNeeded = headerBits.getSize()
                + mode.getCharacterCountBits(Version.getVersionForNumber(1))
                + dataBits.getSize();
        Version provisionalVersion = chooseVersion(provisionalBitsNeeded, ecLevel);

        // Use that guess to calculate the right version. I am still not sure this works in 100% of cases.

        int bitsNeeded = headerBits.getSize()
                + mode.getCharacterCountBits(provisionalVersion)
                + dataBits.getSize();
        Version version;
        Integer num;
        if(hints != null && (num = (Integer)hints.get(EncodeHintType.QR_VERSION)) != null)
            version = Version.getVersionForNumber(num);
        else
            version = chooseVersion(bitsNeeded, ecLevel);


        // data = data +padding + rs
        // finalData = interleave data
        BitArray headerAndDataBits = new BitArray();
        headerAndDataBits.appendBitArray(headerBits);
        // Find "length" of com.google.zxing.main segment and write it
        int numLetters = mode == Mode.BYTE ? dataBits.getSizeInBytes() : content.length();
        appendLengthInfo(numLetters, version, mode, headerAndDataBits);
        // Put data together into the overall payload
        headerAndDataBits.appendBitArray(dataBits);

        Version.ECBlocks ecBlocks = version.getECBlocksForLevel(ecLevel);
        int numDataBytes = version.getTotalCodewords() - ecBlocks.getTotalECCodewords();

        // Terminate the bits properly without padding.
        terminateBits(numDataBytes, headerAndDataBits);

        // length for bit
        mTotalLength = version.getTotalCodewords() * 8;
        mHeadAndDataLength = headerAndDataBits.getSize();
        mPaddingLength = numDataBytes * 8  - mHeadAndDataLength;
        mRSCodeLength = ecBlocks.getTotalECCodewords() * 8;

        //
        QRCode qrCode = new QRCode();

        qrCode.setECLevel(ecLevel);
        qrCode.setMode(mode);
        qrCode.setVersion(version);

        //  Choose the mask pattern and set to "qrCode".
        int dimension = version.getDimensionForVersion();
        ByteMatrix matrix = new ByteMatrix(dimension, dimension);
        basicChange = new ByteMatrix(dimension, dimension);
        MatrixUtil.clearMatrix(basicChange);
        basicNotChange = new ByteMatrix(dimension, dimension);
        MatrixUtil.clearMatrix(basicNotChange);

        // should choose the MaskPattern according to the idea result
        int maskPattern = 0;
        qrCode.setMaskPattern(maskPattern);

        // Build the matrix and set it to "qrCode".
        MatrixUtil.clearMatrix(matrix);
        MatrixUtil.embedBasicPatterns(version, matrix);
        MatrixUtil.embedBasicPatterns(version, basicNotChange);
        // Type information appear with any version.
        MatrixUtil.embedTypeInfo(ecLevel, maskPattern, matrix);
        MatrixUtil.embedTypeInfo(ecLevel, maskPattern, basicChange);
        //MatrixUtil.embedTypeInfo(ecLevel, maskPattern, basicNotChange);
        // Version info appear if version >= 7.
        MatrixUtil.maybeEmbedVersionInfo(version, matrix);
        MatrixUtil.maybeEmbedVersionInfo(version, basicChange);
        //MatrixUtil.maybeEmbedVersionInfo(version, basicNotChange);

        mFinalDataToData = new int[version.getTotalCodewords() * 8];
        mDataToFinalData = new int[version.getTotalCodewords() * 8];
        // get finalBit to Data mapping
        buildFinalToDataMapping(version.getTotalCodewords(), numDataBytes, ecBlocks.getNumBlocks());
        // get the data to finalBit mapping
        getRevMapFromMap(mFinalDataToData, mDataToFinalData);
        // get the ideal
        mIdeal = getIdealResult();

        // get the finalBit to M(x, y) mapping
        int[] x = new int[mTotalLength], y = new int[mTotalLength];
        MatrixUtil.buildDataBitsIndex(mTotalLength, matrix, x, y);

        // In before interleave order
        BitOfCode[] allBits = new BitOfCode[mTotalLength];
        BitOfCode[] allByte = new BitOfCode[version.getTotalCodewords()];
        BitArray idealBits = new BitArray();
        Mat edge = getEdge();
        Mat salient = getSalient();
        assert edge.width() == matrix.getWidth() * moduleSize && edge.height() == matrix.getHeight() * moduleSize;
        for(int i = 0; i < mTotalLength; i++){
            allBits[i] = new BitOfCode();
            allBits[i].index = i;
            allBits[i].mImportanceValue = 0;
            if(i % 8 == 0){
                allByte[i / 8] = new BitOfCode();
                allByte[i / 8].index = i / 8;
                allByte[i / 8].mImportanceValue = 0;
            }
            if(i < mHeadAndDataLength) {
                allBits[i].mImportanceValue = Integer.MAX_VALUE * 1.0f;
                idealBits.appendBit(headerAndDataBits.get(i));
            }
            else {
                for (int j = 0; j < moduleSize; j++) {
                    for (int k = 0; k < moduleSize; k++) {
                        int IndexI = x[mDataToFinalData[i]] * moduleSize + j, IndexJ = y[mDataToFinalData[i]] * moduleSize + k;
                        allBits[i].mImportanceValue += (pEdge * edge.get(IndexI, IndexJ)[0]) / 255;
                    }
                }
                boolean tmp = mIdeal.get(x[mDataToFinalData[i]], y[mDataToFinalData[i]]);
                if(MaskUtil.getDataMaskBit(maskPattern, x[mDataToFinalData[i]], y[mDataToFinalData[i]]))
                    tmp = !tmp;
                idealBits.appendBit(tmp);
            }
            allByte[i / 8].mImportanceValue += allBits[i].mImportanceValue;
        }

//        Arrays.sort(allByte, new Comparator<BitOfCode>() {
//            @Override
//            public int compare(BitOfCode o1, BitOfCode o2) {
//                return (int)(o2.mImportanceValue - o1.mImportanceValue);
//            }
//        });

        assert idealBits.getSize() == mTotalLength;

        //divide idealBits into blocks
        int numRSBlocks = ecBlocks.getNumBlocks();
        int dataBytesOffset = 0;
        int ecByteOffset = numDataBytes;

        int maxNumDataBytes = 0;
        int maxNumEcBytes = 0;

        // Since, we know the number of reedsolmon blocks, we can initialize the vector with the number.
        ArrayList<BlockPair> blocks = new ArrayList<>(numRSBlocks);


        for (int i = 0; i < numRSBlocks; ++i) {
            int[] numDataBytesInBlock = new int[1];
            int[] numEcBytesInBlock = new int[1];
            getNumDataBytesAndNumECBytesForBlockID(
                    version.getTotalCodewords(), numDataBytes, numRSBlocks, i,
                    numDataBytesInBlock, numEcBytesInBlock);

            int s1 = numDataBytesInBlock[0];
            int s2 = numEcBytesInBlock[0];
            byte[] dataBytes = new byte[s1 + s2];
            idealBits.toBytes(8 * dataBytesOffset, dataBytes, 0, s1);
            idealBits.toBytes(8 * ecByteOffset, dataBytes, s1, s2);
            // contains s1 true and s2 false
            boolean[] flag = getImportanceFlag_New(dataBytesOffset, s1, ecByteOffset, s2, allByte);
            int[] dataBytesInt = new int[dataBytes.length];
            for(int z = 0; z < dataBytes.length; z++) {
                dataBytesInt[z] = dataBytes[z] & 0xFF;
            }
            new ReedSolomonEncoder(GenericGF.QR_CODE_FIELD_256).encodeNoneSym(dataBytesInt, flag, s1);
            for(int z = 0; z < dataBytes.length; z++)
                dataBytes[z] = (byte)dataBytesInt[z];
            new ReedSolomonEncoder(GenericGF.QR_CODE_FIELD_256).encode(dataBytesInt, s2);
            blocks.add(new BlockPair(Arrays.copyOfRange(dataBytes, 0, s1),
                    Arrays.copyOfRange(dataBytes, s1, s1 + s2)));

            maxNumDataBytes = Math.max(maxNumDataBytes, s1);
            maxNumEcBytes = Math.max(maxNumEcBytes, s2);
            dataBytesOffset += numDataBytesInBlock[0];
            ecByteOffset += numEcBytesInBlock[0];
        }
        if (numDataBytes != dataBytesOffset) {
            throw new WriterException("Data bytes does not match offset");
        }

        BitArray result = new BitArray();

        // First, place data blocks.
        for (int i = 0; i < maxNumDataBytes; ++i) {
            for (BlockPair block : blocks) {
                byte[] dataBytes = block.getDataBytes();
                if (i < dataBytes.length) {
                    result.appendBits(dataBytes[i], 8);
                }
            }
        }
        // Then, place error correction blocks.
        for (int i = 0; i < maxNumEcBytes; ++i) {
            for (BlockPair block : blocks) {
                byte[] ecBytes = block.getErrorCorrectionBytes();
                if (i < ecBytes.length) {
                    result.appendBits(ecBytes[i], 8);
                }
            }
        }
        if (version.getTotalCodewords() != result.getSizeInBytes()) {  // Should be same.
            throw new WriterException("Interleaving error: " + version.getTotalCodewords() + " and " +
                    result.getSizeInBytes() + " differ.");
        }

        // Data should be embedded at end.
        MatrixUtil.embedDataBits(result, maskPattern, matrix);
        qrCode.setMatrix(matrix);

        return qrCode;
    }

    /**
     * @return the code point of the table used in alphanumeric mode or
     * -1 if there is no corresponding code in the table.
     */
    int getAlphanumericCode(int code) {
        if (code < ALPHANUMERIC_TABLE.length) {
            return ALPHANUMERIC_TABLE[code];
        }
        return -1;
    }

    public Mode chooseMode(String content) {
        return chooseMode(content, null);
    }

    /**
     * Choose the best mode by examining the content. Note that 'encoding' is used as a hint;
     * if it is Shift_JIS, and the input is only double-byte Kanji, then we return {@link Mode#KANJI}.
     */
    private Mode chooseMode(String content, String encoding) {
        if ("Shift_JIS".equals(encoding)) {
            // Choose Kanji mode if all input are double-byte characters
            return isOnlyDoubleByteKanji(content) ? Mode.KANJI : Mode.BYTE;
        }
        boolean hasNumeric = false;
        boolean hasAlphanumeric = false;
        for (int i = 0; i < content.length(); ++i) {
            char c = content.charAt(i);
            if (c >= '0' && c <= '9') {
                hasNumeric = true;
            } else if (getAlphanumericCode(c) != -1) {
                hasAlphanumeric = true;
            } else {
                return Mode.BYTE;
            }
        }
        if (hasAlphanumeric) {
            return Mode.ALPHANUMERIC;
        }
        if (hasNumeric) {
            return Mode.NUMERIC;
        }
        return Mode.BYTE;
    }

    private boolean isOnlyDoubleByteKanji(String content) {
        byte[] bytes;
        try {
            bytes = content.getBytes("Shift_JIS");
        } catch (UnsupportedEncodingException ignored) {
            return false;
        }
        int length = bytes.length;
        if (length % 2 != 0) {
            return false;
        }
        for (int i = 0; i < length; i += 2) {
            int byte1 = bytes[i] & 0xFF;
            if ((byte1 < 0x81 || byte1 > 0x9F) && (byte1 < 0xE0 || byte1 > 0xEB)) {
                return false;
            }
        }
        return true;
    }

    private int chooseMaskPattern(BitArray bits,
                                         ErrorCorrectionLevel ecLevel,
                                         Version version,
                                         ByteMatrix matrix) throws WriterException {

        int minPenalty = Integer.MAX_VALUE;  // Lower penalty is better.
        int bestMaskPattern = -1;
        // We try all mask patterns to choose the best one.
        for (int maskPattern = 0; maskPattern < QRCode.NUM_MASK_PATTERNS; maskPattern++) {
            MatrixUtil.buildMatrix(bits, ecLevel, version, maskPattern, matrix);
            int penalty = calculateMaskPenalty(matrix);
            if (penalty < minPenalty) {
                minPenalty = penalty;
                bestMaskPattern = maskPattern;
            }
        }
        return bestMaskPattern;
    }

    private Version chooseVersion(int numInputBits, ErrorCorrectionLevel ecLevel) throws WriterException {
        // In the following comments, we use numbers of Version 7-H.
        for (int versionNum = 1; versionNum <= 40; versionNum++) {
            Version version = Version.getVersionForNumber(versionNum);
            // numBytes = 196
            int numBytes = version.getTotalCodewords();
            // getNumECBytes = 130
            Version.ECBlocks ecBlocks = version.getECBlocksForLevel(ecLevel);
            int numEcBytes = ecBlocks.getTotalECCodewords();
            // getNumDataBytes = 196 - 130 = 66
            int numDataBytes = numBytes - numEcBytes;
            int totalInputBytes = (numInputBits + 7) / 8;
            if (numDataBytes >= totalInputBytes) {
                return version;
            }
        }
        throw new WriterException("Data too big");
    }

    /**
     * Terminate bits as described in 8.4.8 and 8.4.9 of JISX0510:2004 (p.24).
     */
    void terminateBits(int numDataBytes, BitArray bits) throws WriterException {
        int capacity = numDataBytes * 8;
        if (bits.getSize() > capacity) {
            throw new WriterException("data bits cannot fit in the QR Code" + bits.getSize() + " > " +
                    capacity);
        }
        for (int i = 0; i < 4 && bits.getSize() < capacity; ++i) {
            bits.appendBit(false);
        }
        // Append termination bits. See 8.4.8 of JISX0510:2004 (p.24) for details.
        // If the last byte isn't 8-bit aligned, we'll add padding bits.
        int numBitsInLastByte = bits.getSize() & 0x07;
        if (numBitsInLastByte > 0) {
            for (int i = numBitsInLastByte; i < 8; i++) {
                bits.appendBit(false);
            }
        }
    }

    /**
     * Get number of data bytes and number of error correction bytes for block id "blockID". Store
     * the result in "numDataBytesInBlock", and "numECBytesInBlock". See table 12 in 8.5.1 of
     * JISX0510:2004 (p.30)
     */
    void getNumDataBytesAndNumECBytesForBlockID(int numTotalBytes,
                                                       int numDataBytes,
                                                       int numRSBlocks,
                                                       int blockID,
                                                       int[] numDataBytesInBlock,
                                                       int[] numECBytesInBlock) throws WriterException {
        if (blockID >= numRSBlocks) {
            throw new WriterException("Block ID too large");
        }
        // numRsBlocksInGroup2 = 196 % 5 = 1
        int numRsBlocksInGroup2 = numTotalBytes % numRSBlocks;
        // numRsBlocksInGroup1 = 5 - 1 = 4
        int numRsBlocksInGroup1 = numRSBlocks - numRsBlocksInGroup2;
        // numTotalBytesInGroup1 = 196 / 5 = 39
        int numTotalBytesInGroup1 = numTotalBytes / numRSBlocks;
        // numTotalBytesInGroup2 = 39 + 1 = 40
        int numTotalBytesInGroup2 = numTotalBytesInGroup1 + 1;
        // numDataBytesInGroup1 = 66 / 5 = 13
        int numDataBytesInGroup1 = numDataBytes / numRSBlocks;
        // numDataBytesInGroup2 = 13 + 1 = 14
        int numDataBytesInGroup2 = numDataBytesInGroup1 + 1;
        // numEcBytesInGroup1 = 39 - 13 = 26
        int numEcBytesInGroup1 = numTotalBytesInGroup1 - numDataBytesInGroup1;
        // numEcBytesInGroup2 = 40 - 14 = 26
        int numEcBytesInGroup2 = numTotalBytesInGroup2 - numDataBytesInGroup2;
        // Sanity checks.
        // 26 = 26
        if (numEcBytesInGroup1 != numEcBytesInGroup2) {
            throw new WriterException("EC bytes mismatch");
        }
        // 5 = 4 + 1.
        if (numRSBlocks != numRsBlocksInGroup1 + numRsBlocksInGroup2) {
            throw new WriterException("RS blocks mismatch");
        }
        // 196 = (13 + 26) * 4 + (14 + 26) * 1
        if (numTotalBytes !=
                ((numDataBytesInGroup1 + numEcBytesInGroup1) *
                        numRsBlocksInGroup1) +
                        ((numDataBytesInGroup2 + numEcBytesInGroup2) *
                                numRsBlocksInGroup2)) {
            throw new WriterException("Total bytes mismatch");
        }

        if (blockID < numRsBlocksInGroup1) {
            numDataBytesInBlock[0] = numDataBytesInGroup1;
            numECBytesInBlock[0] = numEcBytesInGroup1;
        } else {
            numDataBytesInBlock[0] = numDataBytesInGroup2;
            numECBytesInBlock[0] = numEcBytesInGroup2;
        }
    }

    /**
     * Interleave "bits" with corresponding error correction bytes. On success, store the result in
     * "result". The interleave rule is complicated. See 8.6 of JISX0510:2004 (p.37) for details.
     */
    BitArray interleaveWithECBytes(BitArray bits,
                                          int numTotalBytes,
                                          int numDataBytes,
                                          int numRSBlocks) throws WriterException {

        // "bits" must have "getNumDataBytes" bytes of data.
        if (bits.getSizeInBytes() != numDataBytes) {
            throw new WriterException("Number of bits and data bytes does not match");
        }

        // Step 1.  Divide data bytes into blocks and generate error correction bytes for them. We'll
        // store the divided data bytes blocks and error correction bytes blocks into "blocks".
        int dataBytesOffset = 0;
        int maxNumDataBytes = 0;
        int maxNumEcBytes = 0;

        // Since, we know the number of reedsolmon blocks, we can initialize the vector with the number.
        Collection<BlockPair> blocks = new ArrayList<>(numRSBlocks);

        for (int i = 0; i < numRSBlocks; ++i) {
            int[] numDataBytesInBlock = new int[1];
            int[] numEcBytesInBlock = new int[1];
            getNumDataBytesAndNumECBytesForBlockID(
                    numTotalBytes, numDataBytes, numRSBlocks, i,
                    numDataBytesInBlock, numEcBytesInBlock);

            int size = numDataBytesInBlock[0];
            byte[] dataBytes = new byte[size];
            bits.toBytes(8 * dataBytesOffset, dataBytes, 0, size);
            byte[] ecBytes = generateECBytes(dataBytes, numEcBytesInBlock[0]);
            blocks.add(new BlockPair(dataBytes, ecBytes));

            maxNumDataBytes = Math.max(maxNumDataBytes, size);
            maxNumEcBytes = Math.max(maxNumEcBytes, ecBytes.length);
            dataBytesOffset += numDataBytesInBlock[0];
        }
        if (numDataBytes != dataBytesOffset) {
            throw new WriterException("Data bytes does not match offset");
        }

        BitArray result = new BitArray();

        // First, place data blocks.
        for (int i = 0; i < maxNumDataBytes; ++i) {
            for (BlockPair block : blocks) {
                byte[] dataBytes = block.getDataBytes();
                if (i < dataBytes.length) {
                    result.appendBits(dataBytes[i], 8);
                }
            }
        }
        // Then, place error correction blocks.
        for (int i = 0; i < maxNumEcBytes; ++i) {
            for (BlockPair block : blocks) {
                byte[] ecBytes = block.getErrorCorrectionBytes();
                if (i < ecBytes.length) {
                    result.appendBits(ecBytes[i], 8);
                }
            }
        }
        if (numTotalBytes != result.getSizeInBytes()) {  // Should be same.
            throw new WriterException("Interleaving error: " + numTotalBytes + " and " +
                    result.getSizeInBytes() + " differ.");
        }

        return result;
    }

    byte[] generateECBytes(byte[] dataBytes, int numEcBytesInBlock) {
        int numDataBytes = dataBytes.length;
        int[] toEncode = new int[numDataBytes + numEcBytesInBlock];
        for (int i = 0; i < numDataBytes; i++) {
            toEncode[i] = dataBytes[i] & 0xFF;
        }
        new ReedSolomonEncoder(GenericGF.QR_CODE_FIELD_256).encode(toEncode, numEcBytesInBlock);

        byte[] ecBytes = new byte[numEcBytesInBlock];
        for (int i = 0; i < numEcBytesInBlock; i++) {
            ecBytes[i] = (byte) toEncode[numDataBytes + i];
        }
        return ecBytes;
    }

    /**
     * Append mode info. On success, store the result in "bits".
     */
    void appendModeInfo(Mode mode, BitArray bits) {
        bits.appendBits(mode.getBits(), 4);
    }


    /**
     * Append length info. On success, store the result in "bits".
     */
    void appendLengthInfo(int numLetters, Version version, Mode mode, BitArray bits) throws WriterException {
        int numBits = mode.getCharacterCountBits(version);
        if (numLetters >= (1 << numBits)) {
            throw new WriterException(numLetters + " is bigger than " + ((1 << numBits) - 1));
        }
        bits.appendBits(numLetters, numBits);
    }

    /**
     * Append "bytes" in "mode" mode (encoding) into "bits". On success, store the result in "bits".
     */
    void appendBytes(String content,
                            Mode mode,
                            BitArray bits,
                            String encoding) throws WriterException {
        switch (mode) {
            case NUMERIC:
                appendNumericBytes(content, bits);
                break;
            case ALPHANUMERIC:
                appendAlphanumericBytes(content, bits);
                break;
            case BYTE:
                append8BitBytes(content, bits, encoding);
                break;
            case KANJI:
                appendKanjiBytes(content, bits);
                break;
            default:
                throw new WriterException("Invalid mode: " + mode);
        }
    }

    void appendNumericBytes(CharSequence content, BitArray bits) {
        int length = content.length();
        int i = 0;
        while (i < length) {
            int num1 = content.charAt(i) - '0';
            if (i + 2 < length) {
                // Encode three numeric letters in ten bits.
                int num2 = content.charAt(i + 1) - '0';
                int num3 = content.charAt(i + 2) - '0';
                bits.appendBits(num1 * 100 + num2 * 10 + num3, 10);
                i += 3;
            } else if (i + 1 < length) {
                // Encode two numeric letters in seven bits.
                int num2 = content.charAt(i + 1) - '0';
                bits.appendBits(num1 * 10 + num2, 7);
                i += 2;
            } else {
                // Encode one numeric letter in four bits.
                bits.appendBits(num1, 4);
                i++;
            }
        }
    }

    void appendAlphanumericBytes(CharSequence content, BitArray bits) throws WriterException {
        int length = content.length();
        int i = 0;
        while (i < length) {
            int code1 = getAlphanumericCode(content.charAt(i));
            if (code1 == -1) {
                throw new WriterException();
            }
            if (i + 1 < length) {
                int code2 = getAlphanumericCode(content.charAt(i + 1));
                if (code2 == -1) {
                    throw new WriterException();
                }
                // Encode two alphanumeric letters in 11 bits.
                bits.appendBits(code1 * 45 + code2, 11);
                i += 2;
            } else {
                // Encode one alphanumeric letter in six bits.
                bits.appendBits(code1, 6);
                i++;
            }
        }
    }

    void append8BitBytes(String content, BitArray bits, String encoding)
            throws WriterException {
        byte[] bytes;
        try {
            bytes = content.getBytes(encoding);
        } catch (UnsupportedEncodingException uee) {
            throw new WriterException(uee);
        }
        for (byte b : bytes) {
            bits.appendBits(b, 8);
        }
    }

    void appendKanjiBytes(String content, BitArray bits) throws WriterException {
        byte[] bytes;
        try {
            bytes = content.getBytes("Shift_JIS");
        } catch (UnsupportedEncodingException uee) {
            throw new WriterException(uee);
        }
        int length = bytes.length;
        for (int i = 0; i < length; i += 2) {
            int byte1 = bytes[i] & 0xFF;
            int byte2 = bytes[i + 1] & 0xFF;
            int code = (byte1 << 8) | byte2;
            int subtracted = -1;
            if (code >= 0x8140 && code <= 0x9ffc) {
                subtracted = code - 0x8140;
            } else if (code >= 0xe040 && code <= 0xebbf) {
                subtracted = code - 0xc140;
            }
            if (subtracted == -1) {
                throw new WriterException("Invalid byte sequence");
            }
            int encoded = ((subtracted >> 8) * 0xc0) + (subtracted & 0xff);
            bits.appendBits(encoded, 13);
        }
    }

    private void appendECI(CharacterSetECI eci, BitArray bits) {
        bits.appendBits(Mode.ECI.getBits(), 4);
        // This is correct for values up to 127, which is all we need now.
        bits.appendBits(eci.getValue(), 8);
    }

    private Mat getEdge(){
        Mat src = Highgui.imread("result/logo_change_size.bmp");
        Mat gray = new Mat();
        Mat gray_s = new Mat();
        Mat edge_x = new Mat();
        Mat edge_y = new Mat();
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
        Imgproc.medianBlur(gray, gray_s, 3);
        Imgproc.Sobel(gray_s, edge_x, CvType.CV_16S, 0, 1, 3, 1, 0);
        Imgproc.Sobel(gray_s, edge_y, CvType.CV_16S, 1, 0, 3, 1, 0);
        for(int i = 0; i < edge_x.height(); i++){
            for(int j = 0; j < edge_x.width(); j++){
                if(Math.abs(edge_x.get(i, j)[0]) + Math.abs(edge_y.get(i, j)[0]) > 255){
                    edge_x.row(i).col(j).setTo(new Scalar(255));
                }else{
                    edge_x.row(i).col(j).setTo(new Scalar(Math.abs(edge_x.get(i, j)[0]) + Math.abs(edge_y.get(i, j)[0])));
                }
            }
        }
        Highgui.imwrite("result/logo_gray.bmp", gray);
        Highgui.imwrite("result/logo_gray_s.bmp", gray_s);
        Highgui.imwrite("result/logo_edge.bmp", edge_x);
        return edge_x;
    }

    private Mat getSalient(){
        return null;
    }

    void buildFinalToDataMapping(int numTotalBytes,
                                   int numDataBytes,
                                   int numRSBlocks) throws WriterException {
        // "bits" must have "getNumDataBytes" bytes of data.
        if (mFinalDataToData.length != numTotalBytes * 8) {
            throw new WriterException("Mapping buffer size and total bytes does not match");
        }

        // Step 1.  Divide data bytes into blocks and generate error correction bytes for them. We'll
        // store the divided data bytes blocks and error correction bytes blocks into "blocks".
        int dataBytesOffset = 0;
        int rsBytesOffset = 0;
        int maxNumDataBytes = 0;
        int maxNumEcBytes = 0;

        // Since, we know the number of reedsolmon blocks, we can initialize the vector with the number.
        int[] dataOffset = new int[numRSBlocks], rsOffset = new int[numRSBlocks];
        int[] blockDataLength = new int[numRSBlocks], blockRSLength = new int[numRSBlocks];

        for (int i = 0; i < numRSBlocks; ++i) {
            int[] numDataBytesInBlock = new int[1];
            int[] numEcBytesInBlock = new int[1];
            getNumDataBytesAndNumECBytesForBlockID(
                    numTotalBytes, numDataBytes, numRSBlocks, i,
                    numDataBytesInBlock, numEcBytesInBlock);

            blockDataLength[i] = numDataBytesInBlock[0];
            blockRSLength[i] = numEcBytesInBlock[0];

            maxNumDataBytes = Math.max(maxNumDataBytes, blockDataLength[i]);
            maxNumEcBytes = Math.max(maxNumEcBytes, blockRSLength[i]);
            dataOffset[i] = dataBytesOffset;
            rsOffset[i] = rsBytesOffset;
            dataBytesOffset += numDataBytesInBlock[0];
            rsBytesOffset += numEcBytesInBlock[0];
        }
        if (numDataBytes != dataBytesOffset) {
            throw new WriterException("Data bytes does not match offset");
        }

        //BitArray result = new BitArray();
        int count = 0;

        // map the data
        for (int i = 0; i < maxNumDataBytes; ++i) {
            for (int j = 0; j < numRSBlocks; ++j) {
                if (i < blockDataLength[j]) {
                    for(int k = 0; k < 8; k++){
                        mFinalDataToData[count] = (dataOffset[j] + i) * 8 + k;
                        count++;
                    }
                }
            }
        }

        // map the rs
        for (int i = 0; i < maxNumEcBytes; ++i) {
            for (int j = 0; j < numRSBlocks; ++j) {
                if (i < blockRSLength[j]) {
                    for(int k = 0; k < 8; k++){
                        mFinalDataToData[count] = (numDataBytes + rsOffset[j] + i) * 8 + k;
                        count++;
                    }
                }
            }
        }
        if (mTotalLength != count) {  // Should be same.
            throw new WriterException("Interleaving error: " + numTotalBytes + " and " +
                    count + " differ.");
        }
    }

    BitMatrix getIdealResult() throws IOException, NotFoundException, FormatException{
        String file = "result/logo_ideal.bmp";
        BufferedImage image;

        image = ImageIO.read(new File(file));
        LuminanceSource source = new BufferedImageLuminanceSource(image);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        DetectorResult detectorResult = new Detector(bitmap.getBlackMatrix()).detect();
        return detectorResult.getBits();
    }

    private void getRevMapFromMap (int[] map, int[] rev) throws WriterException{
        //ensure the map is a permutation
        class Permute{
            int index;
            int map;

            Permute(){
            }
        }
        Permute[] a = new Permute[map.length];
        for(int i = 0; i < map.length; i++){
            a[i] = new Permute();
            a[i].index = i;
            a[i].map = map[i];
        }
        Arrays.sort(a, new Comparator<Permute>() {
            @Override
            public int compare(Permute o1, Permute o2) {
                return o1.map - o2.map;
            }
        });
        for(int i = 0; i < map.length; i++){
            if(a[i].map != i)
                throw new WriterException("getRevMapFromMap: map is not a permutation");
            rev[i] = a[i].index;
        }
    }

    private boolean[] getImportanceFlag(int dataOffset, int dataLength, int ecOffset, int ecLength, BitOfCode[] iv){
        boolean[] result = new boolean[dataLength + ecLength];
        for(int i = 0; i < dataLength; i++)
            result[i] = true;
        for(int i = 0; i < ecLength; i++)
            result[dataLength + i] = false;
        return result;
    }

    private boolean[] getImportanceFlag_New(int dataOffset, int dataLength, int ecOffset, int ecLength, BitOfCode[] iv){
        boolean[] result = new boolean[dataLength + ecLength];
        BitOfCode[] current = new BitOfCode[dataLength + ecLength];
        int allDataByte = (mHeadAndDataLength + mPaddingLength) / 8;
        for(int i = 0; i < dataLength; i++) {
            current[i] = new BitOfCode();
            current[i].index = i;
            current[i].mImportanceValue = iv[dataOffset + i].mImportanceValue;
        }
        for(int i = 0; i < ecLength; i++) {
            current[dataLength + i] = new BitOfCode();
            current[dataLength + i].index = dataLength + i;
            current[dataLength + i].mImportanceValue = iv[ecOffset + i].mImportanceValue;
        }
        Arrays.sort(current, new Comparator<BitOfCode>() {
            @Override
            public int compare(BitOfCode o1, BitOfCode o2) {
                return (int)(o2.mImportanceValue - o1.mImportanceValue);
            }
        });
        for(int i = 0; i < dataLength; i++) {
            result[current[i].index] = true;
        }
        for(int i = 0; i < ecLength; i++) {
            result[current[dataLength + i].index] = false;
        }
        int count = 0;
        for(int i = 0; i < dataLength + ecLength; i++){
            if(result[i])
                count++;
        }
        return result;
    }


    private class BitOfCode{
        int index;
        float mImportanceValue;
    }
}
