package com.android.internal.policy.impl;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowManager;
import com.android.internal.policy.impl.keyguard.KeyguardViewMediator;

public class LewaScreenOnProximityLock
{
	  private static final boolean DEBUG = true;
	  private static final int EVENT_FAR_AWAY = 2;
	  private static final int EVENT_NO_USER_ACTIVITY = 4;
	  private static final int EVENT_RELEASE = 3;
	  private static final int EVENT_TOO_CLOSE = 1;
	  private static final int FIRST_CHANGE_TIMEOUT = 1000;
	  private static final String LOG_TAG = "LewaScreenOnProximityLock";
	  private static final float PROXIMITY_THRESHOLD = 4.0F;
	  private static final int RELEASE_DELAY = 300;
	  private static int sValidChangeDelay;
	  private Context mContext;
	  private Dialog mDialog;
	  private SparseArray<Boolean> mDownRecieved = new SparseArray();
	  private Handler mHandler;
	  private boolean mHeld;
	  private boolean mIsFirstChange;
	  private KeyguardViewMediator mKeyguardMediator;
	  private Sensor mSensor;
	  private MySensorEventListener mSensorEventListener = new MySensorEventListener();
	  private SensorManager mSensorManager;
      private PowerManager.WakeLock mProximityWakeLock;

	  public LewaScreenOnProximityLock(Context context, KeyguardViewMediator keyguardViewMediator)
	  {
	    mContext = context;
	    sValidChangeDelay = 200;
	    mKeyguardMediator = keyguardViewMediator;
	    mSensorManager = ((SensorManager)mContext.getSystemService("sensor"));
	    mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);

        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mProximityWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lewa_inadvert");

