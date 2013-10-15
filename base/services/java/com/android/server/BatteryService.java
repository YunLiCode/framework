/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server;

import com.android.internal.app.IBatteryStats;
import com.android.server.am.BatteryStatsService;

import android.app.ActivityManagerNative;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.BroadcastReceiver;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.DropBoxManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Slog;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

import com.mediatek.common.featureoption.FeatureOption; // M

///LEWA BEGIN
import android.content.ContentResolver;
import android.location.LocationManager;
import android.database.Cursor;
import android.content.ContentQueryMap;
import java.util.Observable;
import java.util.Observer;
import android.database.ContentObserver;
import android.os.Handler;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.Date;
import java.util.Calendar;
import android.net.ConnectivityManager;
import android.bluetooth.BluetoothAdapter;
import android.provider.Settings;
import android.location.LocationManager;
import android.provider.Settings.Secure;
import android.net.wifi.WifiManager;
import android.content.ContentResolver;
import android.content.BroadcastReceiver;
import android.provider.Settings.SettingNotFoundException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import android.os.Environment;
import android.os.Message;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.telephony.SignalStrength;
import android.util.SparseArray;
import android.content.IntentFilter;
import android.hardware.SensorManager;
import android.os.BatteryStats;
import android.os.BatteryStats.Uid;
import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.PowerProfile;


import android.os.Build;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
//import android.annotation.LewaHook;

///LEWA END


import com.mediatek.xlog.Xlog;
/**
 * <p>BatteryService monitors the charging status, and charge level of the device
 * battery.  When these values change this service broadcasts the new values
 * to all {@link android.content.BroadcastReceiver IntentReceivers} that are
 * watching the {@link android.content.Intent#ACTION_BATTERY_CHANGED
 * BATTERY_CHANGED} action.</p>
 * <p>The new values are stored in the Intent data and can be retrieved by
 * calling {@link android.content.Intent#getExtra Intent.getExtra} with the
 * following keys:</p>
 * <p>&quot;scale&quot; - int, the maximum value for the charge level</p>
 * <p>&quot;level&quot; - int, charge level, from 0 through &quot;scale&quot; inclusive</p>
 * <p>&quot;status&quot; - String, the current charging status.<br />
 * <p>&quot;health&quot; - String, the current battery health.<br />
 * <p>&quot;present&quot; - boolean, true if the battery is present<br />
 * <p>&quot;icon-small&quot; - int, suggested small icon to use for this state</p>
 * <p>&quot;plugged&quot; - int, 0 if the device is not plugged in; 1 if plugged
 * into an AC power adapter; 2 if plugged in via USB.</p>
 * <p>&quot;voltage&quot; - int, current battery voltage in millivolts</p>
 * <p>&quot;temperature&quot; - int, current battery temperature in tenths of
 * a degree Centigrade</p>
 * <p>&quot;technology&quot; - String, the type of battery installed, e.g. "Li-ion"</p>
 *
 * <p>
 * The battery service may be called by the power manager while holding its locks so
 * we take care to post all outcalls into the activity manager to a handler.
 *
 * FIXME: Ideally the power manager would perform all of its calls into the battery
 * service asynchronously itself.
 * </p>
 */
public final class BatteryService extends Binder {
    private static final String TAG = BatteryService.class.getSimpleName();

    private static final boolean DEBUG = false;

    private static final int BATTERY_SCALE = 100;    // battery capacity is a percentage

    // Used locally for determining when to make a last ditch effort to log
    // discharge stats before the device dies.
    private int mCriticalBatteryLevel;

    private static final int DUMP_MAX_LENGTH = 24 * 1024;
    private static final String[] DUMPSYS_ARGS = new String[] { "--checkin", "-u" };
    private static final String BATTERY_STATS_SERVICE_NAME = "batteryinfo";

    private static final String DUMPSYS_DATA_PATH = "/data/system/";

    // This should probably be exposed in the API, though it's not critical
    private static final int BATTERY_PLUGGED_NONE = 0;

    private final Context mContext;
    private final IBatteryStats mBatteryStats;
    private final Handler mHandler;

    private final Object mLock = new Object();

    /* Begin native fields: All of these fields are set by native code. */
    private boolean mAcOnline;
    private boolean mUsbOnline;
    private boolean mWirelessOnline;
    private int mBatteryStatus;
    private int mBatteryHealth;
    private boolean mBatteryPresent;
    private int mBatteryLevel;
    private int mBatteryVoltage;
    private int mBatteryTemperature;
    private String mBatteryTechnology;
    private boolean mBatteryLevelCritical;
    /* End native fields. */

    private int mLastBatteryStatus;
    private int mLastBatteryHealth;
    private boolean mLastBatteryPresent;
    private int mLastBatteryLevel;
    private int mLastBatteryVoltage;
    private int mLastBatteryTemperature;
    private boolean mLastBatteryLevelCritical;

    private int mInvalidCharger;
    private int mLastInvalidCharger;

    private int mLowBatteryWarningLevel;
    private int mLowBatteryCloseWarningLevel;
    private int mShutdownBatteryTemperature;

    private int mPlugType;
    private int mLastPlugType = -1; // Extra state so we can detect first run

    private long mDischargeStartTime;
    private int mDischargeStartLevel;

    private boolean mUpdatesStopped;

    private Led mLed;

    private boolean mSentLowBatteryBroadcast = false;
    private boolean mIPOShutdown = false;
    private boolean mIPOed = false;
    private boolean mIPOBoot = false;
    private static final String IPO_POWER_ON  = "android.intent.action.ACTION_BOOT_IPO";
    private static final String IPO_POWER_OFF = "android.intent.action.ACTION_SHUTDOWN_IPO";
    private boolean ipo_led_on = false;
    private boolean ipo_led_off = false;
    private boolean LowLevelFlag = false;
    private native void native_update();
///LEWA BEGIN
    private static boolean mBootCompleted=false;
    EstimatingTime mEstimatingTime=new EstimatingTime();
    private static long mBatteryLevelStartTime =0;
    private static long mBatteryLevelConsumeStartTime =0;
    private static long mBatteryDurationTime = 0;
    IntentFilter mBootCompletedFilter = new IntentFilter();
///LEWA END

