package com.energy.iruvc.ircmd;

import com.energy.iruvc.utils.CommonParams;
import com.energy.iruvc.utils.OnCreateResultCallback;
import com.energy.iruvc.utils.PreviewStartParam_t;

public class IRCMD {
    private IRCMDType ircmdType;
    private long idCamera = -1L;
    private OnCreateResultCallback createResultCallback;
    private final boolean isStandardSdk;

    public IRCMD(boolean standardSdk) {
        this.isStandardSdk = standardSdk;
    }

    public IRCMD onCreate() {
        if (createResultCallback != null) {
            createResultCallback.onInitResult(
                ircmdType == IRCMDType.USB_IR_256_384 && idCamera != -1L ? ResultCode.SUCCESS : ResultCode.FAILURE
            );
        }
        return this;
    }

    public void setIrcmdType(IRCMDType ircmdType) {
        this.ircmdType = ircmdType;
    }

    public void setId_camera(long idCamera) {
        this.idCamera = idCamera;
    }

    public void setCreateResultCallback(OnCreateResultCallback callback) {
        this.createResultCallback = callback;
    }

    public int startPreview(
        CommonParams.PreviewPathChannel previewPathChannel,
        CommonParams.StartPreviewSource source,
        int fps,
        CommonParams.StartPreviewMode mode,
        CommonParams.DataFlowMode flowMode
    ) {
        PreviewStartParam_t p = new PreviewStartParam_t();
        p.path = (byte) previewPathChannel.getValue();
        p.source = (byte) source.getValue();
        p.fps = (byte) fps;
        p.mode = (byte) mode.getValue();
        if (flowMode == CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT) {
            p.width = 256;
            p.height = 384;
        } else {
            p.width = 256;
            p.height = 192;
        }
        return LibIRCMD.preview_start(p, idCamera);
    }

    public int stopPreview(CommonParams.PreviewPathChannel previewPathChannel) {
        return LibIRCMD.preview_stop(previewPathChannel.getValue(), idCamera);
    }

    public int getPropTPDParams(CommonParams.PropTPDParams propTPDParams, int[] outValue) {
        return LibIRCMD.get_prop_tpd_params(propTPDParams.getValue(), outValue, idCamera);
    }

    public int setPropTPDParams(CommonParams.PropTPDParams propTPDParams, int value) {
        return LibIRCMD.set_prop_tpd_params(propTPDParams.getValue(), value, idCamera);
    }

    public int setPropTPDParams(CommonParams.PropTPDParams propTPDParams, CommonParams.PropTPDParamsValue valueType) {
        int value = valueType.getValue();
        switch (propTPDParams) {
            case TPD_PROP_DISTANCE:
                if (value < 0 || value > 25600) {
                    throw new IllegalArgumentException("The min DISTANCE is 0 and max is 25600");
                }
                break;
            case TPD_PROP_TU:
            case TPD_PROP_TA:
                int[] gain = new int[1];
                if (getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_GAIN_SEL, gain) < 0) {
                    return -1;
                }
                if (gain[0] == 1) {
                    if (value < 230 || value > 500) {
                        throw new IllegalArgumentException("The min TU/TA is 230 and max is 500 in high gain with unit:K");
                    }
                } else if (gain[0] == 0) {
                    if (value < 230 || value > 900) {
                        throw new IllegalArgumentException("The min TU/TA is 230 and max is 900 in low gain with unit:K");
                    }
                }
                break;
            case TPD_PROP_EMS:
                if (value < 1 || value > 128) {
                    throw new IllegalArgumentException("The min EMS is 1 and max is 128");
                }
                break;
            case TPD_PROP_TAU:
                if (value < 1 || value > 128) {
                    throw new IllegalArgumentException("The min TAU is 1 and max is 128");
                }
                break;
            case TPD_PROP_GAIN_SEL:
                break;
            case TPD_NUC_BYPASS:
                break;
            default:
                throw new RuntimeException("Invalid parameter.");
        }
        return LibIRCMD.set_prop_tpd_params(propTPDParams.getValue(), value, idCamera);
    }

    public int setPropImageParams(CommonParams.PropImageParams propImageParams, CommonParams.PropImageParamsValue valueType) {
        int value = valueType.getValue();
        switch (propImageParams) {
            case IMAGE_PROP_LEVEL_DDE:
                if (value < 0 || value > 4) {
                    throw new IllegalArgumentException("The min DDE is 0 and max is 4");
                }
                break;
            case IMAGE_PROP_LEVEL_CONTRAST:
                if (value < 0 || value > 255) {
                    throw new IllegalArgumentException("The min CONTRAST is 0 and max is 255");
                }
                break;
            case IMAGE_PROP_ONOFF_AGC:
            case IMAGE_PROP_SEL_MIRROR_FLIP:
                break;
            default:
                throw new RuntimeException("Invalid parameter.");
        }
        return LibIRCMD.set_prop_image_params(propImageParams.getValue(), value, idCamera);
    }

    public int setPropAutoShutterParameter(
        CommonParams.PropAutoShutterParameter parameter,
        CommonParams.PropAutoShutterParameterValue valueType
    ) {
        return LibIRCMD.set_prop_auto_shutter_params(parameter.getValue(), valueType.getValue(), idCamera);
    }

    public int zoomCenterDown(CommonParams.PreviewPathChannel previewPathChannel, CommonParams.ZoomScaleStep zoomScaleStep) {
        return LibIRCMD.zoom_center_down(previewPathChannel.getValue(), zoomScaleStep.getValue(), idCamera);
    }
}
