package com.magnifierglass;

public class NativeYuvDecoder {

    static {
        System.loadLibrary("yuv-decoder");
    }

    public static native void YUVtoRGBGreyscale(byte[] yuv, int width, int height, int[] out);
}