    public BatteryService(Context context, LightsService lights) {
        mContext = context;
        mHandler = new Handler(true /*async*/);
        mLed = new Led(context, lights);
        mBatteryStats = BatteryStatsService.getService();

        mCriticalBatteryLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);
        mLowBatteryWarningLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryWarningLevel);
        mLowBatteryCloseWarningLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryCloseWarningLevel);
        mShutdownBatteryTemperature = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_shutdownBatteryTemperature);


///LEWA BEGIN
        mBootCompletedFilter.addAction("android.intent.action.BOOT_COMPLETED");
        mContext.registerReceiver(mBootCompletedReceiver, mBootCompletedFilter);
        setLowBatteryCloseWarningLevel();
        registerObservers(mContext.getContentResolver());
///LEWA END

        mPowerSupplyObserver.startObserving("SUBSYSTEM=power_supply");

        // watch for invalid charger messages if the invalid_charger switch exists
        if (new File("/sys/devices/virtual/switch/invalid_charger/state").exists()) {
            mInvalidChargerObserver.startObserving(
                    "DEVPATH=/devices/virtual/switch/invalid_charger");
        }

        // set initial status
        synchronized (mLock) {
            updateLocked();
        }
        
        if (FeatureOption.MTK_IPO_SUPPORT == true) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(IPO_POWER_ON);
            filter.addAction(IPO_POWER_OFF);
            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (IPO_POWER_ON.equals(intent.getAction())) {
                        mIPOShutdown = false;
                        mIPOBoot = true;
                        // Let BatteryService to handle low battery warning.
                        mLastBatteryLevel = mLowBatteryWarningLevel + 1;
                        updateLocked();
                    } else
                        if (IPO_POWER_OFF.equals(intent.getAction())) {
                            mIPOShutdown = true;
                    }
                }
            }, filter);
        }
    }

    void systemReady() {
        // check our power situation now that it is safe to display the shutdown dialog.
        synchronized (mLock) {
            shutdownIfNoPowerLocked();
            shutdownIfOverTempLocked();
        }
    }

    /**
     * Returns true if the device is plugged into any of the specified plug types.
     */
    public boolean isPowered(int plugTypeSet) {
        synchronized (mLock) {
            return isPoweredLocked(plugTypeSet);
        }
    }

    private boolean isPoweredLocked(int plugTypeSet) {
        // assume we are powered if battery state is unknown so
        // the "stay on while plugged in" option will work.
        if (mBatteryStatus == BatteryManager.BATTERY_STATUS_UNKNOWN) {
            return true;
        }
        if ((plugTypeSet & BatteryManager.BATTERY_PLUGGED_AC) != 0 && mAcOnline) {
            return true;
        }
        if ((plugTypeSet & BatteryManager.BATTERY_PLUGGED_USB) != 0 && mUsbOnline) {
            return true;
        }
        if ((plugTypeSet & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0 && mWirelessOnline) {
            return true;
        }
        return false;
    }

    /**
     * Returns the current plug type.
     */
    public int getPlugType() {
        synchronized (mLock) {
            return mPlugType;
        }
    }

    /**
     * Returns battery level as a percentage.
     */
    public int getBatteryLevel() {
        synchronized (mLock) {
            return mBatteryLevel;
        }
    }

    /**
     * Returns true if battery level is below the first warning threshold.
     */
    public boolean isBatteryLow() {
        synchronized (mLock) {
            return mBatteryPresent && mBatteryLevel <= mLowBatteryWarningLevel;
        }
    }

    private void shutdownIfNoPowerLocked() {
        // shut down gracefully if our battery is critically low and we are not powered.
        // wait until the system has booted before attempting to display the shutdown dialog.
        if (mBatteryLevel == 0 && !isPoweredLocked(BatteryManager.BATTERY_PLUGGED_ANY)) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (ActivityManagerNative.isSystemReady()) {
                        if (FeatureOption.MTK_IPO_SUPPORT == true) {
                            SystemProperties.set("sys.ipo.battlow","1");
                        }
                        Intent intent = new Intent(Intent.ACTION_REQUEST_SHUTDOWN);
                        intent.putExtra(Intent.EXTRA_KEY_CONFIRM, false);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
                    }
                }
            });
        }
    }

    private void shutdownIfOverTempLocked() {
        // shut down gracefully if temperature is too high (> 68.0C by default)
        // wait until the system has booted before attempting to display the
        // shutdown dialog.
        if (mBatteryTemperature > mShutdownBatteryTemperature) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (ActivityManagerNative.isSystemReady()) {
                        Intent intent = new Intent(Intent.ACTION_REQUEST_SHUTDOWN);
                        intent.putExtra(Intent.EXTRA_KEY_CONFIRM, false);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
                    }
                }
            });
        }
    }

    private void updateLocked() {
        if (!mUpdatesStopped) {
            // Update the values of mAcOnline, et. all.
            native_update();
            if (FeatureOption.MTK_IPO_SUPPORT == true) {
                if (mIPOShutdown)
                    return;
            }
            // Process the new values.
            processValuesLocked();
        }
    }

    private void processValuesLocked() {
        boolean logOutlier = false;
        long dischargeDuration = 0;

        mBatteryLevelCritical = (mBatteryLevel <= mCriticalBatteryLevel);
        if (mAcOnline) {
            mPlugType = BatteryManager.BATTERY_PLUGGED_AC;
        } else if (mUsbOnline) {
            mPlugType = BatteryManager.BATTERY_PLUGGED_USB;
        } else if (mWirelessOnline) {
            mPlugType = BatteryManager.BATTERY_PLUGGED_WIRELESS;
        } else {
            mPlugType = BATTERY_PLUGGED_NONE;
        }

        if (DEBUG) {
            Slog.d(TAG, "Processing new values: "
                    + "mAcOnline=" + mAcOnline
                    + ", mUsbOnline=" + mUsbOnline
                    + ", mWirelessOnline=" + mWirelessOnline
                    + ", mBatteryStatus=" + mBatteryStatus
                    + ", mBatteryHealth=" + mBatteryHealth
                    + ", mBatteryPresent=" + mBatteryPresent
                    + ", mBatteryLevel=" + mBatteryLevel
                    + ", mBatteryTechnology=" + mBatteryTechnology
                    + ", mBatteryVoltage=" + mBatteryVoltage
                    + ", mBatteryTemperature=" + mBatteryTemperature
                    + ", mBatteryLevelCritical=" + mBatteryLevelCritical
                    + ", mPlugType=" + mPlugType);
        }
		if (mLastBatteryVoltage != mBatteryVoltage) {
			Xlog.d(TAG, "mBatteryVoltage=" + mBatteryVoltage);		
		}
        // Update the battery LED
        mLed.updateLightsLocked();

        // Let the battery stats keep track of the current level.
        try {
            mBatteryStats.setBatteryState(mBatteryStatus, mBatteryHealth,
                    mPlugType, mBatteryLevel, mBatteryTemperature,
                    mBatteryVoltage);
        } catch (RemoteException e) {
            // Should never happen.
        }

        shutdownIfNoPowerLocked();
        shutdownIfOverTempLocked();

        if (mBatteryStatus != mLastBatteryStatus ||
                mBatteryHealth != mLastBatteryHealth ||
                mBatteryPresent != mLastBatteryPresent ||
                mBatteryLevel != mLastBatteryLevel ||
                mPlugType != mLastPlugType ||
                mBatteryVoltage != mLastBatteryVoltage ||
                mBatteryTemperature != mLastBatteryTemperature ||
                mInvalidCharger != mLastInvalidCharger) {

            if (mPlugType != mLastPlugType) {
                if (mLastPlugType == BATTERY_PLUGGED_NONE) {
                    // discharging -> charging

                    // There's no value in this data unless we've discharged at least once and the
                    // battery level has changed; so don't log until it does.
                    if (mDischargeStartTime != 0 && mDischargeStartLevel != mBatteryLevel) {
                        dischargeDuration = SystemClock.elapsedRealtime() - mDischargeStartTime;
                        logOutlier = true;
                        EventLog.writeEvent(EventLogTags.BATTERY_DISCHARGE, dischargeDuration,
                                mDischargeStartLevel, mBatteryLevel);
                        // make sure we see a discharge event before logging again
                        mDischargeStartTime = 0;
                    }
                } else if (mPlugType == BATTERY_PLUGGED_NONE) {
                    // charging -> discharging or we just powered up
                    mDischargeStartTime = SystemClock.elapsedRealtime();
                    mDischargeStartLevel = mBatteryLevel;
                }
            }
            if (mBatteryStatus != mLastBatteryStatus ||
                    mBatteryHealth != mLastBatteryHealth ||
                    mBatteryPresent != mLastBatteryPresent ||
                    mPlugType != mLastPlugType) {
                EventLog.writeEvent(EventLogTags.BATTERY_STATUS,
                        mBatteryStatus, mBatteryHealth, mBatteryPresent ? 1 : 0,
                        mPlugType, mBatteryTechnology);
            }
            if (mBatteryLevel != mLastBatteryLevel ||
                    mBatteryVoltage != mLastBatteryVoltage ||
                    mBatteryTemperature != mLastBatteryTemperature) {
                EventLog.writeEvent(EventLogTags.BATTERY_LEVEL,
                        mBatteryLevel, mBatteryVoltage, mBatteryTemperature);
            }
            if (mBatteryLevelCritical && !mLastBatteryLevelCritical &&
                    mPlugType == BATTERY_PLUGGED_NONE) {
                // We want to make sure we log discharge cycle outliers
                // if the battery is about to die.
                dischargeDuration = SystemClock.elapsedRealtime() - mDischargeStartTime;
                logOutlier = true;
            }

            final boolean plugged = mPlugType != BATTERY_PLUGGED_NONE;
            final boolean oldPlugged = mLastPlugType != BATTERY_PLUGGED_NONE;

            /* The ACTION_BATTERY_LOW broadcast is sent in these situations:
             * - is just un-plugged (previously was plugged) and battery level is
             *   less than or equal to WARNING, or
             * - is not plugged and battery level falls to WARNING boundary
             *   (becomes <= mLowBatteryWarningLevel).
             */
            final boolean sendBatteryLow = !plugged
                    && mBatteryStatus != BatteryManager.BATTERY_STATUS_UNKNOWN
                    && mBatteryLevel <= mLowBatteryWarningLevel
                    && (oldPlugged || mLastBatteryLevel > mLowBatteryWarningLevel
                    || (!oldPlugged && mLastBatteryLevel <= mLowBatteryWarningLevel)); 

///LEWA BEGIN
            extraProcessValues();           
///LEWA END

            sendIntentLocked();

            // Separate broadcast is sent for power connected / not connected
            // since the standard intent will not wake any applications and some
            // applications may want to have smart behavior based on this.
            if (mPlugType != 0 && mLastPlugType == 0) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Intent statusIntent = new Intent(Intent.ACTION_POWER_CONNECTED);
                        statusIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                        mContext.sendBroadcastAsUser(statusIntent, UserHandle.ALL);
                    }
                });
            }
            else if (mPlugType == 0 && mLastPlugType != 0) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Intent statusIntent = new Intent(Intent.ACTION_POWER_DISCONNECTED);
                        statusIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                        mContext.sendBroadcastAsUser(statusIntent, UserHandle.ALL);
                    }
                });
            }

            if (sendBatteryLow) {
                mSentLowBatteryBroadcast = true;
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Intent statusIntent = new Intent(Intent.ACTION_BATTERY_LOW);
                        statusIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                        mContext.sendBroadcastAsUser(statusIntent, UserHandle.ALL);
                    }
                });
            } else if (mSentLowBatteryBroadcast && mLastBatteryLevel >= mLowBatteryCloseWarningLevel) {
                mSentLowBatteryBroadcast = false;
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Intent statusIntent = new Intent(Intent.ACTION_BATTERY_OKAY);
                        statusIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                        mContext.sendBroadcastAsUser(statusIntent, UserHandle.ALL);
                    }
                });
            }

            // Update the battery LED
            // mLed.updateLightsLocked();

            // This needs to be done after sendIntent() so that we get the lastest battery stats.
            if (logOutlier && dischargeDuration != 0) {
                logOutlierLocked(dischargeDuration);
            }

            mLastBatteryStatus = mBatteryStatus;
            mLastBatteryHealth = mBatteryHealth;
            mLastBatteryPresent = mBatteryPresent;
            mLastBatteryLevel = mBatteryLevel;
            mLastPlugType = mPlugType;
            mLastBatteryVoltage = mBatteryVoltage;
            mLastBatteryTemperature = mBatteryTemperature;
            mLastBatteryLevelCritical = mBatteryLevelCritical;
            mLastInvalidCharger = mInvalidCharger;
        }
    }

    private void sendIntentLocked() {
        //  Pack up the values and broadcast them to everyone
        final Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);

        int icon = getIconLocked(mBatteryLevel);

        intent.putExtra(BatteryManager.EXTRA_STATUS, mBatteryStatus);
        intent.putExtra(BatteryManager.EXTRA_HEALTH, mBatteryHealth);
        intent.putExtra(BatteryManager.EXTRA_PRESENT, mBatteryPresent);
        intent.putExtra(BatteryManager.EXTRA_LEVEL, mBatteryLevel);
        intent.putExtra(BatteryManager.EXTRA_SCALE, BATTERY_SCALE);
        intent.putExtra(BatteryManager.EXTRA_ICON_SMALL, icon);
        intent.putExtra(BatteryManager.EXTRA_PLUGGED, mPlugType);
        intent.putExtra(BatteryManager.EXTRA_VOLTAGE, mBatteryVoltage);
        intent.putExtra(BatteryManager.EXTRA_TEMPERATURE, mBatteryTemperature);
        intent.putExtra(BatteryManager.EXTRA_TECHNOLOGY, mBatteryTechnology);
        intent.putExtra(BatteryManager.EXTRA_INVALID_CHARGER, mInvalidCharger);

