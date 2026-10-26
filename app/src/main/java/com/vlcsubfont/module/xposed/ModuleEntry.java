package com.vlcsubfont.module.xposed;

import com.vlcsubfont.module.FontStore;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class ModuleEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(LoadPackageParam lpp) {
        if (!FontStore.VLC_PACKAGE.equals(lpp.packageName)) {
            return;
        }
        VlcHooker.install(lpp.classLoader);
    }
}
