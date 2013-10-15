/**
 * @author chenhengheng
 * it is constant for 360 intercept that content table column ,name for provider,and some for app
 */
package android.provider;

import android.net.Uri;

public class InterceptConstants {

    public static final boolean DBUG = true;

    public static final String TAG = "Intercept";

    public static final String DBNAME = "intercept.db";

    public static final String TABLENAME = "interceptData";

    public static final String DND_TABLENAME = "dnd";

    public static final String MSG_TABLENAME = "msg_history";

    public static final String CALL_TABLENAME = "call_history";

    public static final int VERSION = 1;

    public static String COLUMN_BLOCK_NAME_ID = "_id";
    // phone number
    public static final String COLUMN_NAME = "name";
    // phone number
    public static final String COLUMN_NUMBER = "number";
    // black number , 0 is not black number, 1 is yes
    // public static final String COLUMN_BLACK = "black";

    public static final String COLUMN_TYPE = "type";

    // intercept mode , 1 is sms mode , 2 is call mode, 3 is all
    public static final String COLUMN_MODE = "mode";
    // provacy number, 0 is not , 1 is yes
    public static final String COLUMN_PRIVACY = "provacy";

    public static String COLUMN_DND_ID = "_id";
    // switchMode
    public static final String COLUMN_SWITCH_MODE = "switchMode";
    // night dnd switch, 0 is close, 1 is open
    public static final String COLUMN_SWITCH = "switch";
    // start time of night dnd switch
    public static final String COLUMN_START_TIME = "startTime";
    // end time of night dnd switch
    public static final String COLUMN_END_TIME = "endTime";
    // intercept switch , 0 is close ,1 is open
    public static final String COLUMN_INTERCEPT = "isIntercept";

    public static String COLUMN_MSG_ID = "_id";

    public static final String COLUMN_MSG_NAME = "name";

    public static final String COLUMN_MSG_ADDRESS = "address";

    public static final String COLUMN_MSG_LOCATION = "location";

    public static final String COLUMN_MSG_DATE = "date";

    public static final String COLUMN_MSG_SUBJECT = "subject";

    public static final String COLUMN_MSG_BODY = "body";

    public static final String COLUMN_MSG_READ = "read";

    public static final String COLUMN_MSG_TYPE = "type";

    public static final String COLUMN_MSG_CARDINFO = "cardinfo";

    public static String COLUMN_CALL_ID = "_id";

    public static final String COLUMN_CALL_NAME = "name";

    public static final String COLUMN_CALL_ADDRESS = "address";

    public static final String COLUMN_CALL_LOCATION = "location";

    public static final String COLUMN_CALL_DATE = "date";

    public static final String COLUMN_CALL_READ = "read";

    public static final String COLUMN_CALL_CAUSE = "cause";

    public static final String COLUMN_CALL_CARDINFO = "cardinfo";

    public static final String COLUMN_INTERCEPT_CAUSE = "RingOnce";

    public static final String COLUMN_CALL_BLOCKTYPE = "blocktype";

    public static final String AUTOHORITY = "com.lewa.providers.intercept";

    public static final int ITEM = 1;

    public static final int ITEM_ID = 2;

    public static final int DND_ITEM = 3;

    public static final int MSG_HISTORY_ITEM = 4;

    public static final int MSG_HISTORY_ITEM_ID = 5;

    public static final int CALL_HISTORY_ITEM = 6;

    public static final int CALL_HISTORY_ITEM_ID = 7;

    public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.lewa.intercept.providers";

    public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.lewa.intercept.providers";

    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTOHORITY
            + "/interceptData");

    public static final Uri DND_CONTENT_URI = Uri.parse("content://"
            + AUTOHORITY + "/dnd");

    public static final Uri MSG_CONTENT_URI = Uri.parse("content://"
            + AUTOHORITY + "/msg_history");

    public static final Uri CALL_CONTENT_URI = Uri.parse("content://"
            + AUTOHORITY + "/call_history");

    public static final Uri MSG_INBOX_URI = Uri.parse("content://sms/inbox");

    public static final String SHARE_PREFERENCE_NAME = "com.lewa.intercept_preferences";

    // block rule choice value (checked KEY)
    public static final int BLOCK_MODE_BLACKLIST = 1;
    public static final int BLOCK_MODE_ALLNUM = 2;
    public static final int BLOCK_MODE_OUT_OF_WHITELIST = 3;
    public static final int BLOCK_MODE_EXCEPT_CONTACT = 4;
    public static final int BLOCK_MODE_SMART = 5;

    public static final int BLOCK_TYPE_NUMBER_CALL = 1;
    public static final int BLOCK_TYPE_NUMBER_MSG = 2;
    public static final int BLOCK_TYPE_NUMBER_DEFAULT = 3;

    public static final int DEFAULT_BLOCK_PRIVACY = 0;

    public static final int BLOCK_TYPE_DEFAULT = 0;
    public static final int BLOCK_TYPE_BLACK = 1;
    public static final int BLOCK_TYPE_WHITE = 2;

    public static final int BLOCK_SWITCH_ON_INT = 0;
    public static final int BLOCK_SWITCH_OFF_INT = 1;

    public static final int BLOCK_INTERCEPT_ON_INT = 0;
    public static final int BLOCK_INTERCEPT_OFF_INT = 1;

    public static final boolean BLOCK_SWITCH_ON_BOOLEAN = true;
    public static final boolean BLOCK_SWITCH_OFF_BOOLEAN = false;

    public static final boolean BLOCK_INTERCEPT_ON_BOOLEAN = true;
    public static final boolean BLOCK_INTERCEPT_OFF_BOOLEAN = false;

    public static final String STARTTIME = "07:00";
    public static final String ENDTIME = "23:00";

    public static final String KEY_INTERCEPT = "isIntercept";
    public static final String KEY_SWITCH = "isSwitch";
    // public static final String KEY_INTERUPTSET = "isInterupt";

    public static final String KEY_BLOCK_MODE = "blockMode";

    public static final String KEY_START_TIME = "startTime";
    public static final String KEY_END_TIME = "endTime";

    public static final String KEY_START_HOUR = "startHour";
    public static final String KEY_START_MUNITE = "startMunite";
    public static final String KEY_END_HOUR = "endHour";
    public static final String KEY_END_MUNITE = "endMunite";

    public static final String KEY_BLOCK_IS_ALL_DAY = "isAllDay";

    public static final String KEY_BLOCK_SET_TIME = "timeSetting";

    public static final String LEWA_INTERCEPT_NOTIFICATION_ACTION = "android.provider.lewa.intercept.notification";

    public static final String LEWA_INTERCEPT_NOTIFICATION_CLASSFY_ACTION = "android.provider.lewa.intercept.notification.classfy";
}