	    mHandler = new Handler(mContext.getMainLooper())
	    {
	      public void handleMessage(Message msg)
	      {
	            switch (msg.what)
	            {
	            case EVENT_TOO_CLOSE://1:
	              Log.d("LewaScreenOnProximityLock", "too close screen, show hint...");
	              if (mDialog == null)
	              {
	            	  prepareHintDialog();
	            	  mDialog.show();
	              }
	             // mKeyguardMediator.mRealPowerManager.enableUserActivity(false);
	              break;
	            case EVENT_FAR_AWAY://2:
	  	          Log.d("LewaScreenOnProximityLock", "far from the screen, hide hint...");
		          if (mDialog != null)
		          {
		        	  mDialog.dismiss();
		        	  mDialog = null;
		          }
		          break;
	            case EVENT_RELEASE://3:
	  	         if (!mKeyguardMediator.isShowingAndNotHidden())
		          {
		        	//mKeyguardMediator.mRealPowerManager.enableUserActivity(true);
		            Log.d("LewaScreenOnProximityLock", "far from the screen for a certain time, release proximity sensor...");
		            if (isHeld()) {
		                release();
		            }
		          }
	  	          break;
	            case EVENT_NO_USER_ACTIVITY:
	            	mIsFirstChange = false;
	            	break;
	            default:
	              break;
	            }
	        }
	    };
	  }

	  private void prepareHintDialog()
	  {
	    this.mDialog = new Dialog(this.mContext, 16973931);
	    WindowManager.LayoutParams localLayoutParams = this.mDialog.getWindow().getAttributes();
	    localLayoutParams.type = 2016;
	    localLayoutParams.flags = 4352;
	    localLayoutParams.format = -3;
	    localLayoutParams.gravity = 17;
	    this.mDialog.getWindow().setAttributes(localLayoutParams);
	    this.mDialog.getWindow().setBackgroundDrawable(new ColorDrawable(-872415232));
	    this.mDialog.getWindow().requestFeature(1);
	    this.mDialog.setCancelable(false);
	    this.mDialog.setContentView(View.inflate(this.mDialog.getContext(),
	    		com.lewa.internal.R.layout.screen_inadvert_view, null), new ViewGroup.LayoutParams(-1, -1));
	  }

	  public void aquire()
	  {
	      Log.d("LewaScreenOnProximityLock", "try to aquire");
	      if (!mHeld)
	      {
	        Log.d("LewaScreenOnProximityLock", "aquire");
	        mHeld = true;
	        mHandler.sendEmptyMessageDelayed(EVENT_RELEASE, 1000L);
            if (!mProximityWakeLock.isHeld()) {
                mProximityWakeLock.acquire();
                mSensorManager.registerListener(mSensorEventListener, mSensor, mSensorManager.SENSOR_DELAY_GAME);
            }
	        mIsFirstChange = true;
	      }
	       mSensorEventListener.handleChanges();
	  }

	  public boolean isHeld()
	  {
	      return mHeld;
	  }

	  public boolean release()
	  {
	      Log.d("LewaScreenOnProximityLock", "try to release");
	      if (mHeld)
	      {
	        Log.d("LewaScreenOnProximityLock", "release");
	        mHeld = false;
	        mIsFirstChange = false;
	        mDownRecieved.clear();
            if (mProximityWakeLock.isHeld()) {
                mSensorManager.unregisterListener(this.mSensorEventListener);
                mProximityWakeLock.release();
            }
	        mHandler.removeMessages(1);
//	        mHandler.removeMessages(4);
	        mHandler.removeMessages(3);
	        mHandler.sendEmptyMessage(2);
	        return true;
	      }
	      return false;
	  }

	  public boolean shouldBeBlocked(KeyEvent event)
	  {
		  boolean bool = true;
		  if ((event == null) || (!mHeld))
			  bool = false;

		  if (!((AudioManager)this.mContext.getSystemService("audio")).isMusicActive())
			  return false;
		  int i;
		  i = event.getKeyCode();
		  if ((event.getRepeatCount() == 0) && (event.getAction() == 0))
			  mDownRecieved.put(i, Boolean.valueOf(bool));

		  switch (event.getKeyCode())
		  {
		  case KeyEvent.KEYCODE_VOLUME_UP://24:
		  case KeyEvent.KEYCODE_VOLUME_DOWN://25:
		  case KeyEvent.KEYCODE_HEADSETHOOK://79:
		  case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE://85:
		  case KeyEvent.KEYCODE_MEDIA_STOP://86:
		  case KeyEvent.KEYCODE_MEDIA_NEXT://87:
		  case KeyEvent.KEYCODE_MEDIA_PLAY://126:
		  case KeyEvent.KEYCODE_MEDIA_PAUSE://127:
			  if (mDownRecieved.get(i) != null)
				  return false;
		  default:
			  break;

		  }
		  return bool;
	  }

	  private class MySensorEventListener implements SensorEventListener
	  {
	    boolean mIsTooClose;

	    private MySensorEventListener()
	    {
	    }

	    public void handleChanges()
	    { 
	        String string;
	        
	        if (mIsTooClose) {
	        	string = "too close";
	        }else {
	        	string = "far away";
	        }
	        Log.d(LOG_TAG, "handle change = " + string);
	        if (mIsFirstChange) {
	        	mHandler.removeMessages(1);
	        	mHandler.removeMessages(2);
	        	mHandler.removeMessages(3);
	        	mHandler.sendEmptyMessageDelayed(4,sValidChangeDelay);
	        }
	       
	        if (mIsTooClose) {
	        	if (mIsFirstChange) {
	        		mHandler.sendEmptyMessageDelayed(1, sValidChangeDelay);
	        	}
	        }else {
	        	mHandler.removeMessages(1);
	        	mHandler.sendEmptyMessageDelayed(2, sValidChangeDelay);
	        }
	    }

	    public void onAccuracyChanged(Sensor sensor, int accuracy)
	    {
	    }

	    public void onSensorChanged(SensorEvent event)
	    {
	      float value = event.values[0];
	      boolean bool1 = value < 0.0D;
	      boolean bool2 = false;
	      if (!bool1)
	      {
	        boolean bool3 = value < 4.0F;
	        bool2 = false;
	        if (bool3)
	        {
	          boolean bool4 = value < mSensor.getMaximumRange();
	          bool2 = false;
	          if (bool4)
	            bool2 = true;
	        }
	      }
	      this.mIsTooClose = bool2;
	      handleChanges();
	    }
	  }
	}
