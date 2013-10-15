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
package com.android.internal.widget;

import com.android.internal.R;
import com.android.internal.view.menu.ActionMenuPresenter;
import com.android.internal.view.menu.ActionMenuView;
import com.android.internal.view.menu.MenuBuilder;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.MotionEvent;

///LEWA BEGIN
import com.android.internal.view.StandaloneActionMode;
import com.android.internal.app.ActionBarImpl.ActionModeImpl;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.PopupMenu.OnMenuItemClickListener;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View.OnClickListener;
///LEWA END
import android.util.Log;
import android.annotation.LewaHook;
import lewa.util.LewaUiUtil;
/**
 * @hide
 */
public class ActionBarContextView extends AbsActionBarView implements AnimatorListener {
    private static final String TAG = "ActionBarContextView";

    private CharSequence mTitle;
///LEWA BEGIN
    // private CharSequence mSubtitle;
///LEWA END

    private View mClose;
    private View mCustomView;
    private LinearLayout mTitleLayout;
    private TextView mTitleView;
    private TextView mSubtitleView;
    private int mTitleStyleRes;
    private int mSubtitleStyleRes;
    private Drawable mSplitBackground;
    private boolean mTitleOptional;

    private Animator mCurrentAnimation;
    private boolean mAnimateInOnLayout;
    private int mAnimationMode;

    private static final int ANIMATE_IDLE = 0;
    private static final int ANIMATE_IN = 1;
    private static final int ANIMATE_OUT = 2;
    
    public ActionBarContextView(Context context) {
        this(context, null);
    }
    
    public ActionBarContextView(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.actionModeStyle);
    }
    
    public ActionBarContextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ActionMode, defStyle, 0);
        setBackgroundDrawable(a.getDrawable(
                com.android.internal.R.styleable.ActionMode_background));
        mTitleStyleRes = a.getResourceId(
                com.android.internal.R.styleable.ActionMode_titleTextStyle, 0);
        mSubtitleStyleRes = a.getResourceId(
                com.android.internal.R.styleable.ActionMode_subtitleTextStyle, 0);

        mContentHeight = a.getLayoutDimension(
                com.android.internal.R.styleable.ActionMode_height, 0);

        mSplitBackground = a.getDrawable(
                com.android.internal.R.styleable.ActionMode_backgroundSplit);

        a.recycle();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mActionMenuPresenter != null) {
            mActionMenuPresenter.hideOverflowMenu();
            mActionMenuPresenter.hideSubMenus();
        }
    }

    @Override
    public void setSplitActionBar(boolean split) {
        if (mSplitActionBar != split) {
            if (mActionMenuPresenter != null) {
                // Mode is already active; move everything over and adjust the menu itself.
                final LayoutParams layoutParams = new LayoutParams(LayoutParams.WRAP_CONTENT,
                        LayoutParams.MATCH_PARENT);
                if (!split) {
                    mMenuView = (ActionMenuView) mActionMenuPresenter.getMenuView(this);
                    mMenuView.setBackgroundDrawable(null);
                    final ViewGroup oldParent = (ViewGroup) mMenuView.getParent();
                    if (oldParent != null) oldParent.removeView(mMenuView);
                    addView(mMenuView, layoutParams);
                } else {
                    // Allow full screen width in split mode.
                    mActionMenuPresenter.setWidthLimit(
                            getContext().getResources().getDisplayMetrics().widthPixels, true);
                    // No limit to the item count; use whatever will fit.
                    mActionMenuPresenter.setItemLimit(Integer.MAX_VALUE);
                    // Span the whole width
                    layoutParams.width = LayoutParams.MATCH_PARENT;
                    layoutParams.height = mContentHeight;
                    mMenuView = (ActionMenuView) mActionMenuPresenter.getMenuView(this);
                    mMenuView.setBackgroundDrawable(mSplitBackground);
                    final ViewGroup oldParent = (ViewGroup) mMenuView.getParent();
                    if (oldParent != null) oldParent.removeView(mMenuView);
                    mSplitView.addView(mMenuView, layoutParams);
                }
            }
            super.setSplitActionBar(split);
        }
    }

    public void setContentHeight(int height) {
        mContentHeight = height;
    }

    public void setCustomView(View view) {
        if (mCustomView != null) {
            removeView(mCustomView);
        }
        mCustomView = view;
        if (mTitleLayout != null) {
            removeView(mTitleLayout);
            mTitleLayout = null;
        }
        if (view != null) {
            addView(view);
        }
        requestLayout();
    }

    public void setTitle(CharSequence title) {
        mTitle = title;
        initTitle();
    }

    public void setSubtitle(CharSequence subtitle) {
///LEWA BEGIN
        // mSubtitle = subtitle;
///LEWA END
        initTitle();
    }

    public CharSequence getTitle() {
        return mTitle;
    }

    public CharSequence getSubtitle() {
///LEWA BEGIN
        // return mSubtitle;
        return null;
///LEWA END
    }

