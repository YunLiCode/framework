/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.net;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Objects;

///LEWA BEGIN
import android.text.format.Time;
///LEWA END

/**
 * Policy for networks matching a {@link NetworkTemplate}, including usage cycle
 * and limits to be enforced.
 *
 * @hide
 */
public class NetworkPolicy implements Parcelable, Comparable<NetworkPolicy> {
    public static final int CYCLE_NONE = -1;
    public static final long WARNING_DISABLED = -1;
    public static final long LIMIT_DISABLED = -1;
    public static final long SNOOZE_NEVER = -1;

    public final NetworkTemplate template;
    public int cycleDay;
    public String cycleTimezone;
    public long warningBytes;
    public long limitBytes;
    public long lastWarningSnooze;
    public long lastLimitSnooze;
    public boolean metered;
    public boolean inferred;

///LEWA BEGIN
    public long adjustBytes;
    public long adjustTime;
    public final long snapToNextCycleDay() {
        final Time now = new Time();
        now.set(System.currentTimeMillis());
        if(now.monthDay >= cycleDay) {
            if(now.month == 11){
                now.month = 0;
                now.year++;
            } else {
                now.month++;
            }
        }
        now.monthDay = cycleDay;
        now.hour = now.minute = now.second = 0;
        return now.toMillis(true);
    }
///LEWA END
    private static final long DEFAULT_MTU = 1500;


   /// M: Gemini Feature Option     
   public boolean active;

    @Deprecated
    public NetworkPolicy(NetworkTemplate template, int cycleDay, String cycleTimezone,
            long warningBytes, long limitBytes, boolean metered) {
        this(template, cycleDay, cycleTimezone, warningBytes, limitBytes, SNOOZE_NEVER,
                SNOOZE_NEVER, metered, false);
    }

    public NetworkPolicy(NetworkTemplate template, int cycleDay, String cycleTimezone,
            long warningBytes, long limitBytes, long lastWarningSnooze, long lastLimitSnooze,
///LEWA BEGIN
//            boolean metered, boolean inferred) {
            boolean metered, boolean inferred, long adjustBytes, long adjustTime) {
        this.adjustBytes = adjustBytes;
        this.adjustTime = adjustTime;
///LEWA END
        this.template = checkNotNull(template, "missing NetworkTemplate");
        this.cycleDay = cycleDay;
        this.cycleTimezone = checkNotNull(cycleTimezone, "missing cycleTimezone");
        this.warningBytes = warningBytes;
        this.limitBytes = limitBytes;
        this.lastWarningSnooze = lastWarningSnooze;
        this.lastLimitSnooze = lastLimitSnooze;
        this.metered = metered;
        this.inferred = inferred;
    /// M: Gemini Feature Option     
     this.active = false;
    }

///LEWA BEGIN
    public NetworkPolicy(NetworkTemplate template, int cycleDay, String cycleTimezone,
            long warningBytes, long limitBytes, long lastWarningSnooze, long lastLimitSnooze,
            boolean metered, boolean inferred) {
        this(template, cycleDay, cycleTimezone, warningBytes, limitBytes, lastWarningSnooze,
            lastLimitSnooze, metered, inferred, 0, 0);
    }
///LEWA END

