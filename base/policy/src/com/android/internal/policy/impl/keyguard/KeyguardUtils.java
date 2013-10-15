/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.policy.impl.keyguard;

import com.mediatek.common.featureoption.FeatureOption;
import com.mediatek.xlog.Xlog;
import android.os.SystemProperties;
import android.provider.Settings;
import android.content.Context;
///LEWA BEGIN
import com.android.internal.policy.impl.keyguard.KeyguardSecurityModel.SecurityMode;
///LEWA END

public class KeyguardUtils {
///LEWA BEGIN
    private static KeyguardSecurityModel mSecurityModel = null;
///LEWA BEGIN

    public static final boolean isGemini() {
        return FeatureOption.MTK_GEMINI_SUPPORT;
    }
    
    public static final boolean isMediatekVT3G324MSupport() {
        return FeatureOption.MTK_VT3G324M_SUPPORT;
    }
    
    public static final boolean isMediatekGemini3GSwitchSupport() {
        return FeatureOption.MTK_GEMINI_3G_SWITCH;
    }
    
    public static final void xlogD(final String tag, final String logs) {
        Xlog.d(tag, logs);
    }
    
    public static final void xlogI(final String tag, final String logs) {
        Xlog.i(tag, logs);
    }
    
    public static final void xlogE(final String tag, final String logs) {
        Xlog.e(tag, logs);
    }
    
    public static final void xlogW(final String tag, final String logs) {
        Xlog.w(tag, logs);
    }
    
    public static final boolean isTablet() {
        return ("tablet".equals(SystemProperties.get("ro.build.characteristics")));
    }

///LEWA BEGIN
    public static final boolean isLewaLockscreen(Context context){
       if(mSecurityModel == null){
           mSecurityModel = new KeyguardSecurityModel(context);
       }
       return (KeyguardSecurityModel.SecurityMode.None == mSecurityModel.getSecurityMode()) && (Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.LEWALOCKSCREEN_INUSE, 1) > 0);
    }
///LEWA END
}
