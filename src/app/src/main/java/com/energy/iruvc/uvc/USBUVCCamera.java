package com.energy.iruvc.uvc;

import android.content.Context;
import com.energy.iruvc.utils.IFrameCallback;
import java.nio.ByteBuffer;

/**
 * Vendor JNI surface mirrored to satisfy RegisterNatives in libUSBUVCCamera/libircmd.
 */
public class USBUVCCamera {
    static {
        System.loadLibrary("USBUVCCamera");
    }

    public static native int nativeBCalibration(long j);

    public static native int nativeConnect(long j, int i, int i2, int i3, int i4, int i5, String str);

    public static native long nativeCreate();

    public static native void nativeDestroy(long j);

    public static native int nativeDestroyIRISPModule(long j);

    public static native float nativeGetAtmosphereTransmitRate(long j);

    public static native float nativeGetEnverionmentTemperature(long j);

    public static native int nativeGetFirmwareVersion(long j);

    public static native int nativeGetObjectDistance(long j);

    public static native float nativeGetObjectEmitRate(long j);

    public static native float nativeGetReflectionTemperature(long j);

    public static native String nativeGetSnCode(long j);

    public static native String nativeGetSupportedSize(long j);

    public static native int nativeInitIRISPModule(
        Context context,
        long j,
        int i,
        boolean z,
        boolean z2,
        boolean z3,
        String str,
        String str2,
        String str3,
        int i2,
        String str4,
        String str5,
        String str6,
        int i3,
        int i4
    );

    public static native int nativeManualShut(long j);

    public static native int nativePausePreview(long j);

    public static native int nativePseudoDestroyGpu(long j);

    public static native int nativePseudoInitGpu(long j, int i, int i2, int[] iArr, int i3);

    public static native int nativePseudoProcessGpu(long j, int i, int i2, float[] fArr, byte[] bArr);

    public static native int nativeRelease(long j);

    public static native int nativeRenewFirmware(long j, ByteBuffer byteBuffer);

    public static native int nativeResumePreview(long j);

    public static native int nativeSetAtmosphereTransmitRate(long j, float f);

    public static native int nativeSetBCParameter(long j, int i, int i2);

    public static native int nativeSetCurVTemp(long j, int i);

    public static native int nativeSetDualFusionEnable(long j, boolean z);

    public static native int nativeSetEnvCorrectParams(long j, int i, int i2, int i3, int i4);

    public static native int nativeSetEnverionmentTemperature(long j, float f);

    public static native int nativeSetFrameCallback(long j, IFrameCallback iFrameCallback);

    public static native int nativeSetObjectDistance(long j, int i);

    public static native int nativeSetObjectEmitRate(long j, float f);

    public static native int nativeSetPreviewSize(long j, int i, int i2, int i3, int i4, int i5, float f);

    public static native int nativeSetReflectionTemperature(long j, float f);

    public static native int nativeSetTempCorrectParams(
        long j,
        byte[] bArr,
        byte[] bArr2,
        short[] sArr,
        short[] sArr2,
        short[] sArr3,
        short[] sArr4,
        short[] sArr5,
        short[] sArr6
    );

    public static native int nativeSetTransformParameter(long j, int i);

    public static native int nativeSetZoomCenterFactor(long j, float f);

    public static native int nativeStartPreview(long j);

    public static native int nativeStopPreview(long j);

    public static native int nativeUpdateAGCV3Params(long j, float f, float f2);

    public static native int nativeUpdateDDEV1Params(long j, float f, float f2);

    public static native int nativeUpdateISPSwitchConfig(long j, boolean z, String str, int i, String str2, String str3, String str4);

    public static native int nativesetRESTORE_TPD(long j);

    public static native int nativesetTPD_RECAL(long j, float f);

    public static native int nativesetTPD_RECAL2(long j, float f, int i);

    public static native int nativesetgetframemode(long j, int i);
}
