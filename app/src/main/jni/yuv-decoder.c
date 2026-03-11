#include <jni.h>

// source: https://github.com/CyberAgent/android-gpuimage

JNIEXPORT void JNICALL Java_com_magnifierglass_NativeYuvDecoder_YUVtoRGBGreyscale(JNIEnv * env, jobject obj, jbyteArray yuv420sp, jint width, jint height, jintArray rgbOut)
{
    /* naive java implementation @source http://stackoverflow.com/a/29963291

     private int[] decodeGreyscale(byte[] yuv420sp, int width, int height) {
        int pixelCount = width * height;
        int[] rgbOut = new int[pixelCount];
        for (int i = 0; i < pixelCount; ++i) {
            int luminance = yuv420sp[i] & 0xFF;
            // rgbOut[i] = Color.argb(0xFF, luminance, luminance, luminance);
            rgbOut[i] = 0xff000000 | luminance <<16 | luminance <<8 | luminance;//No need to create Color object for each.
        }
        return rgbOut;
    }
   */

    int pixelCount;
    pixelCount = width * height;

    jint *rgbData = (jint*) (*env)->GetPrimitiveArrayCritical(env, rgbOut, 0);
    jbyte* yuv = (jbyte*) (*env)->GetPrimitiveArrayCritical(env, yuv420sp, 0);

    int i;
    int luminance;
    for (i = 0; i < pixelCount; ++i) {
        luminance = yuv[i] & 0xff;
        rgbData[i] = 0xff000000 | luminance <<16 | luminance <<8 | luminance;
    }

    (*env)->ReleasePrimitiveArrayCritical(env, rgbOut, rgbData, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, yuv420sp, yuv, 0);
}
