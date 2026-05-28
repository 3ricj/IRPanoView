package com.energy.irutilslibrary;

import com.energy.irutilslibrary.bean.GainSwitchParam;

/**
 * Minimal JNI bridge to vendor libadvirtemp.so for strict TC001 parity work.
 * This class intentionally keeps the original package/class name so JNI symbols resolve.
 */
public class LibIRTemp {
    static {
        System.loadLibrary("advirtemp");
        System.loadLibrary("advirutils");
    }

    public static class TempDataRes_t {
        public char width;
        public char height;
    }

    public static class Dot_t {
        public int x;
        public int y;
    }

    public static class Area_t {
        public int start_x;
        public int start_y;
        public int width;
        public int height;
    }

    public static class Line_t {
        public int start_x;
        public int start_y;
        public int end_x;
        public int end_y;
    }

    public static class TempInfo_t {
        public char min_temp;
        public char max_temp;
        public char avr_temp;
        public Dot_t max_cord = new Dot_t();
        public Dot_t min_cord = new Dot_t();
    }

    public static class SearchInfo_t {
        public char start_point;
        public char end_point;
    }

    public static class KtInfo_t {
        public char start_addr;
        public char end_addr;
    }

    public static class VtempInfo_t {
        public char Address_CA;
        public short Ktemp;
        public short Btemp;
    }

    public static class CaliPoint_t {
        public int TL;
        public int TH;
    }

    public static class CaliFactor_t {
        public int Kn;
        public int Bn;
    }

    public static class EnvTemp_t {
        public double T_low;
        public double T_high;
    }

    public static class VTempSet_t {
        public int VTemp_Low;
        public int VTemp_High;
    }

    public static class ReCaliFactor_t {
        public int K;
        public int B;
    }

    public static class EnvParam_t {
        public int EMS;
        public int TAU;
        public int Ta;
        public int Tu;
    }

    public static class EnvFactor_t {
        public int K_E;
        public int B_E;
    }

    public static class TempThreshold_t {
        public int upper_limit;
        public int lower_limit;
    }

    public static class NucFactor_t {
        public int P0;
        public int P1;
        public int P2;
    }

    public static class Circle_t {
        public Dot_t center;
        public int radius;
    }

    public static native int get_point_temp(byte[] data, TempDataRes_t res, Dot_t dot, char[] outTemp);

    public static native int get_rect_temp(byte[] data, TempDataRes_t res, Area_t area, TempInfo_t outInfo);

    public static native int get_line_temp(byte[] data, TempDataRes_t res, Line_t line, TempInfo_t outInfo);

    public static native int set_side_length(int sideLength);

    public static native int get_side_length();

    private static native int calculate_KE_and_BE(EnvParam_t envParam_t, NucFactor_t nucFactor_t, int i, EnvFactor_t envFactor_t);

    private static native int calculate_Kn_and_Bn(VtempInfo_t vtempInfo_t, KtInfo_t ktInfo_t, CaliPoint_t caliPoint_t, CaliFactor_t caliFactor_t);

    private static native int calculate_new_KE_and_BE(EnvParam_t envParam_t, NucFactor_t nucFactor_t, int i, EnvFactor_t envFactor_t);

    private static native int calculate_new_KE_and_BE_with_nuc_t(EnvParam_t envParam_t, int[] iArr, int i, NucFactor_t nucFactor_t);

    private static native int calculate_nuc_with_nuc_factor(NucFactor_t nucFactor_t, float f, int[] iArr);

    private static native int calculate_org_KE_and_BE_with_nuc_t(EnvParam_t envParam_t, int[] iArr, int i, NucFactor_t nucFactor_t);

    private static native char calculate_tau(char c, char c2, char c3, char c4);

    private static native int double_point_recalibrate_KB(CaliFactor_t caliFactor_t, VTempSet_t vTempSet_t, EnvTemp_t envTemp_t, ReCaliFactor_t reCaliFactor_t);

    private static native int env_temp_calculate(CaliFactor_t caliFactor_t, ReCaliFactor_t reCaliFactor_t, char c, double[] dArr);

    private static native int find_start_and_end_addr(char[] cArr, SearchInfo_t searchInfo_t, char c, KtInfo_t ktInfo_t);

    private static native int get_NUC_value(char[] cArr, TempDataRes_t tempDataRes_t, Dot_t dot_t, char[] cArr2);

    private static native int get_circle_temp(byte[] bArr, TempDataRes_t tempDataRes_t, Circle_t circle_t, TempInfo_t tempInfo_t);

    public static native long get_tempinfo(int i, int i2, short[] sArr, short[] sArr2, int i3, int i4, int i5, int i6);

    private static native long get_tempinfo_wn640_v2(int i, int i2, short[] sArr, short[] sArr2, int i3, int i4, int i5);

    private static native void irtemp_log_register(int i);

    private static native String irtemp_version();

    private static native int line_rect_over_threshold_alarm(TempThreshold_t tempThreshold_t, TempInfo_t tempInfo_t);

    private static native int native_gain_switch_detect(byte[] bArr, LibIRProcess.ImageRes_t imageRes_t, GainSwitchParam gainSwitchParam, byte[] bArr2);

    private static native int native_overexposure_detect(byte[] bArr, LibIRProcess.ImageRes_t imageRes_t, int i, float f, byte[] bArr2);

    private static native int point_over_threshold_alarm(TempThreshold_t tempThreshold_t, char c);

    private static native int read_tau(byte[] bArr, float f, float f2, float f3, int[] iArr);

    private static native int read_tau_with_target_temp_and_dist(byte[] bArr, float f, float f2, int[] iArr);

    private static native int recalc_NUC_with_env_correct(EnvFactor_t envFactor_t, int i, int[] iArr);

    private static native int remap_temp(short[] sArr, int i, int[] iArr);

    private static native int reverse_calc_NUC_with_env_correct(NucFactor_t nucFactor_t, double d, int[] iArr);

    private static native int reverse_calc_NUC_with_nuc_t(short[] sArr, float f, int[] iArr);

    private static native int reverse_calc_NUC_without_env_correct(EnvFactor_t envFactor_t, int i, int[] iArr);

    public static native int reverse_enhance_distance_temp_correct(byte[] bArr, int i, float f, float f2, float f3, int i2, int i3, float f4, float[] fArr);

    private static native int reverse_temp_correct(float f, int i, float f2, float f3, float[] fArr);

    private static native int single_point_recalibrate_KB(CaliFactor_t caliFactor_t, int i, double d, ReCaliFactor_t reCaliFactor_t);

    private static native int temp_correct(float f, int i, float f2, float f3, float[] fArr);

    public static native void temp_correction_release(long j, boolean z);

    public static native float temp_correction_with_new_method(float f, byte[] bArr, float f2, float f3, float f4, float f5, float f6);

    public static native float temp_correction_with_origin_method(float f, byte[] bArr, float f2, float f3, float f4, float f5, float f6, long j, int i);

    private static native int write_tau(byte[] bArr, float f, float f2, float f3, int i);
}
