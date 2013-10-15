/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.preference;

import com.android.internal.R;
import com.android.internal.view.menu.ActionMenuPresenter;

import android.annotation.LewaHook;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Checkable;
import android.widget.CompoundButton;
import android.widget.Switch;

import lewa.internal.v5.widget.SlidingButton;
import lewa.util.LewaUiUtil;
/**
 * A {@link Preference} that provides a two-state toggleable option.
 * <p>
 * This preference will store a boolean into the SharedPreferences.
 *
 * @attr ref android.R.styleable#SwitchPreference_summaryOff
 * @attr ref android.R.styleable#SwitchPreference_summaryOn
 * @attr ref android.R.styleable#SwitchPreference_switchTextOff
 * @attr ref android.R.styleable#SwitchPreference_switchTextOn
 * @attr ref android.R.styleable#SwitchPreference_disableDependentsState
 */
public class SwitchPreference extends TwoStatePreference {
    // Switch text for on and off states
    private CharSequence mSwitchOn;
    private CharSequence mSwitchOff;
    private final Listener mListener = new Listener();
    ///LEWA ADD BEGIN
    private final SlidingButtonListener mListenerSlidingButton = new SlidingButtonListener();
    ///LEWA ADD END

    private class Listener implements CompoundButton.OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (!callChangeListener(isChecked)) {
                // Listener didn't like it, change it back.
                // CompoundButton will make sure we don't recurse.
                buttonView.setChecked(!isChecked);
                return;
            }
            SwitchPreference.this.setChecked(isChecked);
        }
    }

    /**
     * Construct a new SwitchPreference with the given style options.
     *
     * @param context The Context that will style this preference
     * @param attrs Style attributes that differ from the default
     * @param defStyle Theme attribute defining the default style options
     */
    public SwitchPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.SwitchPreference, defStyle, 0);
        setSummaryOn(a.getString(com.android.internal.R.styleable.SwitchPreference_summaryOn));
        setSummaryOff(a.getString(com.android.internal.R.styleable.SwitchPreference_summaryOff));
        setSwitchTextOn(a.getString(
                com.android.internal.R.styleable.SwitchPreference_switchTextOn));
        setSwitchTextOff(a.getString(
                com.android.internal.R.styleable.SwitchPreference_switchTextOff));
        setDisableDependentsState(a.getBoolean(
                com.android.internal.R.styleable.SwitchPreference_disableDependentsState, false));
        a.recycle();
    }

    /**
     * Construct a new SwitchPreference with the given style options.
     *
     * @param context The Context that will style this preference
     * @param attrs Style attributes that differ from the default
     */
    public SwitchPreference(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.switchPreferenceStyle);
    }

    /**
     * Construct a new SwitchPreference with default style options.
     *
     * @param context The Context that will style this preference
     */
    public SwitchPreference(Context context) {
        this(context, null);
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        ///LEWA MODIFY BEGIN
        Context context = getContext();
        View checkableView = Injector.getCheckableView(context , view);
        ///LEWA MODIFY END
        if (checkableView != null && checkableView instanceof Checkable) {
            ((Checkable) checkableView).setChecked(mChecked);

            sendAccessibilityEvent(checkableView);

            if (checkableView instanceof Switch) {
                final Switch switchView = (Switch) checkableView;
                switchView.setTextOn(mSwitchOn);
                switchView.setTextOff(mSwitchOff);
///LEWA BEGIN
                if (switchView.isClickable()) {
                    switchView.setOnCheckedChangeListener(mListener);
                }
///LEWA END
            }
           ///LEWA ADD BEGIN
           if (checkableView instanceof SlidingButton) {
               final SlidingButton slidingButton = (SlidingButton) checkableView;
               slidingButton.setOnCheckedChangedListener(mListenerSlidingButton);
            }
           ///LEWA ADD END
        }

        syncSummaryView(view);
    }

    ///LEWA ADD BEGIN
    @LewaHook(LewaHook.LewaHookType.NEW_METHOD)
    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        if (LewaUiUtil.isV5Ui(context)) {
            int mWidgetLayoutResId  = com.lewa.internal.R.layout.preference_widget_slidingbutton;
            final LayoutInflater layoutInflater =
                    (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

                final View layout = layoutInflater.inflate(getLayoutResource(), parent, false);

                final ViewGroup widgetFrame = (ViewGroup) layout
                        .findViewById(com.android.internal.R.id.widget_frame);
                if (widgetFrame != null) {
                    if (mWidgetLayoutResId != 0) {
                        layoutInflater.inflate(mWidgetLayoutResId, widgetFrame);
                    } else {
                        widgetFrame.setVisibility(View.GONE);
                    }
                }
              return layout;
        } else {
            return super.onCreateView(parent);
        }
    }

    @LewaHook(LewaHook.LewaHookType.NEW_CLASS)
    static class Injector {
      static View getCheckableView(Context context, View view) {
          if (LewaUiUtil.isV5Ui(context)) {
              return view.findViewById(com.lewa.internal.R.id.slidebutton);
          } else {
              return view.findViewById(com.android.internal.R.id.switchWidget);
          }
      }
    }

    @LewaHook(LewaHook.LewaHookType.NEW_CLASS)
    private class SlidingButtonListener implements SlidingButton.OnCheckedChangedListener {
        @Override
        public void onCheckedChanged(CompoundButton buttonView,boolean isChecked){
            if (!callChangeListener(isChecked)) {
                // Listener didn't like it, change it back.
                // CompoundButton will make sure we don't recurse.
                buttonView.setChecked(!isChecked);
                return;
            }
            SwitchPreference.this.setChecked(isChecked);
            performClick();
        }
    }

    @LewaHook(LewaHook.LewaHookType.NEW_METHOD)
    void performClick() {
        if (!isEnabled()) {
            return;
        }

        OnPreferenceClickListener onPreferenceClickListener = getOnPreferenceClickListener();
        if (onPreferenceClickListener != null && onPreferenceClickListener.onPreferenceClick(this)) {
            return;
        }

        PreferenceManager preferenceManager = getPreferenceManager();
        if (preferenceManager != null) {
            PreferenceManager.OnPreferenceTreeClickListener listener = preferenceManager
                    .getOnPreferenceTreeClickListener();
            if (listener != null
                    && listener.onPreferenceTreeClick(null, this)) {
                return;
            }
        }
    }
   ///LEWA ADD END

    /**
     * Set the text displayed on the switch widget in the on state.
     * This should be a very short string; one word if possible.
     *
     * @param onText Text to display in the on state
     */
    public void setSwitchTextOn(CharSequence onText) {
        mSwitchOn = onText;
        notifyChanged();
    }

    /**
     * Set the text displayed on the switch widget in the off state.
     * This should be a very short string; one word if possible.
     *
     * @param offText Text to display in the off state
     */
    public void setSwitchTextOff(CharSequence offText) {
        mSwitchOff = offText;
        notifyChanged();
    }

    /**
     * Set the text displayed on the switch widget in the on state.
     * This should be a very short string; one word if possible.
     *
     * @param resId The text as a string resource ID
     */
    public void setSwitchTextOn(int resId) {
        setSwitchTextOn(getContext().getString(resId));
    }

    /**
     * Set the text displayed on the switch widget in the off state.
     * This should be a very short string; one word if possible.
     *
     * @param resId The text as a string resource ID
     */
    public void setSwitchTextOff(int resId) {
        setSwitchTextOff(getContext().getString(resId));
    }

    /**
     * @return The text that will be displayed on the switch widget in the on state
     */
    public CharSequence getSwitchTextOn() {
        return mSwitchOn;
    }

    /**
     * @return The text that will be displayed on the switch widget in the off state
     */
    public CharSequence getSwitchTextOff() {
        return mSwitchOff;
    }
}
