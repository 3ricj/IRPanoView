package com.energy.iruvc.ircmd;

import com.energy.iruvc.utils.OnCreateResultCallback;

public abstract class IRCMDBuilder {
    public abstract IRCMD build();

    public abstract IRCMDBuilder setIrcmdType(IRCMDType ircmdType);

    public abstract IRCMDBuilder setIdCamera(long idCamera);

    public abstract IRCMDBuilder setCreateResultCallback(OnCreateResultCallback callback);
}
