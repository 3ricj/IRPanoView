package com.energy.iruvc.ircmd;

import com.energy.iruvc.utils.PreviewStartParam_t;

/**
 * Minimal JNI bridge for reading TC001 TPD properties from vendor libircmd.so.
 */
public class LibIRCMD {
    static {
        System.loadLibrary("ircmd");
    }

    public static native int get_prop_tpd_params(int key, int[] outValue, long cameraHandle);

    public static native int set_prop_tpd_params(int key, int value, long cameraHandle);

    public static native int set_prop_image_params(int key, int value, long cameraHandle);

    public static native int set_prop_auto_shutter_params(int key, int value, long cameraHandle);

    public static native int hand_shake_preview(long cameraHandle);

    public static native int preview_start(PreviewStartParam_t previewStartParam, long cameraHandle);

    public static native int preview_stop(int path, long cameraHandle);

    public static native int zoom_center_down(int path, int step, long cameraHandle);
}
