package android.graphics.drawable;

import android.content.res.Resources;

public class ListDrawable extends DrawableContainer {
    private final ListState mListState;
    private Resources mResources;

    public ListDrawable(ListState orig, Resources res) {
        mListState = new ListState(orig, this, res);
        setConstantState(mListState);
        if (res != null) {
            mResources = res;
            enableFade(true);
        }
    }

    public void addDrawable(int id) {
        if (mResources != null)
            mListState.addChild(mResources.getDrawable(id));
    }

    public void enableFade(boolean enable) {
        if (enable) {
            setEnterFadeDuration(mResources.getInteger(android.R.integer.config_shortAnimTime));
            setExitFadeDuration(mResources.getInteger(android.R.integer.config_mediumAnimTime));
        } else {
            setEnterFadeDuration(1);
            setExitFadeDuration(1);
        }
    }

    protected boolean onLevelChange(int level) {
        int idx = level;
        if (selectDrawable(idx)) {
            return true;
        }
        return super.onLevelChange(level);
    }

    private static final class ListState extends DrawableContainer.DrawableContainerState {
        ListState(ListState orig, ListDrawable owner, Resources res) {
            super(orig, owner, res);
        }

        public Drawable newDrawable() {
            return new ListDrawable(this, null);
        }

        public Drawable newDrawable(Resources res) {
            return new ListDrawable(this, res);
        }
    }
}