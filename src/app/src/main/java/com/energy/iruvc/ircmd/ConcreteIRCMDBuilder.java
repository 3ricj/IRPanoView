package com.energy.iruvc.ircmd;

import com.energy.iruvc.utils.OnCreateResultCallback;

public class ConcreteIRCMDBuilder extends IRCMDBuilder {
    private IRCMD ircmd;

    public ConcreteIRCMDBuilder(boolean standardSdk) {
        this.ircmd = new IRCMD(standardSdk);
    }

    @Override
    public IRCMD build() {
        IRCMD created = ircmd.onCreate();
        this.ircmd = created;
        return created;
    }

    @Override
    public IRCMDBuilder setIrcmdType(IRCMDType ircmdType) {
        ircmd.setIrcmdType(ircmdType);
        return this;
    }

    @Override
    public IRCMDBuilder setIdCamera(long idCamera) {
        ircmd.setId_camera(idCamera);
        return this;
    }

    @Override
    public IRCMDBuilder setCreateResultCallback(OnCreateResultCallback callback) {
        ircmd.setCreateResultCallback(callback);
        return this;
    }
}