///LEWA BEGIN
    // Woody Guo @ 2012/12/14: Lewa ActionBarContextView
    // Use a self-made spinner (Button + DropdownMenu) to replace the Title and SubTitle
    // Id of the MenuItem is android.R.id.selectAll
    // Client should keep track of the selection state: select all or clear selection
    private Button mSpinner;
    private ActionMode mActionMode;

    private void initTitle() {
        if(!lewaInitTitle()) {
            if (mTitleLayout == null) {
                LayoutInflater inflater = LayoutInflater.from(getContext());
                inflater.inflate(com.lewa.internal.R.layout.action_bar_title_item_with_spinner, this);
                mTitleLayout = (LinearLayout) getChildAt(getChildCount() - 1);
                // mTitleView = (TextView) mTitleLayout.findViewById(R.id.action_bar_title);
                // mSubtitleView = (TextView) mTitleLayout.findViewById(R.id.action_bar_subtitle);
                // if (mTitleStyleRes != 0) {
                    // mTitleView.setTextAppearance(mContext, mTitleStyleRes);
                // }
                // if (mSubtitleStyleRes != 0) {
                    // mSubtitleView.setTextAppearance(mContext, mSubtitleStyleRes);
                // }

                mSpinner = (Button) mTitleLayout.findViewById(com.lewa.internal.R.id.action_bar_spinner);
                mSpinner.setText("One item selected");

                final PopupMenu popMenu = new PopupMenu(mContext, mSpinner);
                final Menu menu = popMenu.getMenu();
                menu.add(Menu.NONE, android.R.id.selectAll, Menu.NONE, android.R.string.selectAll);
                popMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    public boolean onMenuItemClick(MenuItem item) {
                        if (null != mActionMode) {
                            if (mActionMode instanceof ActionModeImpl) {
                                return ((ActionModeImpl) mActionMode)
                                        .onMenuItemSelected((MenuBuilder)menu, item);
                            }
                            if (mActionMode instanceof StandaloneActionMode) {
                                return ((StandaloneActionMode) mActionMode)
                                        .onMenuItemSelected((MenuBuilder)menu, item);
                            }
                            // Woody Guo @ 2012/12/18: State of the selection menu item
                            // is totally controlled by the calling client
                            /*
                             * mActionMode.setSelectionMode(
                             *         ActionMode.SELECT_ALL == mActionMode.getSelectionMode()
                             *         ? ActionMode.SELECT_NONE : ActionMode.SELECT_ALL);
                             * item.setTitle(ActionMode.SELECT_ALL == mActionMode.getSelectionMode()
                             *         ? android.R.string.selectAll : R.string.selectNone);
                             * return true;
                             */
                        }
                        return false;
                    }
                });

                mSpinner.setOnClickListener(new OnClickListener() {
                    public void onClick(View v) {
                        popMenu.getMenu().getItem(0).setTitle(
                                ActionMode.SELECT_ALL == mActionMode.getSelectionMode()
                                ? android.R.string.selectAll : com.lewa.internal.R.string.selectNone);
                        popMenu.show();
                    }
                });
            }

            mSpinner.setText(mTitle);
        }
        if (LewaUiUtil.isV5Ui(mContext)) {
            mTitleView.setText(mTitle);
            //mSubtitleView.setText(mSubtitle);
        }
        final boolean hasTitle = !TextUtils.isEmpty(mTitle);
        // final boolean hasSubtitle = !TextUtils.isEmpty(mSubtitle);
        // mSubtitleView.setVisibility(hasSubtitle ? VISIBLE : GONE);
        // mTitleLayout.setVisibility(hasTitle || hasSubtitle ? VISIBLE : GONE);
        mTitleLayout.setVisibility(hasTitle ? VISIBLE : GONE);
        if (mTitleLayout.getParent() == null) {
            addView(mTitleLayout);
        }
    }