///LEWA BEGIN
        putExtraBatteryInfo(intent);     
///LEWA END

        if (DEBUG) {
            Slog.d(TAG, "Sending ACTION_BATTERY_CHANGED.  level:" + mBatteryLevel +
                    ", scale:" + BATTERY_SCALE + ", status:" + mBatteryStatus +
                    ", health:" + mBatteryHealth +  ", present:" + mBatteryPresent +
                    ", voltage: " + mBatteryVoltage +
                    ", temperature: " + mBatteryTemperature +
                    ", technology: " + mBatteryTechnology +
                    ", AC powered:" + mAcOnline + ", USB powered:" + mUsbOnline +
                    ", Wireless powered:" + mWirelessOnline +
                    ", icon:" + icon  + ", invalid charger:" + mInvalidCharger);
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                ActivityManagerNative.broadcastStickyIntent(intent, null, UserHandle.USER_ALL);
            }
        });
    }

    private void logBatteryStatsLocked() {
        IBinder batteryInfoService = ServiceManager.getService(BATTERY_STATS_SERVICE_NAME);
        if (batteryInfoService == null) return;

        DropBoxManager db = (DropBoxManager) mContext.getSystemService(Context.DROPBOX_SERVICE);
        if (db == null || !db.isTagEnabled("BATTERY_DISCHARGE_INFO")) return;

        File dumpFile = null;
        FileOutputStream dumpStream = null;
        try {
            // dump the service to a file
            dumpFile = new File(DUMPSYS_DATA_PATH + BATTERY_STATS_SERVICE_NAME + ".dump");
            dumpStream = new FileOutputStream(dumpFile);
            batteryInfoService.dump(dumpStream.getFD(), DUMPSYS_ARGS);
            FileUtils.sync(dumpStream);

            // add dump file to drop box
            db.addFile("BATTERY_DISCHARGE_INFO", dumpFile, DropBoxManager.IS_TEXT);
        } catch (RemoteException e) {
            Slog.e(TAG, "failed to dump battery service", e);
        } catch (IOException e) {
            Slog.e(TAG, "failed to write dumpsys file", e);
        } finally {
            // make sure we clean up
            if (dumpStream != null) {
                try {
                    dumpStream.close();
                } catch (IOException e) {
                    Slog.e(TAG, "failed to close dumpsys output stream");
                }
            }
            if (dumpFile != null && !dumpFile.delete()) {
                Slog.e(TAG, "failed to delete temporary dumpsys file: "
                        + dumpFile.getAbsolutePath());
            }
        }
    }

    private void logOutlierLocked(long duration) {
        ContentResolver cr = mContext.getContentResolver();
        String dischargeThresholdString = Settings.Global.getString(cr,
                Settings.Global.BATTERY_DISCHARGE_THRESHOLD);
        String durationThresholdString = Settings.Global.getString(cr,
                Settings.Global.BATTERY_DISCHARGE_DURATION_THRESHOLD);

        if (dischargeThresholdString != null && durationThresholdString != null) {
            try {
                long durationThreshold = Long.parseLong(durationThresholdString);
                int dischargeThreshold = Integer.parseInt(dischargeThresholdString);
                if (duration <= durationThreshold &&
                        mDischargeStartLevel - mBatteryLevel >= dischargeThreshold) {
                    // If the discharge cycle is bad enough we want to know about it.
                    logBatteryStatsLocked();
                }
                if (DEBUG) Slog.v(TAG, "duration threshold: " + durationThreshold +
                        " discharge threshold: " + dischargeThreshold);
                if (DEBUG) Slog.v(TAG, "duration: " + duration + " discharge: " +
                        (mDischargeStartLevel - mBatteryLevel));
            } catch (NumberFormatException e) {
                Slog.e(TAG, "Invalid DischargeThresholds GService string: " +
                        durationThresholdString + " or " + dischargeThresholdString);
                return;
            }
        }
    }

    private int getIconLocked(int level) {
        if (mBatteryStatus == BatteryManager.BATTERY_STATUS_CHARGING) {
            return com.android.internal.R.drawable.stat_sys_battery_charge;
        } else if (mBatteryStatus == BatteryManager.BATTERY_STATUS_DISCHARGING) {
            return com.android.internal.R.drawable.stat_sys_battery;
        } else if (mBatteryStatus == BatteryManager.BATTERY_STATUS_NOT_CHARGING
                || mBatteryStatus == BatteryManager.BATTERY_STATUS_FULL) {
            if (isPoweredLocked(BatteryManager.BATTERY_PLUGGED_ANY)
                    && mBatteryLevel >= 100) {
                return com.android.internal.R.drawable.stat_sys_battery_charge;
            } else {
                return com.android.internal.R.drawable.stat_sys_battery;
            }
        } else {
            return com.android.internal.R.drawable.stat_sys_battery_unknown;
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {

            pw.println("Permission Denial: can't dump Battery service from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        synchronized (mLock) {
            if (args == null || args.length == 0 || "-a".equals(args[0])) {
                pw.println("Current Battery Service state:");
                if (mUpdatesStopped) {
                    pw.println("  (UPDATES STOPPED -- use 'reset' to restart)");
                }
                pw.println("  AC powered: " + mAcOnline);
                pw.println("  USB powered: " + mUsbOnline);
                pw.println("  Wireless powered: " + mWirelessOnline);
                pw.println("  status: " + mBatteryStatus);
                pw.println("  health: " + mBatteryHealth);
                pw.println("  present: " + mBatteryPresent);
                pw.println("  level: " + mBatteryLevel);
                pw.println("  scale: " + BATTERY_SCALE);
                pw.println("  voltage:" + mBatteryVoltage);
                pw.println("  temperature: " + mBatteryTemperature);
                pw.println("  technology: " + mBatteryTechnology);
            } else if (args.length == 3 && "set".equals(args[0])) {
                String key = args[1];
                String value = args[2];
                try {
                    boolean update = true;
                    if ("ac".equals(key)) {
                        mAcOnline = Integer.parseInt(value) != 0;
                    } else if ("usb".equals(key)) {
                        mUsbOnline = Integer.parseInt(value) != 0;
                    } else if ("wireless".equals(key)) {
                        mWirelessOnline = Integer.parseInt(value) != 0;
                    } else if ("status".equals(key)) {
                        mBatteryStatus = Integer.parseInt(value);
                    } else if ("level".equals(key)) {
                        mBatteryLevel = Integer.parseInt(value);
                    } else if ("invalid".equals(key)) {
                        mInvalidCharger = Integer.parseInt(value);
                    } else {
                        pw.println("Unknown set option: " + key);
                        update = false;
                    }
                    if (update) {
                        mUpdatesStopped = true;
                        processValuesLocked();
                    }
                } catch (NumberFormatException ex) {
                    pw.println("Bad value: " + value);
                }
            } else if (args.length == 1 && "reset".equals(args[0])) {
                mUpdatesStopped = false;
                updateLocked();
            } else {
                pw.println("Dump current battery state, or:");
                pw.println("  set ac|usb|wireless|status|level|invalid <value>");
                pw.println("  reset");
            }
        }
    }

    private final UEventObserver mPowerSupplyObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEventObserver.UEvent event) {
            synchronized (mLock) {
                updateLocked();
            }
        }
    };

    private final UEventObserver mInvalidChargerObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEventObserver.UEvent event) {
            final int invalidCharger = "1".equals(event.get("SWITCH_STATE")) ? 1 : 0;
            synchronized (mLock) {
                if (mInvalidCharger != invalidCharger) {
                    mInvalidCharger = invalidCharger;
                    updateLocked();
                }
            }
        }
    };

    private final class Led {
        private final LightsService.Light mBatteryLight;

        private final int mBatteryLowARGB;
        private final int mBatteryMediumARGB;
        private final int mBatteryFullARGB;
        private final int mBatteryLedOn;
        private final int mBatteryLedOff;

        public Led(Context context, LightsService lights) {
            mBatteryLight = lights.getLight(LightsService.LIGHT_ID_BATTERY);

            mBatteryLowARGB = context.getResources().getInteger(
                    com.android.internal.R.integer.config_notificationsBatteryLowARGB);
            mBatteryMediumARGB = context.getResources().getInteger(
                    com.android.internal.R.integer.config_notificationsBatteryMediumARGB);
            mBatteryFullARGB = context.getResources().getInteger(
                    com.android.internal.R.integer.config_notificationsBatteryFullARGB);
            mBatteryLedOn = context.getResources().getInteger(
                    com.android.internal.R.integer.config_notificationsBatteryLedOn);
            mBatteryLedOff = context.getResources().getInteger(
                    com.android.internal.R.integer.config_notificationsBatteryLedOff);
        }

        /**
         * Synchronize on BatteryService.
         */
        public void updateLightsLocked() {
            final int level = mBatteryLevel;
            final int status = mBatteryStatus;
            if(mIPOBoot)
            {
                //Get led status in IPO mode
                getIpoLedStatus();
            }
            if (level < mLowBatteryWarningLevel) {
                if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
                    updateLedStatus();
                    // Solid red when battery is charging
                    mBatteryLight.setColor(mBatteryLowARGB);
                } else {
                    LowLevelFlag = true;
                    updateLedStatus();
                    // Flash red when battery is low and not charging
                    mBatteryLight.setFlashing(mBatteryLowARGB, LightsService.LIGHT_FLASH_TIMED,
                            mBatteryLedOn, mBatteryLedOff);
                }
            } else if (status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL) {
                if (status == BatteryManager.BATTERY_STATUS_FULL || level >= 90) {
                    updateLedStatus();
                    // Solid green when full or charging and nearly full
                    mBatteryLight.setColor(mBatteryFullARGB);
                } else {
                    updateLedStatus();
                    // Solid orange when charging and halfway full
                    mBatteryLight.setColor(mBatteryMediumARGB);
                }
            } else {
                if(ipo_led_on && mIPOBoot){
                    if (status == BatteryManager.BATTERY_STATUS_FULL || level >= 90) {
                        mBatteryLight.setColor(mBatteryFullARGB);
                    }
                    else {
                        mBatteryLight.setColor(mBatteryMediumARGB);
                    }
                    mIPOBoot = false;
                    ipo_led_on = false;
                }
                // No lights if not charging and not low
                mBatteryLight.turnOff();
            }
        }
        private void getIpoLedStatus() {
            if ("1".equals(SystemProperties.get("sys.ipo.ledon"))) {
                ipo_led_on = true;
            }
            else if ("0".equals(SystemProperties.get("sys.ipo.ledon"))) {
                ipo_led_off = true;
            }
            if (DEBUG) {
                Slog.d(TAG, ">>>>>>>getIpoLedStatus ipo_led_on = "+ipo_led_on +",  ipo_led_off = " +ipo_led_off +"<<<<<<<");
            }
        }
        
        private void updateLedStatus() {
            // if LowBatteryWarning happened, we refresh the led state no matter ipo_led is on or off.
            if((ipo_led_off && mIPOBoot) || (LowLevelFlag && mIPOBoot)){
                mBatteryLight.turnOff();
                mIPOBoot = false;
                ipo_led_off = false;
                ipo_led_on = false;
                if (DEBUG) {
                    Slog.d(TAG, ">>>>>>>updateLedStatus  LowLevelFlag = "+LowLevelFlag +"<<<<<<<");
                }
            }
        }
    }

