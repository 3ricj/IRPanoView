package com.energy.iruvc.utils;

/**
 * Focused subset of APK CommonParams needed for TC001 parity sequencing.
 */
public final class CommonParams {
    private CommonParams() {}

    public enum PreviewPathChannel {
        PREVIEW_PATH0(0);

        private final int value;

        PreviewPathChannel(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public enum StartPreviewSource {
        SOURCE_SENSOR(0);

        private final int value;

        StartPreviewSource(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public enum StartPreviewMode {
        VOC_DVP_MODE(0);

        private final int value;

        StartPreviewMode(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public enum DataFlowMode {
        IMAGE_AND_TEMP_OUTPUT(0),
        IMAGE_OUTPUT(1);

        private final int value;

        DataFlowMode(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public enum PropTPDParams {
        TPD_PROP_DISTANCE(0),
        TPD_PROP_TU(1),
        TPD_PROP_TA(2),
        TPD_PROP_EMS(3),
        TPD_PROP_TAU(4),
        TPD_PROP_GAIN_SEL(5),
        TPD_PROP_P0(12),
        TPD_PROP_P1(13),
        TPD_PROP_P2(14),
        TPD_NUC_BYPASS(15);

        private final int value;

        PropTPDParams(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public interface PropTPDParamsValue {
        int getValue();

        enum GAINSELStatus implements PropTPDParamsValue {
            GAIN_SEL_LOW(0),
            GAIN_SEL_HIGH(1);

            private final int value;

            GAINSELStatus(int value) {
                this.value = value;
            }

            @Override
            public int getValue() {
                return value;
            }
        }

        enum NUCBYPASSStatus implements PropTPDParamsValue {
            NUC_BYPASS_DISABLE(0),
            NUC_BYPASS_ENABLE(1);

            private final int value;

            NUCBYPASSStatus(int value) {
                this.value = value;
            }

            @Override
            public int getValue() {
                return value;
            }
        }

        final class NumberType implements PropTPDParamsValue {
            private final String value;

            public NumberType(String value) {
                if (value == null) {
                    throw new IllegalArgumentException("The value can not be null");
                }
                this.value = value;
            }

            @Override
            public int getValue() {
                try {
                    return Float.valueOf(value).intValue();
                } catch (NumberFormatException e) {
                    throw new NumberFormatException(e.getMessage());
                }
            }
        }
    }

    public enum PropImageParams {
        IMAGE_PROP_LEVEL_TNR(0),
        IMAGE_PROP_LEVEL_SNR(1),
        IMAGE_PROP_LEVEL_DDE(2),
        IMAGE_PROP_LEVEL_BRIGHTNESS(3),
        IMAGE_PROP_LEVEL_CONTRAST(4),
        IMAGE_PROP_MODE_AGC(5),
        IMAGE_PROP_LEVEL_MAX_GAIN(6),
        IMAGE_PROP_LEVEL_BOS(7),
        IMAGE_PROP_ONOFF_AGC(8),
        IMAGE_PROP_SEL_MIRROR_FLIP(9),
        IMAGE_PROP_SEL_FLYER(10),
        IMAGE_PROP_SEL_ROI(11),
        IMAGE_PROP_LEVEL_DDE_STR(12);

        private final int value;

        PropImageParams(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public interface PropImageParamsValue {
        int getValue();

        enum DDEType implements PropImageParamsValue {
            DDE_0(0),
            DDE_1(1),
            DDE_2(2),
            DDE_3(3),
            DDE_4(4);

            private final int value;

            DDEType(int value) {
                this.value = value;
            }

            @Override
            public int getValue() {
                return value;
            }
        }

        enum MirrorFlipType implements PropImageParamsValue {
            NO_MIRROR_FLIP(0),
            ONLY_MIRROR(1),
            ONLY_FLIP(2),
            MIRROR_FLIP(3);

            private final int value;

            MirrorFlipType(int value) {
                this.value = value;
            }

            @Override
            public int getValue() {
                return value;
            }
        }

        enum StatusSwith implements PropImageParamsValue {
            OFF(0),
            ON(1);

            private final int value;

            StatusSwith(int value) {
                this.value = value;
            }

            @Override
            public int getValue() {
                return value;
            }
        }

        final class NumberType implements PropImageParamsValue {
            private final String value;

            public NumberType(String value) {
                if (value == null) {
                    throw new IllegalArgumentException("The value can not be null");
                }
                this.value = value;
            }

            @Override
            public int getValue() {
                try {
                    return Float.valueOf(value).intValue();
                } catch (NumberFormatException e) {
                    throw new NumberFormatException(e.getMessage());
                }
            }
        }
    }

    public enum PropAutoShutterParameter {
        SHUTTER_PROP_SWITCH(0);

        private final int value;

        PropAutoShutterParameter(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public interface PropAutoShutterParameterValue {
        int getValue();

        enum StatusSwith implements PropAutoShutterParameterValue {
            OFF(0),
            ON(1);

            private final int value;

            StatusSwith(int value) {
                this.value = value;
            }

            @Override
            public int getValue() {
                return value;
            }
        }
    }

    public enum ZoomScaleStep {
        ZOOM_STEP1(1),
        ZOOM_STEP2(2),
        ZOOM_STEP3(3),
        ZOOM_STEP4(4);

        private final int value;

        ZoomScaleStep(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }
}