    public NetworkPolicy(Parcel in) {
        template = in.readParcelable(null);
        cycleDay = in.readInt();
        cycleTimezone = in.readString();
        warningBytes = in.readLong();
        limitBytes = in.readLong();
        lastWarningSnooze = in.readLong();
        lastLimitSnooze = in.readLong();
        metered = in.readInt() != 0;
        inferred = in.readInt() != 0;
///LEWA BEGIN
        adjustBytes = in.readLong();
        adjustTime = in.readLong();
///LEWA END
    /// M: Gemini Feature Option     
    active = (in.readInt() == 0) ? false : true;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(template, flags);
        dest.writeInt(cycleDay);
        dest.writeString(cycleTimezone);
        dest.writeLong(warningBytes);
        dest.writeLong(limitBytes);
        dest.writeLong(lastWarningSnooze);
        dest.writeLong(lastLimitSnooze);
        dest.writeInt(metered ? 1 : 0);
        dest.writeInt(inferred ? 1 : 0);
///LEWA BEGIN
        dest.writeLong(adjustBytes);
        dest.writeLong(adjustTime);
///LEWA END
    /// M: Gemini Feature Option     
    dest.writeInt(active ? 1 : 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Test if given measurement is over {@link #warningBytes}.
     */
    public boolean isOverWarning(long totalBytes) {
///LEWA BEGIN
        totalBytes += getAdjustBytes();
///LEWA END
        return warningBytes != WARNING_DISABLED && totalBytes >= warningBytes;
    }

    /**
     * Test if given measurement is near enough to {@link #limitBytes} to be
     * considered over-limit.
     */
    public boolean isOverLimit(long totalBytes) {
        // over-estimate, since kernel will trigger limit once first packet
        // trips over limit.
        totalBytes += 2 * DEFAULT_MTU;
///LEWA BEGIN
        totalBytes += getAdjustBytes();
///LEWA END
        return limitBytes != LIMIT_DISABLED && totalBytes >= limitBytes;
    }

///LEWA BEGIN
    public long getAdjustBytes(){
        return snapToNextCycleDay() == adjustTime ? adjustBytes : 0;
    }
///LEWA END
    /**
     * Clear any existing snooze values, setting to {@link #SNOOZE_NEVER}.
     */
    public void clearSnooze() {
        lastWarningSnooze = SNOOZE_NEVER;
        lastLimitSnooze = SNOOZE_NEVER;
    }

    /**
     * Test if this policy has a cycle defined, after which usage should reset.
     */
    public boolean hasCycle() {
        return cycleDay != CYCLE_NONE;
    }

    @Override
    public int compareTo(NetworkPolicy another) {
        if (another == null || another.limitBytes == LIMIT_DISABLED) {
            // other value is missing or disabled; we win
            return -1;
        }
        if (limitBytes == LIMIT_DISABLED || another.limitBytes < limitBytes) {
            // we're disabled or other limit is smaller; they win
            return 1;
        }
        return 0;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(template, cycleDay, cycleTimezone, warningBytes, limitBytes,
///LEWA BEGIN
//                lastWarningSnooze, lastLimitSnooze, metered, inferred);
                adjustBytes, adjustTime, lastWarningSnooze, lastLimitSnooze, metered, inferred);
///LEWA END
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof NetworkPolicy) {
            final NetworkPolicy other = (NetworkPolicy) obj;
            return cycleDay == other.cycleDay && warningBytes == other.warningBytes
                    && limitBytes == other.limitBytes
///LEWA BEGIN
                    && adjustBytes == other.adjustBytes
                    && adjustTime == other.adjustTime
///LEWA END
                    && lastWarningSnooze == other.lastWarningSnooze
                    && lastLimitSnooze == other.lastLimitSnooze && metered == other.metered
                    && inferred == other.inferred
                    && Objects.equal(cycleTimezone, other.cycleTimezone)
                    && Objects.equal(template, other.template);
        }
        return false;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder("NetworkPolicy");
        builder.append("[").append(template).append("]:");
        builder.append(" cycleDay=").append(cycleDay);
        builder.append(", cycleTimezone=").append(cycleTimezone);
        builder.append(", warningBytes=").append(warningBytes);
        builder.append(", limitBytes=").append(limitBytes);
///LEWA BEGIN
        builder.append(", adjustBytes=").append(adjustBytes);
        builder.append(", adjustTime=").append(adjustTime);
///LEWA END
        builder.append(", lastWarningSnooze=").append(lastWarningSnooze);
        builder.append(", lastLimitSnooze=").append(lastLimitSnooze);
        builder.append(", metered=").append(metered);
        builder.append(", inferred=").append(inferred);
     /// M: Gemini Feature Option         
    builder.append(", active=").append(active);

        return builder.toString();
    }

    public static final Creator<NetworkPolicy> CREATOR = new Creator<NetworkPolicy>() {
        @Override
        public NetworkPolicy createFromParcel(Parcel in) {
            return new NetworkPolicy(in);
        }

        @Override
        public NetworkPolicy[] newArray(int size) {
            return new NetworkPolicy[size];
        }
    };
}