///LEWA BEGIN
    //@LewaHook(LewaHook.LewaHookType.NEW_METHOD)
    private void registerObservers(ContentResolver contentResolver) {
        contentResolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.POWE_LOW_VAL),
                    false,mBatteryLowValObserver);
    }

    //@LewaHook(LewaHook.LewaHookType.NEW_METHOD)
    private void setLowBatteryCloseWarningLevel () {
        mLowBatteryWarningLevel = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.POWE_LOW_VAL, mLowBatteryWarningLevel);
        mLowBatteryCloseWarningLevel = mLowBatteryWarningLevel + 5;
    }

    //@LewaHook(LewaHook.LewaHookType.NEW_METHOD)
    private void extraProcessValues() {
        if(mBootCompleted){
            long  currDate = SystemClock.elapsedRealtime();
            mEstimatingTime.estimateDurationTime();
            if(mPlugType != BATTERY_PLUGGED_NONE){
                mBatteryLevelConsumeStartTime = 0;
                //Log.i(TAG,"processValues mBatteryDurationTime="+mBatteryDurationTime);
                if(mLastBatteryLevel == (mBatteryLevel-1)){
                    if(mBatteryLevelStartTime != 0){
                        long diff = (currDate - mBatteryLevelStartTime) / 1000;
                        if( (diff > 0) && (diff < 7200) ){
                            mEstimatingTime.updateTimeBasedOnChargingMode(mPlugType,
                                  mLastBatteryLevel,
                                  diff);
                        }
                    }
                    mBatteryLevelStartTime = currDate;
                }else if( mLastBatteryLevel != mBatteryLevel ){
                    mBatteryLevelStartTime = 0;
                }
            }else{
                mBatteryLevelStartTime = 0;
                if( mLastBatteryLevel == (mBatteryLevel + 1) ){
                    mBatteryLevelConsumeStartTime = currDate;
                }else{
                    mBatteryLevelConsumeStartTime = 0;
                }
                //Log.i(TAG,"processValues mBatteryDurationTime="+mBatteryDurationTime);
            }

        }else{
            if(mPlugType!=BATTERY_PLUGGED_NONE){
                mBatteryDurationTime = mEstimatingTime.estimateInitChargingTime(mPlugType,mBatteryLevel);
            }else{
                mBatteryDurationTime = 0;
            }
        }
    }

    //@LewaHook(LewaHook.LewaHookType.NEW_METHOD)
    private void putExtraBatteryInfo(Intent intent) {
        //Log.i(TAG,"sendIntent mBootCompleted="+mBootCompleted);
        intent.putExtra("batteryInfo", mBatteryDurationTime);
        //Log.i(TAG,"sendIntent mBatteryDurationTime="+mBatteryDurationTime); 
    }

    
    //@LewaHook(LewaHook.LewaHookType.NEW_CLASS)
    private ContentObserver mBatteryLowValObserver = new ContentObserver(new Handler()) {

        @Override
        public void onChange(boolean selfChange) {
          
            mLowBatteryWarningLevel = Settings.System.getInt(mContext.getContentResolver(),
            				Settings.System.POWE_LOW_VAL,
            				mLowBatteryWarningLevel);
            mLowBatteryCloseWarningLevel = mLowBatteryWarningLevel+5;
        
        }
    };
    
    //@LewaHook(LewaHook.LewaHookType.NEW_CLASS)
    private BroadcastReceiver mBootCompletedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("android.intent.action.BOOT_COMPLETED")) {
                mBootCompleted = true;
                mEstimatingTime.init();
            } 
        }
    };
     private class EstimatingTime{

        
        public void init(){
            if(!mBootCompleted){
                return;
            }
            boolean inited=(Settings.System.getInt(mContext.getContentResolver(),
                					Settings.System.POWER_CHARGING_INIT,
                					0)==1);
            if(!inited){
                saveAc();
                saveUsb();
                Settings.System.putInt(mContext.getContentResolver(),
                					Settings.System.POWER_CHARGING_INIT,
                					1);
            }
            
        }
        public long estimateDurationTime(){
            init();
            if(mPlugType == BatteryManager.BATTERY_PLUGGED_USB||
                mPlugType == BatteryManager.BATTERY_PLUGGED_AC){
                mBatteryDurationTime = estimateChargingInTime();
            }else{
                mBatteryDurationTime = estimateChargingOutTime();
            }
            Log.e("Batteryservice","estimateDurationTime--------------------mBatteryDurationTime="+mBatteryDurationTime);
            return mBatteryDurationTime;
        }

        private long estimateChargingInTime(){
            long  currDate = SystemClock.elapsedRealtime();
            long duration=estimateChargingTime(mPlugType,mBatteryLevel);
            if(mBatteryLevelStartTime != 0){
                long diff = (currDate - mBatteryLevelStartTime) / 1000;
                if(diff > 0 ){
                    duration -= diff;
                }
            }
            if(duration <= 0){
                /*throw new RuntimeException("estimateChargingInTime time should be greater than 0"
                    +"  duration="+duration
                    +"  mBatteryLevelStartTime="+mBatteryLevelStartTime
                    +"  currDate="+currDate);
                    */
                duration = 300;//minimal 5 minute
            }
            duration = Math.round(duration/60);//second to minute
            return duration;

        }
        private long estimateChargingOutTime(){
            long  currDate = SystemClock.elapsedRealtime();
            long duration = Math.round(getPowerConsume());
            if(mBatteryLevelConsumeStartTime > 0){
    		    duration -= (currDate - mBatteryLevelConsumeStartTime) / 1000/60;
            }
            if(duration <= 0){
                /*throw new RuntimeException("estimateChargingOutTime time should be greater than 0"
                    +"  duration="+duration
                    +"  mBatteryLevelStartTime="+mBatteryLevelStartTime
                    +"  currDate="+currDate);
                    */
                    duration = 5;//minimal 5 minute
            }
            return duration;
        }
    	private void saveAc() {
  
            String str = mContext.getResources().getString(
                    com.android.internal.R.string.config_initial_charge_times_ac);
            
            //Log.i(TAG,"saveAc str ="+str);
             
            Pattern p = Pattern.compile("\\s*|\t|\r|\n");
            
            Matcher m = p.matcher(str);

            str = m.replaceAll("");

            //Log.i(TAG,"saveAc str ="+str);

            Settings.System.putString(mContext.getContentResolver(),
            					Settings.System.POWER_CHARGING_VAL_AC,
            					str);
    	}

    	private void saveUsb() {
            String str = mContext.getResources().getString(
                    com.android.internal.R.string.config_initial_charge_times_usb);
            
            //Log.i(TAG,"saveUsb str ="+str);
            
            Pattern p = Pattern.compile("\\s*|\t|\r|\n");
            
            Matcher m = p.matcher(str);

            str = m.replaceAll("");

            //Log.i(TAG,"saveUsb str ="+str );
            Settings.System.putString(mContext.getContentResolver(),
            					Settings.System.POWER_CHARGING_VAL_USB,
            					str);
    	}
        
        public long estimateChargingTime(int plugType,int level){
    		long chargingTime = 0;
            
            String spName=Settings.System.POWER_CHARGING_VAL_AC;
            
            if( mPlugType == BatteryManager.BATTERY_PLUGGED_USB){
                
                spName=Settings.System.POWER_CHARGING_VAL_USB;
            }
            
    		String arr=Settings.System.getString(mContext.getContentResolver(),
                					spName);
            
            //Log.e(TAG,"estimateChargingTime spName="+spName+"   arr="+arr);

            chargingTime=getSumTime(arr,level);
            
            //Log.e(TAG,"estimateChargingTime chargingTime="+chargingTime);
    		return chargingTime;
    	}

    	public long estimateInitChargingTime(int plugType,int level){
            String initTimes;
            if( mPlugType == BatteryManager.BATTERY_PLUGGED_USB){
                
                initTimes = mContext.getResources().getString(
                    com.android.internal.R.string.config_initial_charge_times_usb);
                
            }else{
            
                initTimes = mContext.getResources().getString(
                    com.android.internal.R.string.config_initial_charge_times_ac);

            }

            Pattern p = Pattern.compile("\\s*|\t|\r|\n");  
            Matcher m = p.matcher(initTimes);
            initTimes = m.replaceAll("");
            
            return getSumTime(initTimes,level);
    	}
    private long getSumTime(String times,int start_level){

            int idx = times.indexOf(',',0); 
            
            int pre_idx = 0;
            
            int i = 0;
             
            long []levels = new long[101];
            
            long sum = 0;
             
            while(idx >= 0){
                
                String sb = times.substring(pre_idx,idx);
                 
                levels[i] = Long.valueOf(sb);
                 
                pre_idx = idx+1;
                 
                idx = times.indexOf(',',pre_idx);
                 
                i++;
            }
            for (i = start_level; i < 100; i ++){
                
    			 sum += levels[i];
    		}
            return sum;
        }
        
        private void updateTimeBasedOnChargingMode(int type,int level, long time) {
    		int count = 0;
            
    		long oldTime = 0;
            
            String spName=null;
            
    		if (type == BatteryManager.BATTERY_PLUGGED_AC) {
                
    		    spName = Settings.System.POWER_CHARGING_VAL_AC;
                
    		} else if ( type == BatteryManager.BATTERY_PLUGGED_USB) {
    		
    			spName = Settings.System.POWER_CHARGING_VAL_USB;
    		}
            
            int i=0;
            
            long []level_time=new long[101];
            
            String arr=Settings.System.getString(mContext.getContentResolver(),spName);
            //Log.v(TAG,"updateTimeBasedOnChargingMode level="+level+"   time="+time);
        
            //Log.v(TAG,"updateTimeBasedOnChargingMode arr="+arr);
            
            if(arr!=null){
                
                int idx = arr.indexOf(',',0);
                
                int pre_idx = 0;
                
                while(idx >= 0){
                    
                    level_time[i] = Long.valueOf(arr.substring(pre_idx,idx));
                    
                    pre_idx = idx+1;
                    
                    idx = arr.indexOf(',',pre_idx);
                    
                    i++;
                }
                
                level_time[level] = (level_time[level]+time)/2;
                
                arr="";
                
                for(i = 0;i < 100;i++){
                    
                    arr += String.valueOf(level_time[i]);
                    
                    arr += ",";
                }
            Settings.System.putString(mContext.getContentResolver(),spName,arr);
            }
    	}

        public boolean isDataOn() {
    		ConnectivityManager connectManager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    		return connectManager.getMobileDataEnabled();
    	}
        private boolean bluetoothStateToFiveState(int bluetoothState) {
		boolean flag = false;
    		switch (bluetoothState) {
    		case BluetoothAdapter.STATE_OFF:
    			flag = false;
    			break;
    		case BluetoothAdapter.STATE_ON:
    			flag = true;
    			break;
    		case BluetoothAdapter.STATE_TURNING_ON:
    			flag = false;
    			break;
    		case BluetoothAdapter.STATE_TURNING_OFF:
    			flag = false;
    			break;
    		default:
    			break;
    		}
    		return flag;
    	}

        public boolean isBluetoothOn() {
    		BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    		return bluetoothStateToFiveState(mBluetoothAdapter.getState());
    	}
        public boolean isFlyModeOn() {
    		return (Settings.System.getInt(mContext.getContentResolver() , Settings.System.AIRPLANE_MODE_ON,0)==1);	
    	}
        public boolean isGpsOn() {
    		LocationManager myLocationManager = (LocationManager )mContext.getSystemService(Context.LOCATION_SERVICE);
    		return myLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    	}
        public boolean isHapticOn() {
    		return (Settings.System.getInt(mContext.getContentResolver(),
    				Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) == 1);
    	}
        public boolean isWifiOn() {
    		WifiManager wm = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
    		boolean flag = false;
    		switch (wm.getWifiState()) {
    		case WifiManager.WIFI_STATE_DISABLED:
    			flag = false;
    			break;
    		case WifiManager.WIFI_STATE_DISABLING:
    			flag = false;
    			break;
    		case WifiManager.WIFI_STATE_ENABLED:
    			flag = true;
    			break;
    		case WifiManager.WIFI_STATE_ENABLING:
    			flag = true;
    			break;
    		case WifiManager.WIFI_STATE_UNKNOWN:
    			break;
    		default:
    			break;
    		}
    		return flag;
    	
    	}
        public boolean isScreenAuto() {
    		boolean automicBrightness = false;
            try {
                automicBrightness = Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
            } catch (SettingNotFoundException e) {
                e.printStackTrace();
            }
            return automicBrightness;
    	}
        public int getAdjustValue() {
            int nowBrightnessValue = 20;
            try {
    			nowBrightnessValue = android.provider.Settings.System.getInt(mContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
    		} catch (SettingNotFoundException e) {
    			e.printStackTrace();
    		}
    		return nowBrightnessValue;
    	}
        public boolean isAutoSyncOn() {
    		return ContentResolver.getMasterSyncAutomatically();
    	}
        

        private int getBatteryCapacity(){
            return 2050;
        }
        int getIdleConsume(){
            return 20+3*4;
        }
        int getFlyConsume(){
            return  7;
        }
        int getWifiOnConsume(){
            return  1;
        }
        int getWifiActiveConsume(){
            return  150;
        }
        int getBluetoothOnConsume(){
            return  1;
        }
        int getBluetoothActiveConsume(){
            return  1;
        }
        int getGPSActiveConsume(){
            return  55;
        }
        int getHapticActiveConsume(){
            return  150;
        }
        int getScreenConsume(){
            return 200;
        }
        int getTotalLevelSummary(){
            return 160;
        }
        int getDataConsume(){
            return 150;
        }
        public double getPowerConsume() {
            //long uSecTime = SystemClock.elapsedRealtime() * 1000;
            //long batteryTime=mStatsImpl.computeBatteryRealtime(uSecTime,BatteryStats.STATS_SINCE_UNPLUGGED);
            int batteryCapacity=getBatteryCapacity();
            int idleConsume=getIdleConsume();
            int flyConsume=getFlyConsume();
            double allConsume=idleConsume;
            //Log.i(TAG,"getPowerConsume allConsume init="+allConsume);        
    		if (isDataOn()){
                //assume  5M data every day
    		    allConsume+=(1280.0/86400.0)*getDataConsume();
    		}
            //Log.i(TAG,"getPowerConsume allConsume data="+allConsume);  
    		if (isWifiOn()){
    			//long wifiontime=mStatsImpl.getWifiOnTime(uSecTime,BatteryStats.STATS_SINCE_UNPLUGGED);
                //Log.i(TAG,"getPowerConsume wifiontime="+wifiontime);
				allConsume+=getWifiOnConsume();
                //estimation  max 2G data everyday and average 300K every second
                allConsume+=((2.0*1024.0*1024.0/300.0)/86400.0)*getWifiActiveConsume();
    		}
            // Log.i(TAG,"getPowerConsume allConsume wifi="+allConsume);  
    		if (isBluetoothOn()){
    			//long btontime=mStatsImpl.getBluetoothOnTime(uSecTime,BatteryStats.STATS_SINCE_UNPLUGGED);
                //Log.i(TAG,"getPowerConsume btontime="+btontime);
                //allConsume+=btontime/batteryTime*getBluetoothActiveConsume();
                allConsume+=getBluetoothOnConsume();
                allConsume+=getBluetoothActiveConsume();
    		}
            //Log.i(TAG,"getPowerConsume allConsume bt="+allConsume);  
    		if (isGpsOn()){
    			allConsume+=(1280.0/86400.0)*getGPSActiveConsume();
    		}
            //Log.i(TAG,"getPowerConsume allConsume gps="+allConsume);  
    		if (isHapticOn()){
    			allConsume+=(1/1000.0)*getHapticActiveConsume();
    		}
            //Log.i(TAG,"getPowerConsume allConsume haptic="+allConsume);  
    		if (isAutoSyncOn()){
    			allConsume+=(1280.0/86400.0)*getDataConsume()/5;
    		}
            //Log.i(TAG,"getPowerConsume allConsume autosyc="+allConsume);  
            //long screenOntime=mStatsImpl.getScreenOnTime(uSecTime,BatteryStats.STATS_SINCE_UNPLUGGED);
		    int screenLevel=getAdjustValue();
		    //assume bright on for 2 hours a day
    		if (isScreenAuto()){
    			allConsume+=(2.0/24)*(getScreenConsume()+getTotalLevelSummary()*screenLevel/225.0);
    		}else{
                allConsume+=(2.0/24)*(getScreenConsume()+getTotalLevelSummary()*screenLevel/225.0);
    		}
            //Log.i(TAG,"getPowerConsume allConsume bright="+allConsume+"   screenLevel= "+screenLevel); 
    		if (isFlyModeOn()){
    			allConsume-=(16/24.0)*(getIdleConsume()-getFlyConsume());
    		}
            //Log.i(TAG,"getPowerConsume allConsume="+allConsume);
            if(allConsume<=0){
                allConsume=getFlyConsume();
            }
		    return (batteryCapacity/allConsume)*60*mBatteryLevel/100;
	    }
     }
///LEWA END
}