///LEWA END

    public void initForMode(final ActionMode mode) {
///LEWA BEGIN
        mActionMode = mode;
///LEWA END

        if (mClose == null) {
            LayoutInflater inflater = LayoutInflater.from(mContext);
            ///LEWA MODIFY BEGIN
            int resId = Injector.getLeftButtonLayoutId(mContext);
            mClose = inflater.inflate(resId, this, false);
            ///LEWA MODIFY END
            addView(mClose);
        } else if (mClose.getParent() == null) {
            addView(mClose);
        }

        View closeButton = mClose.findViewById(R.id.action_mode_close_button);
        closeButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mode.finish();
            }
        });

        final MenuBuilder menu = (MenuBuilder) mode.getMenu();
        if (mActionMenuPresenter != null) {
            mActionMenuPresenter.dismissPopupMenus();
        }
        ///LEWA MODIFY BEGIN
        mActionMenuPresenter = Injector.newMenuPresenter(mContext);
        ///LEWA MODIFY END
        mActionMenuPresenter.setReserveOverflow(true);

        final LayoutParams layoutParams = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT);
        if (!mSplitActionBar) {
            menu.addMenuPresenter(mActionMenuPresenter);
            mMenuView = (ActionMenuView) mActionMenuPresenter.getMenuView(this);
            mMenuView.setBackgroundDrawable(null);
            addView(mMenuView, layoutParams);
        } else {
            // Allow full screen width in split mode.
            mActionMenuPresenter.setWidthLimit(
                    getContext().getResources().getDisplayMetrics().widthPixels, true);
            // No limit to the item count; use whatever will fit.
            mActionMenuPresenter.setItemLimit(Integer.MAX_VALUE);
            // Span the whole width
            layoutParams.width = LayoutParams.MATCH_PARENT;
            layoutParams.height = mContentHeight;
            menu.addMenuPresenter(mActionMenuPresenter);
            mMenuView = (ActionMenuView) mActionMenuPresenter.getMenuView(this);
            mMenuView.setBackgroundDrawable(mSplitBackground);
            mSplitView.addView(mMenuView, layoutParams);
        }

        mAnimateInOnLayout = true;
    }

    public void closeMode() {
        if (mAnimationMode == ANIMATE_OUT) {
            // Called again during close; just finish what we were doing.
            return;
        }
        if (mClose == null) {
            killMode();
            return;
        }

        finishAnimation();
        mAnimationMode = ANIMATE_OUT;
        mCurrentAnimation = makeOutAnimation();
        mCurrentAnimation.start();
    }

    private void finishAnimation() {
        final Animator a = mCurrentAnimation;
        if (a != null) {
            mCurrentAnimation = null;
            a.end();
        }
    }

    public void killMode() {
        finishAnimation();
        removeAllViews();
        if (mSplitView != null) {
            mSplitView.removeView(mMenuView);
        }
        mCustomView = null;
        mMenuView = null;
        mAnimateInOnLayout = false;
///LEWA BEGIN
        mActionMode = null;
///LEWA END
    }

    @Override
    public boolean showOverflowMenu() {
        if (mActionMenuPresenter != null) {
            return mActionMenuPresenter.showOverflowMenu();
        }
        return false;
    }

    @Override
    public boolean hideOverflowMenu() {
        if (mActionMenuPresenter != null) {
            return mActionMenuPresenter.hideOverflowMenu();
        }
        return false;
    }

    @Override
    public boolean isOverflowMenuShowing() {
        if (mActionMenuPresenter != null) {
            return mActionMenuPresenter.isOverflowMenuShowing();
        }
        return false;
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        // Used by custom views if they don't supply layout params. Everything else
        // added to an ActionBarContextView should have them already.
        return new MarginLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        if (widthMode != MeasureSpec.EXACTLY) {
            throw new IllegalStateException(getClass().getSimpleName() + " can only be used " +
                    "with android:layout_width=\"match_parent\" (or fill_parent)");
        }

        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        if (heightMode == MeasureSpec.UNSPECIFIED) {
            throw new IllegalStateException(getClass().getSimpleName() + " can only be used " +
                    "with android:layout_height=\"wrap_content\"");
        }
        
        final int contentWidth = MeasureSpec.getSize(widthMeasureSpec);

        int maxHeight = mContentHeight > 0 ?
                mContentHeight : MeasureSpec.getSize(heightMeasureSpec);

        final int verticalPadding = getPaddingTop() + getPaddingBottom();
        int availableWidth = contentWidth - getPaddingLeft() - getPaddingRight();
        final int height = maxHeight - verticalPadding;
        final int childSpecHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST);
        
        if (mClose != null) {
            availableWidth = measureChildView(mClose, availableWidth, childSpecHeight, 0);
            MarginLayoutParams lp = (MarginLayoutParams) mClose.getLayoutParams();
            availableWidth -= lp.leftMargin + lp.rightMargin;
        }

        if (mMenuView != null && mMenuView.getParent() == this) {
            availableWidth = measureChildView(mMenuView, availableWidth,
                    childSpecHeight, 0);
        }

        if (mTitleLayout != null && mCustomView == null) {
            if (mTitleOptional) {
                final int titleWidthSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                mTitleLayout.measure(titleWidthSpec, childSpecHeight);
                final int titleWidth = mTitleLayout.getMeasuredWidth();
                final boolean titleFits = titleWidth <= availableWidth;
                if (titleFits) {
                    availableWidth -= titleWidth;
                }
///LEWA BEGIN
                final boolean hasTitle = !TextUtils.isEmpty(mTitle);
                mTitleLayout.setVisibility((hasTitle && titleFits) ? VISIBLE : GONE);
///LEWA END
            } else {
                if (LewaUiUtil.isV5Ui(getContext())) {
                    availableWidth = Injector.measureTitleView(mTitleLayout, availableWidth, childSpecHeight, 0);
                } else {
                    availableWidth = measureChildView(mTitleLayout, availableWidth, childSpecHeight, 0);
                }
            }
        }

        if (mCustomView != null) {
            ViewGroup.LayoutParams lp = mCustomView.getLayoutParams();
            final int customWidthMode = lp.width != LayoutParams.WRAP_CONTENT ?
                    MeasureSpec.EXACTLY : MeasureSpec.AT_MOST;
            final int customWidth = lp.width >= 0 ?
                    Math.min(lp.width, availableWidth) : availableWidth;
            final int customHeightMode = lp.height != LayoutParams.WRAP_CONTENT ?
                    MeasureSpec.EXACTLY : MeasureSpec.AT_MOST;
            final int customHeight = lp.height >= 0 ?
                    Math.min(lp.height, height) : height;
            mCustomView.measure(MeasureSpec.makeMeasureSpec(customWidth, customWidthMode),
                    MeasureSpec.makeMeasureSpec(customHeight, customHeightMode));
        }

        if (mContentHeight <= 0) {
            int measuredHeight = 0;
            final int count = getChildCount();
            for (int i = 0; i < count; i++) {
                View v = getChildAt(i);
                int paddedViewHeight = v.getMeasuredHeight() + verticalPadding;
                if (paddedViewHeight > measuredHeight) {
                    measuredHeight = paddedViewHeight;
                }
            }
            setMeasuredDimension(contentWidth, measuredHeight);
        } else {
            setMeasuredDimension(contentWidth, maxHeight);
        }
    }

    private Animator makeInAnimation() {
        mClose.setTranslationX(-mClose.getWidth() -
                ((MarginLayoutParams) mClose.getLayoutParams()).leftMargin);
        ObjectAnimator buttonAnimator = ObjectAnimator.ofFloat(mClose, "translationX", 0);
        buttonAnimator.setDuration(200);
        buttonAnimator.addListener(this);
        buttonAnimator.setInterpolator(new DecelerateInterpolator());

        AnimatorSet set = new AnimatorSet();
        AnimatorSet.Builder b = set.play(buttonAnimator);

        if (mMenuView != null) {
            final int count = mMenuView.getChildCount();
            if (count > 0) {
                for (int i = count - 1, j = 0; i >= 0; i--, j++) {
                    View child = mMenuView.getChildAt(i);
                    child.setScaleY(0);
                    ObjectAnimator a = ObjectAnimator.ofFloat(child, "scaleY", 0, 1);
                    a.setDuration(300);
                    b.with(a);
                }
            }
        }

        ///LEWA ADD BEGIN
        ObjectAnimator rightBbuttonAnimator = makeRightButtonInAnimation();
        if (rightBbuttonAnimator != null) {
            b.with(rightBbuttonAnimator);
        }
        ///LEWA ADD END
        return set;
    }

    private Animator makeOutAnimation() {
        ObjectAnimator buttonAnimator = ObjectAnimator.ofFloat(mClose, "translationX",
                -mClose.getWidth() - ((MarginLayoutParams) mClose.getLayoutParams()).leftMargin);
        buttonAnimator.setDuration(200);
        buttonAnimator.addListener(this);
        buttonAnimator.setInterpolator(new DecelerateInterpolator());

        AnimatorSet set = new AnimatorSet();
        AnimatorSet.Builder b = set.play(buttonAnimator);

        if (mMenuView != null) {
            final int count = mMenuView.getChildCount();
            if (count > 0) {
                for (int i = 0; i < 0; i++) {
                    View child = mMenuView.getChildAt(i);
                    child.setScaleY(0);
                    ObjectAnimator a = ObjectAnimator.ofFloat(child, "scaleY", 0);
                    a.setDuration(300);
                    b.with(a);
                }
            }
        }
        ///LEWA ADD BEGIN
        ObjectAnimator rightBbuttonAnimator = makeRightButtonOutAnimation();
        if (rightBbuttonAnimator != null) {
            b.with(rightBbuttonAnimator);
        }
        ///LEWA ADD END
        
        return set;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final boolean isLayoutRtl = isLayoutRtl();
        int x = isLayoutRtl ? r - l - getPaddingRight() : getPaddingLeft();
        final int y = getPaddingTop();
        final int contentHeight = b - t - getPaddingTop() - getPaddingBottom();
        
        if (mClose != null && mClose.getVisibility() != GONE) {
            MarginLayoutParams lp = (MarginLayoutParams) mClose.getLayoutParams();
            final int startMargin = (isLayoutRtl ? lp.rightMargin : lp.leftMargin);
            final int endMargin = (isLayoutRtl ? lp.leftMargin : lp.rightMargin);
            x = next(x, startMargin, isLayoutRtl);
            x += positionChild(mClose, x, y, contentHeight, isLayoutRtl);
            x = next(x, endMargin, isLayoutRtl);

            if (mAnimateInOnLayout) {
                mAnimationMode = ANIMATE_IN;
                mCurrentAnimation = makeInAnimation();
                mCurrentAnimation.start();
                mAnimateInOnLayout = false;
            }
        }

        if (mTitleLayout != null && mCustomView == null && mTitleLayout.getVisibility() != GONE) {
            x += positionChild(mTitleLayout, x, y, contentHeight, isLayoutRtl);
        }
        
        if (mCustomView != null) {
            x += positionChild(mCustomView, x, y, contentHeight, isLayoutRtl);
        }

        x = isLayoutRtl ? getPaddingLeft() : r - l - getPaddingRight();

        if (mMenuView != null) {
            ///LEWA MODIFY BEGIN
            x += positionChild(mMenuView, x, y, Injector.getContextMenuViewHeight(getContext(), contentHeight), !isLayoutRtl);
            ///LEWA MODIFY END
        }
    }

    @Override
    public void onAnimationStart(Animator animation) {
    }

    @Override
    public void onAnimationEnd(Animator animation) {
        if (mAnimationMode == ANIMATE_OUT) {
            killMode();
        }
        mAnimationMode = ANIMATE_IDLE;
    }

    @Override
    public void onAnimationCancel(Animator animation) {
    }

    @Override
    public void onAnimationRepeat(Animator animation) {
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return false;
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Action mode started
            event.setSource(this);
            event.setClassName(getClass().getName());
            event.setPackageName(getContext().getPackageName());
            event.setContentDescription(mTitle);
        } else {
            super.onInitializeAccessibilityEvent(event);
        }
    }

    public void setTitleOptional(boolean titleOptional) {
        if (titleOptional != mTitleOptional) {
            requestLayout();
        }
        mTitleOptional = titleOptional;
    }

    public boolean isTitleOptional() {
        return mTitleOptional;
    }
    
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        super.dispatchTouchEvent(event);
        // M: consume touch event to prevent from dispatching it to parent or siblings
        return true;
    }

    ///LEWA ADD BEGIN
    public void setTitleLayout(LinearLayout titleLayout)
    {
        mTitleLayout = titleLayout;
    }

    public LinearLayout getTitleLayout()
    {
        return mTitleLayout;
    }

    public TextView getTitleView() {
        return mTitleView;
    }

    public void setTitleView(TextView titleView) {
        mTitleView = titleView;
    }

    public TextView getSubTitleView() {
        return mSubtitleView;
    }

    public void setSubTitleView(TextView subtitleView) {
        mSubtitleView = subtitleView;
    }

    public int getTitleStyleRes()
    {
        return mTitleStyleRes;
    }

    public int getSubtitleStyleRes()
    {
        return mSubtitleStyleRes;
    }

    /** @hide */
    protected boolean lewaInitTitle()
    {
        return false;
    }

    /** @hide */
    public void setRightActionButtonDrawable(Drawable drawable) {
        
    }

    public ActionMode getActionMode() {
        return mActionMode;
    }

    /** @hide */
    protected ObjectAnimator makeRightButtonInAnimation() {
        return null;
    }

    /** @hide */
    protected ObjectAnimator makeRightButtonOutAnimation() {
        return null;
    }
    ///LEWA ADD END

    ///LEWA ADD BEGIN
    @LewaHook(LewaHook.LewaHookType.NEW_CLASS)
    static class Injector {
        
        static int measureTitleView(View child, int availableWidth, int childSpecHeight,
            int spacing) {
            child.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY),
                    childSpecHeight);

            availableWidth -= child.getMeasuredWidth();
            availableWidth -= spacing;

            return Math.max(0, availableWidth);
        }

        static int getLeftButtonLayoutId(Context context) {
            if (LewaUiUtil.isV5Ui(context)) {
                return com.lewa.internal.R.layout.action_mode_close_item;
            } else {
                return R.layout.action_mode_close_item;
            }
        }

        static ActionMenuPresenter newMenuPresenter(Context context) {
            if (LewaUiUtil.isV5Ui(context)) {
                return new lewa.internal.v5.view.menu.LewaActionMenuPresenter(context);
            } else {
                return new ActionMenuPresenter(context);
            }
        }

        static int getContextMenuViewHeight(Context context, int height) {
            if (LewaUiUtil.isV5Ui(context)) {
                return LewaUiUtil.dip2px(context, 70.0f);
            } else {
                return height;
            }
        }
    }
    ///LEWA ADD END
}
