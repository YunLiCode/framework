package com.android.systemui.statusbar.toolbar;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.sip.SipManager;
import android.provider.Settings;
import android.provider.Telephony.SIMInfo;
import android.util.AttributeSet;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.systemui.R;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.util.SIMHelper;
import com.mediatek.common.featureoption.FeatureOption;
import com.mediatek.xlog.Xlog;

import java.util.ArrayList;
import java.util.List;

/**
 * M: Support "Notification toolbar".
 */
public class SimSwitchPanel extends LinearLayout {
    private static final String TAG = "SimSwitchPanelView";
    private static final boolean DBG = true;

    private static final String SIP_CALL = "SIP_CALL";
    private static final String ALWAYS_ASK = "ALWAYS_ASK";
    private static final int SIP_CALL_COLOR = 4;
    private static final int ALWAYS_ASK_COLOR = 5;
    private boolean mUpdating = false;
    private boolean mPanelShowing = false;
    /// M: This variable should be used combing mPanleShowing, and only meaningful when mPanleShowing is true
    private String mCurrentBussinessType;
    /// M: flags used to indicate sim icon views are already inflated
    private boolean mSimIconInflated = false;
    private List<SIMInfo> mSIMInfoList;
    private List<SimIconView> mSimInconViews;
    private String mCurrentServiceType;
    private ToolBarView mToolBarView;
    private AlertDialog mSwitchDialog;
    private SIMInfo mChooseSIMInfo;
    private SimIconView mSipCallIconView;
    private SimIconView mAlwaysAskIconView;

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DBG) {
                Xlog.d(TAG, "sim state changed");
            }
            if (action.equals(TelephonyIntents.ACTION_SIM_INDICATOR_STATE_CHANGED)
                    && mSIMInfoList != null && mSIMInfoList.size() > 0) {
                for (int i = 0; i < mSIMInfoList.size(); i++) {
                    SIMInfo simInfo = mSIMInfoList.get(i);
                    SimIconView simIconView;
                    if (simInfo != null) {
                        simIconView = (SimIconView) findViewBySlotId(simInfo.mSlot);
                        if (simIconView != null) {
                            simIconView.updateSimIcon(simInfo);
                        }
                    }
                }
            } else if (action.equals(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED)) {
                if (mCurrentServiceType != null && !mCurrentServiceType
                        .equals(Settings.System.GPRS_CONNECTION_SIM_SETTING)) {
                    return;
                }
                String reason = intent.getStringExtra(PhoneConstants.STATE_CHANGE_REASON_KEY);
                PhoneConstants.DataState state = getMobileDataState(intent);
                if (FeatureOption.MTK_GEMINI_SUPPORT) {
                    int simSlotId = intent.getIntExtra(PhoneConstants.GEMINI_SIM_ID_KEY, -1);
                    if (DBG) {
                        Xlog.d(TAG, "mDataConnectionReceiver simId is : " + simSlotId);
                        Xlog.d(TAG, "mDataConnectionReceiver state is : " + state);
                        Xlog.d(TAG, "mDataConnectionReceiver reason is : " + reason);
                    }
                    if (reason == null) {
                        return;
                    }
                    if (reason != null && (reason.equals(Phone.REASON_DATA_ATTACHED)
                            || reason.equals(Phone.REASON_DATA_DETACHED))) {
                        switch (state) {
                        case CONNECTED:
                            updateMobileConnection();
                            break;
                        case DISCONNECTED:
                            updateMobileConnection();
                            break;
                        default:
                            break;
                        }

                        /// M: Refresh the sim indicators
                        if (mSIMInfoList != null) {
                            for (int i = 0; i < mSIMInfoList.size(); i++) {
                                SIMInfo simInfo = mSIMInfoList.get(i);
                                SimIconView simIconView;
                                if (simInfo != null) {
                                    simIconView = (SimIconView) findViewBySlotId(simInfo.mSlot);
                                    if (simIconView != null) {
                                        simIconView.updateSimIcon(simInfo);
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (action.equals(TelephonyIntents.ACTION_SIM_INFO_UPDATE)) {
                SIMHelper.updateSIMInfos(mContext);
                mSIMInfoList = SIMHelper.getSIMInfoList(mContext);
                int count = mSIMInfoList.size();
                for (int i = 0; i < count; i++) {
                    SIMInfo simInfo = mSIMInfoList.get(i);
                    SimIconView simIconView;
                    if (simInfo != null) {
                        simIconView = (SimIconView) findViewBySlotId(simInfo.mSlot);
                        if (simIconView != null) {
                            simIconView.setSlotId(simInfo.mSlot);
                            simIconView.setSimColor(simInfo.mColor);
                            simIconView.updateSimIcon(simInfo);
                        }
                    }
                }
            }
        }
    };

    private static PhoneConstants.DataState getMobileDataState(Intent intent) {
        String str = intent.getStringExtra(PhoneConstants.STATE_KEY);
        if (str != null) {
            return Enum.valueOf(PhoneConstants.DataState.class, str);
        } else {
            return PhoneConstants.DataState.DISCONNECTED;
        }
    }

    /**
     * M: When siminfo changed, for example siminfo's background resource changed, need to reload all related UI.
     */
    public void updateSimInfo() {
        buildSimIconViews();
    }

    private void updateMobileConnection() {
        long simId = SIMHelper.getDefaultSIM(mContext, Settings.System.GPRS_CONNECTION_SIM_SETTING);
        if (DBG) {
            Xlog.d(TAG, "updateMobileConnection, simId is" + simId);
        }
        switchSimId(simId);
    }

    public SimSwitchPanel(Context context) {
        this(context, null);
    }

    public SimSwitchPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mSimInconViews = new ArrayList<SimIconView>();
    }

    protected int getSuggestedMinimumWidth() {
        /// M: makes the large background bitmap not force us to full width
        return 0;
    }

    void setUpdates(boolean update) {
        if (update != mUpdating) {
            mUpdating = update;
            if (update) {
                /// M: Register for Intent broadcasts for the clock and battery
                IntentFilter filter = new IntentFilter();
                filter.addAction(TelephonyIntents.ACTION_SIM_INDICATOR_STATE_CHANGED);
                filter.addAction(TelephonyIntents.ACTION_SIM_INFO_UPDATE);
                filter.addAction(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
                mContext.registerReceiver(mIntentReceiver, filter, null, null);
            } else {
                mContext.unregisterReceiver(mIntentReceiver);
            }
        }
    }

    public void setToolBar(ToolBarView toolBarView) {
        mToolBarView = toolBarView;
    }

    private static boolean isInternetCallEnabled(Context context) {
        return Settings.System.getInt(context.getContentResolver(), Settings.System.ENABLE_INTERNET_CALL, 0) == 1;
    }

    private boolean showSimIconViews(String bussinessType) {
        mCurrentBussinessType = bussinessType;
        /// M: no sim switch for video call currently
        if (bussinessType.equals(Settings.System.VIDEO_CALL_SIM_SETTING)) {
            return false;
        }
        /// M: if sim icon views are not inflated, should load at once
        if (!mSimIconInflated) {
            buildSimIconViews();
        }
        if (bussinessType.equals(Settings.System.VOICE_CALL_SIM_SETTING)
                && mSipCallIconView != null
                && isInternetCallEnabled(getContext())) {
            mSipCallIconView.setVisibility(View.VISIBLE);
            Xlog.d(TAG, "mSIMInfoList.size() 185 " + mSIMInfoList.size() + " mAlwaysAskIconView != null is "
                    + (mAlwaysAskIconView != null));
            if (mSIMInfoList.size() >= 1 && mAlwaysAskIconView != null) {
                mAlwaysAskIconView.setVisibility(View.VISIBLE);
            } else {
                if (mAlwaysAskIconView != null) {
                    mAlwaysAskIconView.setVisibility(View.GONE);
                }
            }
        } else {
            if (mSipCallIconView != null) {
                mSipCallIconView.setVisibility(View.GONE);
            }
            if (mSIMInfoList.size() <= 1 && mAlwaysAskIconView != null) {
                mAlwaysAskIconView.setVisibility(View.GONE);
            }
        }
        if (bussinessType.equals(Settings.System.SMS_SIM_SETTING)) {
            Xlog.d(TAG, "mSIMInfoList.size() 198 " + mSIMInfoList.size() + " mAlwaysAskIconView != null is "
                    + (mAlwaysAskIconView != null));
            if (mSIMInfoList.size() > 1 && mAlwaysAskIconView != null) {
                mAlwaysAskIconView.setVisibility(View.VISIBLE);
            } else {
                if (mAlwaysAskIconView != null) {
                    mAlwaysAskIconView.setVisibility(View.GONE);
                }
            }
        }
        return true;
    }

    public void setPanelShowing(boolean showing) {
        mPanelShowing = showing;
    }

    public boolean isPanelShowing() {
        return mPanelShowing;
    }

    private void buildSimIconViews() {
        this.removeAllViews();
        if (mSimInconViews != null) {
            mSimInconViews.clear();
        }
        mSIMInfoList = SIMHelper.getSIMInfoList(mContext);
        int count = mSIMInfoList.size();
        Xlog.d(TAG, "buildSimIconViews call, mSIMInfoList size is " + count);
        LinearLayout.LayoutParams layutparams = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.0f);
        for (int i = 0; i < count; i++) {
            SIMInfo simInfo = mSIMInfoList.get(i);
            SimIconView simIconView = (SimIconView) View.inflate(mContext, R.layout.toolbar_sim_icon_view, null);
            simIconView.setOrientation(LinearLayout.VERTICAL);
            this.addView(simIconView, layutparams);
            mSimInconViews.add(simIconView);
            if (simInfo != null) {
                simIconView.setSlotId(simInfo.mSlot);
                simIconView.setSimColor(simInfo.mColor);
            }
            simIconView.setTag(simInfo);
            simIconView.setOnClickListener(mSimSwitchListener);
            simIconView.updateSimIcon(mSIMInfoList.get(i));
        }
        if (SipManager.isVoipSupported(mContext)) {
            final SimIconView simIconView = (SimIconView) View.inflate(mContext,
                    R.layout.toolbar_sim_icon_view_config_style, null);
            simIconView.setSimIconViewResource(R.drawable.toolbar_sim_sip_call_not_select);
            simIconView.setOpName(R.string.gemini_intenet_call);
            simIconView.setTag(SIP_CALL);
            simIconView.setOrientation(LinearLayout.VERTICAL);
            this.addView(simIconView, layutparams);
            mSimInconViews.add(simIconView);
            simIconView.setSimColor(SIP_CALL_COLOR);
            simIconView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int i = 0; i < mSimInconViews.size(); i++) {
                        mSimInconViews.get(i).setSelected(false);
                    }
                    simIconView.setSelected(true);
                    Settings.System.putLong(mContext.getContentResolver(),
                            mCurrentServiceType,
                            Settings.System.VOICE_CALL_SIM_SETTING_INTERNET);
                    Intent intent = new Intent();
                    intent.putExtra("simid", Settings.System.VOICE_CALL_SIM_SETTING_INTERNET);
                    intent.setAction(Intent.ACTION_VOICE_CALL_DEFAULT_SIM_CHANGED);
                    SimSwitchPanel.this.getContext().sendBroadcast(intent);
                    mToolBarView.getStatusBarService().animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
                }
            });
            mSipCallIconView = simIconView;
            /// M: if panel is already showing and buildSimIconViews() is called,
            /// we should double check if sip call view should be shown
            if (mPanelShowing) {
                String bussinessType = mCurrentBussinessType;
                if (bussinessType.equals(Settings.System.VOICE_CALL_SIM_SETTING)
                        && isInternetCallEnabled(getContext())) {
                    mSipCallIconView.setVisibility(View.VISIBLE);
                } else {
                    mSipCallIconView.setVisibility(View.GONE);
                }
                long simId = -1;
                simId = Settings.System.getLong(mContext.getContentResolver(), bussinessType, -1);
                switchSimId(simId);
            }
        }
        /// M: support always ask. @{
        final SimIconView simIconView = (SimIconView) View.inflate(
                mContext, R.layout.toolbar_sim_icon_view_config_style, null);
        simIconView.setSimIconViewResource(R.drawable.toolbar_sim_always_ask_not_select);
        simIconView.setOpName(R.string.gemini_default_sim_always_ask);
        simIconView.setTag(ALWAYS_ASK);
        simIconView.setOrientation(LinearLayout.VERTICAL);
        this.addView(simIconView, layutparams);
        mSimInconViews.add(simIconView);
        simIconView.setSimColor(ALWAYS_ASK_COLOR);
        simIconView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (int i = 0; i < mSimInconViews.size(); i++) {
                    mSimInconViews.get(i).setSelected(false);
                }
                simIconView.setSelected(true);
                Settings.System.putLong(mContext.getContentResolver(),
                        mCurrentServiceType,
                        Settings.System.DEFAULT_SIM_SETTING_ALWAYS_ASK);
                Intent intent = new Intent();
                intent.putExtra("simid", Settings.System.DEFAULT_SIM_SETTING_ALWAYS_ASK);
                if (mCurrentServiceType.equals(Settings.System.VOICE_CALL_SIM_SETTING)) {
                    intent.setAction(Intent.ACTION_VOICE_CALL_DEFAULT_SIM_CHANGED);
                } else if (mCurrentServiceType.equals(Settings.System.SMS_SIM_SETTING)) {
                    intent.setAction(Intent.ACTION_SMS_DEFAULT_SIM_CHANGED);
                }
                SimSwitchPanel.this.getContext().sendBroadcast(intent);
                mToolBarView.getStatusBarService().animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
                // mToolBarView.setSimSwitchPanleVisibility(false);
            }
        });
        mAlwaysAskIconView = simIconView;
        if (mPanelShowing) {
            String bussinessType = mCurrentBussinessType;
            if (mSIMInfoList.size() >= 2
                    || (bussinessType
                            .equals(Settings.System.VOICE_CALL_SIM_SETTING)
                            && mSIMInfoList.size() == 1
                            && SipManager.isVoipSupported(mContext) && isInternetCallEnabled(getContext()))) {
                mAlwaysAskIconView.setVisibility(View.VISIBLE);
            } else {
                mAlwaysAskIconView.setVisibility(View.GONE);
            }
            if (mSIMInfoList.size() == 1 && bussinessType.equals(Settings.System.SMS_SIM_SETTING)) {
                mAlwaysAskIconView.setVisibility(View.GONE);
            }
            long simId = -1;
            simId = Settings.System.getLong(mContext.getContentResolver(), bussinessType, -1);
            switchSimId(simId);
        }
        /// M: support always ask. @}
        mSimIconInflated = true;
    }

    private View.OnClickListener mSimSwitchListener = new View.OnClickListener() {
        public void onClick(View v) {
            SIMInfo simInfo = (SIMInfo) v.getTag();
            long simId = simInfo.mSimId;
            int simState = SIMHelper.getSimIndicatorStateGemini(simInfo.mSlot);
            if (DBG) {
                Xlog.d(TAG, "user clicked simIcon, simId is : " + simId + " , simState = " + simState);
            }
            if (simState == PhoneConstants.SIM_INDICATOR_RADIOOFF) {
                mChooseSIMInfo = simInfo;
                if (mSwitchDialog == null) {
                    mSwitchDialog = createDialog(simInfo);
                } else {
                    String mText;
                    if (com.mediatek.common.featureoption.FeatureOption.MTK_GEMINI_SUPPORT) {
                        mText = getResources().getString(R.string.confirm_radio_msg, simInfo.mDisplayName);
                    } else {
                        mText = getResources().getString(R.string.confirm_radio_msg_single);
                    }
                    mSwitchDialog.setMessage(mText);
                }
                mSwitchDialog.show();
            } else {
                changeDefaultSim(simInfo);
            }
        }
    };

    private AlertDialog createDialog(SIMInfo simInfo) {
        String mText;
        if (FeatureOption.MTK_GEMINI_SUPPORT) {
            mText = getResources().getString(R.string.confirm_radio_msg, simInfo.mDisplayName);
        } else {
            mText = getResources().getString(R.string.confirm_radio_msg_single);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(mContext);
        b.setCancelable(true).setTitle(R.string.confirm_radio_title)
                .setMessage(mText).setInverseBackgroundForced(true)
                .setNegativeButton(android.R.string.cancel, mRadioOffListener)
                .setPositiveButton(R.string.confirm_radio_lbutton, mRadioOffListener);
        AlertDialog alertDialog = b.create();
        alertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL);
        return alertDialog;
    }

    public void dismissDialogs() {
        if (mSwitchDialog != null) {
            mSwitchDialog.dismiss();
        }
    }

    private DialogInterface.OnClickListener mRadioOffListener = new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (mSwitchDialog != null) {
                mSwitchDialog.dismiss();
            }
            switch (which) {
            case DialogInterface.BUTTON_POSITIVE:
                radioOnBySlot(mChooseSIMInfo.mSlot);
                changeDefaultSim(mChooseSIMInfo);
                break;
            case DialogInterface.BUTTON_NEGATIVE:
                break;
            default:
                break;
            }
        }
    };

    private void radioOnBySlot(int slot) {
        ContentResolver cr = mContext.getContentResolver();
        if (FeatureOption.MTK_GEMINI_SUPPORT) {
            int dualSimMode = 0;
            if (1 == Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, -1)) {
                Xlog.d(TAG, "radioOnBySlot powerRadioOn airplane mode on");
                Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0);
                if (slot == PhoneConstants.GEMINI_SIM_1) {
                    Settings.System.putInt(cr, Settings.System.DUAL_SIM_MODE_SETTING, 1);
                }
                if (slot == PhoneConstants.GEMINI_SIM_2) {
                    Settings.System.putInt(cr, Settings.System.DUAL_SIM_MODE_SETTING, 2);
                }
                mContext.sendBroadcast(new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", false));
                Xlog.d(TAG, "radioOnBySlot powerRadioOn airplane mode off");
                if (0 == slot) {
                    Xlog.d(TAG, "radioOnBySlot powerRadioOn change to SIM1 only");
                    dualSimMode = 1;
                } else if (1 == slot) {
                    Xlog.d(TAG, "radioOnBySlot powerRadioOn change to SIM2 only");
                    dualSimMode = 2;
                }
            } else {
                Xlog.d(TAG, "radioOnBySlot powerRadioOn: airplane mode is off");
                Xlog.d(TAG, "radioOnBySlot powerRadioOn: airplane mode is off and dualSimMode = " + dualSimMode);
                if (0 == Settings.System.getInt(cr, Settings.System.DUAL_SIM_MODE_SETTING, -1)) {
                    dualSimMode = slot + 1;
                } else {
                    dualSimMode = 3;
                }
            }
            Settings.System.putInt(cr, Settings.System.DUAL_SIM_MODE_SETTING, dualSimMode);
            Intent intent = new Intent(Intent.ACTION_DUAL_SIM_MODE_CHANGED);
            intent.putExtra(Intent.EXTRA_DUAL_SIM_MODE, dualSimMode);
            mContext.sendBroadcast(intent);
        } else {
            Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0);
            mContext.sendBroadcast(intent);
        }
    }

    private void changeDefaultSim(SIMInfo simInfo) {
        long simId = simInfo.mSimId;
        if (simId == Settings.System.getLong(mContext.getContentResolver(), mCurrentServiceType, -1)) {
            mToolBarView.getStatusBarService().animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
            return;
        } else {
            if (!mCurrentServiceType.equals(Settings.System.GPRS_CONNECTION_SIM_SETTING)) {
                Settings.System.putLong(mContext.getContentResolver(), mCurrentServiceType, simId);
            }
            Intent intent = new Intent();
            if (mCurrentServiceType.equals(Settings.System.VOICE_CALL_SIM_SETTING)) {
                intent.putExtra("simid", simId);
                intent.setAction(Intent.ACTION_VOICE_CALL_DEFAULT_SIM_CHANGED);
            } else if (mCurrentServiceType.equals(Settings.System.SMS_SIM_SETTING)) {
                intent.putExtra("simid", simId);
                intent.setAction(Intent.ACTION_SMS_DEFAULT_SIM_CHANGED);
            } else if (mCurrentServiceType.equals(Settings.System.GPRS_CONNECTION_SIM_SETTING)) {
                intent.putExtra(PhoneConstants.MULTI_SIM_ID_KEY, simId);
                intent.setAction(Intent.ACTION_DATA_DEFAULT_SIM_CHANGED);
            }
            SimSwitchPanel.this.getContext().sendBroadcast(intent);
            updateSimSelectState(simInfo);
            mToolBarView.getStatusBarService().animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
        }
    }

    public final boolean updateSimService(String bussinessType) {
        mCurrentServiceType = bussinessType;
        boolean shouldShowSim = showSimIconViews(bussinessType);
        long simId = -1;
        simId = Settings.System.getLong(mContext.getContentResolver(), bussinessType, -1);
        if (DBG) {
            Xlog.d(TAG, "updateSimService, bussinessType is: " + bussinessType + ", simId is " + simId);
        }
        switchSimId(simId);
        return true;
    }

    private void switchSimId(long simId) {
        if (mSIMInfoList == null) {
            /// M: this statement may come in when sim switch panel is not ready,but data connection sim changed
            return;
        }
        // update3GIconState();
        if (simId > 0) {
            updateSimIcons(simId);
        } else if (simId == 0) {
            for (int i = 0; i < mSimInconViews.size(); i++) {
                mSimInconViews.get(i).setSelected(false);
            }
        } else if (simId == -2) {
            for (int i = 0; i < mSimInconViews.size(); i++) {
                mSimInconViews.get(i).setSelected(false);
            }
            SimIconView selectedSimIconView = (SimIconView) findViewWithTag(SIP_CALL);
            if (selectedSimIconView == null) {
                if (DBG) {
                    Xlog.d(TAG, "switchSimId failed, bussinessType is: " + mCurrentServiceType + ", simId is " + simId);
                }
            } else {
                selectedSimIconView.setSelected(true);
            }
        } else if (simId == -1) {
            for (int i = 0; i < mSimInconViews.size(); i++) {
                mSimInconViews.get(i).setSelected(false);
            }
            SimIconView selectedSimIconView = (SimIconView) findViewWithTag(ALWAYS_ASK);
            if (selectedSimIconView == null) {
                if (DBG) {
                    Xlog.d(TAG, "switchSimId failed, bussinessType is: " + mCurrentServiceType + ", simId is " + simId);
                }
            } else {
                selectedSimIconView.setSelected(true);
            }
        }
    }

    private void updateSimIcons(long simId) {
        SIMInfo simInfo = SIMHelper.getSIMInfo(mContext, simId);
        if (simInfo != null) {
            updateSimSelectState(simInfo);
        }
    }

    private SimIconView findViewBySlotId(int slotId) {
        for (SimIconView simIconView : mSimInconViews) {
            if (simIconView.getSlotId() == slotId) {
                return simIconView;
            }
        }
        return null;
    }

    private void updateSimSelectState(SIMInfo simInfo) {
        if (simInfo == null) {
            Xlog.d(TAG, "updateSimSelectState failed for simInfo is null, bussinessType is: " + mCurrentServiceType);
            return;
        }
        for (int i = 0; i < mSimInconViews.size(); i++) {
            mSimInconViews.get(i).setSelected(false);
        }
        SimIconView selectedSimIconView = (SimIconView) findViewBySlotId(simInfo.mSlot);
        if (selectedSimIconView != null) {
            selectedSimIconView.setSelected(true);
        } else {
            if (DBG) {
                Xlog.d(TAG, "updateSimSelectState failed, bussinessType is: " + mCurrentServiceType
                        + ", simId is " + simInfo.mSimId);
            }
        }
    }

    public void updateResources() {
        if (mSimInconViews != null && mSimInconViews.size() != 0) {
            if (mSimInconViews.size() >= 2) {
                SimIconView sipIconView = mSimInconViews.get(mSimInconViews.size() - 2);
                if (sipIconView != null && sipIconView.getTag() != null && sipIconView.getTag().equals(SIP_CALL)) {
                    sipIconView.setOpName(R.string.gemini_intenet_call);
                }
            }
            /// M: Support always ask. @{
            SimIconView sipIconViewAlwaysAsk = mSimInconViews .get(mSimInconViews.size() - 1);
            if (sipIconViewAlwaysAsk != null
                    && sipIconViewAlwaysAsk.getTag() != null
                    && sipIconViewAlwaysAsk.getTag().equals(ALWAYS_ASK)) {
                sipIconViewAlwaysAsk.setOpName(R.string.gemini_default_sim_always_ask);
            }
            /// M: Support always ask. @}
        }
        if (mSwitchDialog != null) {
            mSwitchDialog.setTitle(getResources().getString(R.string.confirm_radio_title));
            mSwitchDialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(R.string.confirm_radio_lbutton);
            mSwitchDialog.getButton(DialogInterface.BUTTON_NEGATIVE).setText(android.R.string.cancel);
            if (mChooseSIMInfo != null) {
                if (com.mediatek.common.featureoption.FeatureOption.MTK_GEMINI_SUPPORT) {
                    mSwitchDialog.setMessage(getResources().getString(R.string.confirm_radio_msg,
                            mChooseSIMInfo.mDisplayName));
                } else {
                    mSwitchDialog.setMessage(getResources().getString(R.string.confirm_radio_msg_single));
                }
            }
        }
    }
}
