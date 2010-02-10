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

package com.android.email;

import com.android.email.activity.setup.AccountSecurity;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.service.MailService;

import android.app.DeviceAdmin;
import android.app.DevicePolicyManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/**
 * Utility functions to support reading and writing security policies, and handshaking the device
 * into and out of various security states.
 */
public class SecurityPolicy {

    private static SecurityPolicy sInstance = null;
    private Context mContext;
    private DevicePolicyManager mDPM;
    private ComponentName mAdminName;
    private PolicySet mAggregatePolicy;
    private boolean mNotificationActive;
    private boolean mAdminEnabled;

    private static final PolicySet NO_POLICY_SET =
            new PolicySet(0, PolicySet.PASSWORD_MODE_NONE, 0, 0, false);

    /**
     * This projection on Account is for scanning/reading 
     */
    private static final String[] ACCOUNT_SECURITY_PROJECTION = new String[] {
        AccountColumns.ID, AccountColumns.SECURITY_FLAGS
    };
    private static final int ACCOUNT_SECURITY_COLUMN_FLAGS = 1;
    // Note, this handles the NULL case to deal with older accounts where the column was added
    private static final String WHERE_ACCOUNT_SECURITY_NONZERO =
        Account.SECURITY_FLAGS + " IS NOT NULL AND " + Account.SECURITY_FLAGS + "!=0";

    /**
     * This projection on Account is for clearing the "security hold" column.  Also includes
     * the security flags column, so we can use it for selecting.
     */
    private static final String[] ACCOUNT_FLAGS_PROJECTION = new String[] {
        AccountColumns.ID, AccountColumns.FLAGS, AccountColumns.SECURITY_FLAGS
    };
    private static final int ACCOUNT_FLAGS_COLUMN_ID = 0;
    private static final int ACCOUNT_FLAGS_COLUMN_FLAGS = 1;

   /**
    * These are hardcoded limits based on knowledge of the current DevicePolicyManager
    * and screen lock mechanisms.  Wherever possible, these should be replaced with queries of
    * dynamic capabilities of the device (e.g. what password modes are supported?)
    */
   private static final int LIMIT_MIN_PASSWORD_LENGTH = 16;
   private static final int LIMIT_PASSWORD_MODE = PolicySet.PASSWORD_MODE_STRONG;
   private static final int LIMIT_SCREENLOCK_TIME = PolicySet.SCREEN_LOCK_TIME_MAX;

    /**
     * Get the security policy instance
     */
    public synchronized static SecurityPolicy getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new SecurityPolicy(context);
        }
        return sInstance;
    }

    /**
     * Private constructor (one time only)
     */
    private SecurityPolicy(Context context) {
        mContext = context;
        mDPM = null;
        mAdminName = new ComponentName(context, PolicyAdmin.class);
        mAggregatePolicy = null;
        mNotificationActive = false;
    }

    /**
     * For testing only: Inject context into already-created instance
     */
    /* package */ void setContext(Context context) {
        mContext = context;
    }

    /**
     * Compute the aggregate policy for all accounts that require it, and record it.
     *
     * The business logic is as follows:
     *  min password length         take the max
     *  password mode               take the max (strongest mode)
     *  max password fails          take the min
     *  max screen lock time        take the min
     *  require remote wipe         take the max (logical or)
     * 
     * @return a policy representing the strongest aggregate.  If no policy sets are defined,
     * a lightweight "nothing required" policy will be returned.  Never null.
     */
    /* package */ PolicySet computeAggregatePolicy() {
        boolean policiesFound = false;

        int minPasswordLength = Integer.MIN_VALUE;
        int passwordMode = Integer.MIN_VALUE;
        int maxPasswordFails = Integer.MAX_VALUE;
        int maxScreenLockTime = Integer.MAX_VALUE;
        boolean requireRemoteWipe = false;

        Cursor c = mContext.getContentResolver().query(Account.CONTENT_URI,
                ACCOUNT_SECURITY_PROJECTION, WHERE_ACCOUNT_SECURITY_NONZERO, null, null);
        try {
            while (c.moveToNext()) {
                int flags = c.getInt(ACCOUNT_SECURITY_COLUMN_FLAGS);
                if (flags != 0) {
                    PolicySet p = new PolicySet(flags);
                    minPasswordLength = Math.max(p.mMinPasswordLength, minPasswordLength);
                    passwordMode  = Math.max(p.mPasswordMode, passwordMode);
                    if (p.mMaxPasswordFails > 0) {
                        maxPasswordFails = Math.min(p.mMaxPasswordFails, maxPasswordFails);
                    }
                    if (p.mMaxScreenLockTime > 0) {
                        maxScreenLockTime = Math.min(p.mMaxScreenLockTime, maxScreenLockTime);
                    }
                    requireRemoteWipe |= p.mRequireRemoteWipe;
                    policiesFound = true;
                }
            }
        } finally {
            c.close();
        }
        if (policiesFound) {
            // final cleanup pass converts any untouched min/max values to zero (not specified)
            if (minPasswordLength == Integer.MIN_VALUE) minPasswordLength = 0;
            if (passwordMode == Integer.MIN_VALUE) passwordMode = 0;
            if (maxPasswordFails == Integer.MAX_VALUE) maxPasswordFails = 0;
            if (maxScreenLockTime == Integer.MAX_VALUE) maxScreenLockTime = 0;

            return new PolicySet(minPasswordLength, passwordMode, maxPasswordFails,
                    maxScreenLockTime, requireRemoteWipe);
        } else {
            return NO_POLICY_SET;
        }
    }

    /**
     * Return updated aggregate policy, from cached value if possible
     */
    public synchronized PolicySet getAggregatePolicy() {
        if (mAggregatePolicy == null) {
            mAggregatePolicy = computeAggregatePolicy();
        }
        return mAggregatePolicy;
    }

    /**
     * Get the dpm.  This mainly allows us to make some utility calls without it, for testing.
     */
    private synchronized DevicePolicyManager getDPM() {
        if (mDPM == null) {
            mDPM = (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        }
        return mDPM;
    }

    /**
     * API: Query used to determine if a given policy is "possible" (irrespective of current
     * device state.  This is used when creating new accounts.
     *
     * TODO: This is hardcoded based on knowledge of the current DevicePolicyManager
     * and screen lock mechanisms.  It would be nice to replace these tests with something
     * more dynamic.
     *
     * @param policies the policies requested
     * @return true if the policies are supported, false if not supported
     */
    public boolean isSupported(PolicySet policies) {
        if (policies.mMinPasswordLength > LIMIT_MIN_PASSWORD_LENGTH) {
            return false;
        }
        if (policies.mPasswordMode > LIMIT_PASSWORD_MODE ) {
            return false;
        }
        // No limit on password fail count
        if (policies.mMaxScreenLockTime > LIMIT_SCREENLOCK_TIME ) {
            return false;
        }
        // No limit on remote wipe capable

        return true;
    }

    /**
     * API: Report that policies may have been updated due to rewriting values in an Account.
     * @param accountId the account that has been updated, -1 if unknown/deleted
     */
    public synchronized void updatePolicies(long accountId) {
        mAggregatePolicy = null;
    }

    /**
     * API: Report that policies may have been updated *and* the caller vouches that the
     * change is a reduction in policies.  This forces an immediate change to device state.
     * Typically used when deleting accounts, although we may use it for server-side policy
     * rollbacks.
     */
    public void reducePolicies() {
        updatePolicies(-1);
        setActivePolicies();
    }

    /**
     * API: Query used to determine if a given policy is "active" (the device is operating at
     * the required security level).
     *
     * This can be used when syncing a specific account, by passing a specific set of policies
     * for that account.  Or, it can be used at any time to compare the device
     * state against the aggregate set of device policies stored in all accounts.
     *
     * This method is for queries only, and does not trigger any change in device state.
     *
     * @param policies the policies requested, or null to check aggregate stored policies
     * @return true if the policies are active, false if not active
     */
    public boolean isActive(PolicySet policies) {
        // select aggregate set if needed
        if (policies == null) {
            policies = getAggregatePolicy();
        }
        // quick check for the "empty set" of no policies
        if (policies == NO_POLICY_SET) {
            return true;
        }
        DevicePolicyManager dpm = getDPM();
        if (dpm.isAdminActive(mAdminName)) {
            // check each policy explicitly
            if (policies.mMinPasswordLength > 0) {
                if (dpm.getPasswordMinimumLength(mAdminName) < policies.mMinPasswordLength) {
                    return false;
                }
            }
            if (policies.mPasswordMode > 0) {
                if (dpm.getPasswordQuality(mAdminName) < policies.getDPManagerPasswordQuality()) {
                    return false;
                }
                if (!dpm.isActivePasswordSufficient()) {
                    return false;
                }
            }
            if (policies.mMaxScreenLockTime > 0) {
                // Note, we use seconds, dpm uses milliseconds
                if (dpm.getMaximumTimeToLock(mAdminName) > policies.mMaxScreenLockTime * 1000) {
                    return false;
                }
            }
            // password failures are counted locally - no test required here
            // no check required for remote wipe (it's supported, if we're the admin)

            // making it this far means we passed!
            return true;
        }
        // return false, not active
        return false;
    }

    /**
     * Set the requested security level based on the aggregate set of requests.
     * If the set is empty, we release our device administration.  If the set is non-empty,
     * we only proceed if we are already active as an admin.
     */
    public void setActivePolicies() {
        DevicePolicyManager dpm = getDPM();
        // compute aggregate set of policies
        PolicySet policies = getAggregatePolicy();
        // if empty set, detach from policy manager
        if (policies == NO_POLICY_SET) {
            dpm.removeActiveAdmin(mAdminName);
        } else if (dpm.isAdminActive(mAdminName)) {
            // set each policy in the policy manager
            // password mode & length
            dpm.setPasswordQuality(mAdminName, policies.getDPManagerPasswordQuality());
            dpm.setPasswordMinimumLength(mAdminName, policies.mMinPasswordLength);
            // screen lock time
            dpm.setMaximumTimeToLock(mAdminName, policies.mMaxScreenLockTime * 1000);
            // local wipe (failed passwords limit)
            dpm.setMaximumFailedPasswordsForWipe(mAdminName, policies.mMaxPasswordFails);
        }
    }

    /**
     * API: Set/Clear the "hold" flag in any account.  This flag serves a dual purpose:
     * Setting it gives us an indication that it was blocked, and clearing it gives EAS a
     * signal to try syncing again.
     */
    public void setAccountHoldFlag(Account account, boolean newState) {
        if (newState) {
            account.mFlags |= Account.FLAGS_SECURITY_HOLD;
        } else {
            account.mFlags &= ~Account.FLAGS_SECURITY_HOLD;
        }
        ContentValues cv = new ContentValues();
        cv.put(AccountColumns.FLAGS, account.mFlags);
        account.update(mContext, cv);
    }

    /**
     * Clear all account hold flags that are set.  This will trigger watchers, and in particular
     * will cause EAS to try and resync the account(s).
     */
    public void clearAccountHoldFlags() {
        ContentResolver resolver = mContext.getContentResolver();
        Cursor c = resolver.query(Account.CONTENT_URI, ACCOUNT_FLAGS_PROJECTION,
                WHERE_ACCOUNT_SECURITY_NONZERO, null, null);
        try {
            while (c.moveToNext()) {
                int flags = c.getInt(ACCOUNT_FLAGS_COLUMN_FLAGS);
                if (0 != (flags & Account.FLAGS_SECURITY_HOLD)) {
                    ContentValues cv = new ContentValues();
                    cv.put(AccountColumns.FLAGS, flags & ~Account.FLAGS_SECURITY_HOLD);
                    long accountId = c.getLong(ACCOUNT_FLAGS_COLUMN_ID);
                    Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, accountId);
                    resolver.update(uri, cv, null, null);
                }
            }
        } finally {
            c.close();
        }
    }

    /**
     * API: Sync service should call this any time a sync fails due to isActive() returning false.
     * This will kick off the notify-acquire-admin-state process and/or increase the security level.
     * The caller needs to write the required policies into this account before making this call.
     * Should not be called from UI thread - uses DB lookups to prepare new notifications
     *
     * @param accountId the account for which sync cannot proceed
     */
    public void policiesRequired(long accountId) {
        Account account = EmailContent.Account.restoreAccountWithId(mContext, accountId);

        // Mark the account as "on hold".
        setAccountHoldFlag(account, true);

        // Put up a notification (unless there already is one)
        synchronized (this) {
            if (mNotificationActive) {
                // no need to do anything - we've already been notified, and we've already
                // put up a notification
                return;
            } else {
                // Prepare & post a notification
                // record that we're watching this one
                mNotificationActive = true;
            }
        }
        // At this point, we will put up a notification

        String tickerText = mContext.getString(R.string.security_notification_ticker_fmt,
                account.getDisplayName());
        String contentTitle = mContext.getString(R.string.security_notification_content_title);
        String contentText = account.getDisplayName();
        String ringtoneString = account.getRingtone();
        Uri ringTone = (ringtoneString == null) ? null : Uri.parse(ringtoneString);
        boolean vibrate = 0 != (account.mFlags & Account.FLAGS_VIBRATE);

        Intent intent = AccountSecurity.actionUpdateSecurityIntent(mContext, accountId);
        PendingIntent pending =
            PendingIntent.getActivity(mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new Notification(R.drawable.stat_notify_email_generic,
                tickerText, System.currentTimeMillis());
        notification.setLatestEventInfo(mContext, contentTitle, contentText, pending);

        // Use the account's notification rules for sound & vibrate (but always notify)
        notification.sound = ringTone;
        if (vibrate) {
            notification.defaults |= Notification.DEFAULT_VIBRATE;
        }
        notification.flags |= Notification.FLAG_SHOW_LIGHTS;
        notification.defaults |= Notification.DEFAULT_LIGHTS;

        NotificationManager notificationManager =
            (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(MailService.NOTIFICATION_ID_SECURITY_NEEDED, notification);
    }

    /**
     * Called from the notification's intent receiver to register that the notification can be
     * cleared now.
     */
    public synchronized void clearNotification(long accountId) {
        NotificationManager notificationManager =
            (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(MailService.NOTIFICATION_ID_SECURITY_NEEDED);
        mNotificationActive = false;
    }

    /**
     * API: Remote wipe (from server).  This is final, there is no confirmation.  It will only
     * return to the caller if there is an unexpected failure.
     */
    public void remoteWipe() {
        DevicePolicyManager dpm = getDPM();
        if (dpm.isAdminActive(mAdminName)) {
            dpm.wipeData(0);
        } else {
            Log.d(Email.LOG_TAG, "Could not remote wipe because not device admin.");
        }
    }

    /**
     * Class for tracking policies and reading/writing into accounts
     */
    public static class PolicySet {

        // Security (provisioning) flags
            // bits 0..4: password length (0=no password required)
        private static final int PASSWORD_LENGTH_MASK = 31;
        private static final int PASSWORD_LENGTH_SHIFT = 0;
        public static final int PASSWORD_LENGTH_MAX = 31;
            // bits 5..8: password mode
        private static final int PASSWORD_MODE_SHIFT = 5;
        private static final int PASSWORD_MODE_MASK = 15 << PASSWORD_MODE_SHIFT;
        public static final int PASSWORD_MODE_NONE = 0 << PASSWORD_MODE_SHIFT;
        public static final int PASSWORD_MODE_SIMPLE = 1 << PASSWORD_MODE_SHIFT;
        public static final int PASSWORD_MODE_STRONG = 2 << PASSWORD_MODE_SHIFT;
            // bits 9..13: password failures -> wipe device (0=disabled)
        private static final int PASSWORD_MAX_FAILS_SHIFT = 9;
        private static final int PASSWORD_MAX_FAILS_MASK = 31 << PASSWORD_MAX_FAILS_SHIFT;
        public static final int PASSWORD_MAX_FAILS_MAX = 31;
            // bits 14..24: seconds to screen lock (0=not required)
        private static final int SCREEN_LOCK_TIME_SHIFT = 14;
        private static final int SCREEN_LOCK_TIME_MASK = 2047 << SCREEN_LOCK_TIME_SHIFT;
        public static final int SCREEN_LOCK_TIME_MAX = 2047;
            // bit 25: remote wipe capability required
        private static final int REQUIRE_REMOTE_WIPE = 1 << 25;

        public final int mMinPasswordLength;
        public final int mPasswordMode;
        public final int mMaxPasswordFails;
        public final int mMaxScreenLockTime;
        public final boolean mRequireRemoteWipe;

        /**
         * Create from raw values.
         * @param minPasswordLength (0=not enforced)
         * @param passwordMode
         * @param maxPasswordFails (0=not enforced)
         * @param maxScreenLockTime in seconds (0=not enforced)
         * @param requireRemoteWipe
         * @throws IllegalArgumentException when any arguments are outside of legal ranges.
         */
        public PolicySet(int minPasswordLength, int passwordMode, int maxPasswordFails,
                int maxScreenLockTime, boolean requireRemoteWipe) throws IllegalArgumentException {
            if (minPasswordLength > PASSWORD_LENGTH_MAX) {
                throw new IllegalArgumentException("password length");
            }
            if (passwordMode < PASSWORD_MODE_NONE
                    || passwordMode > PASSWORD_MODE_STRONG) {
                throw new IllegalArgumentException("password mode");
            }
            if (maxPasswordFails > PASSWORD_MAX_FAILS_MAX) {
                throw new IllegalArgumentException("password max fails");
            }
            if (maxScreenLockTime > SCREEN_LOCK_TIME_MAX) {
                throw new IllegalArgumentException("max screen lock time");
            }

            mMinPasswordLength = minPasswordLength;
            mPasswordMode = passwordMode;
            mMaxPasswordFails = maxPasswordFails;
            mMaxScreenLockTime = maxScreenLockTime;
            mRequireRemoteWipe = requireRemoteWipe;
        }

        /**
         * Create from values encoded in an account
         * @param account
         */
        public PolicySet(Account account) {
            this(account.mSecurityFlags);
        }

        /**
         * Create from values encoded in an account flags int
         */
        public PolicySet(int flags) {
            mMinPasswordLength =
                (flags & PASSWORD_LENGTH_MASK) >> PASSWORD_LENGTH_SHIFT;
            mPasswordMode =
                (flags & PASSWORD_MODE_MASK);
            mMaxPasswordFails =
                (flags & PASSWORD_MAX_FAILS_MASK) >> PASSWORD_MAX_FAILS_SHIFT;
            mMaxScreenLockTime =
                (flags & SCREEN_LOCK_TIME_MASK) >> SCREEN_LOCK_TIME_SHIFT;
            mRequireRemoteWipe = 0 != (flags & REQUIRE_REMOTE_WIPE);
        }

        /**
         * Helper to map our internal encoding to DevicePolicyManager password modes.
         */
        public int getDPManagerPasswordQuality() {
            switch (mPasswordMode) {
                case PASSWORD_MODE_SIMPLE:
                    return DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
                case PASSWORD_MODE_STRONG:
                    return DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC;
                default:
                    return DevicePolicyManager .PASSWORD_QUALITY_UNSPECIFIED;
            }
        }

        /**
         * Record flags (and a sync key for the flags) into an Account
         * Note: the hash code is defined as the encoding used in Account
         *
         * @param account to write the values mSecurityFlags and mSecuritySyncKey
         * @param syncKey the value to write into the account's mSecuritySyncKey
         * @param update if true, also writes the account back to the provider (updating only
         *  the fields changed by this API)
         * @param context a context for writing to the provider
         * @return true if the actual policies changed, false if no change (note, sync key
         *  does not affect this)
         */
        public boolean writeAccount(Account account, String syncKey, boolean update,
                Context context) {
            int newFlags = hashCode();
            boolean dirty = (newFlags != account.mSecurityFlags);
            account.mSecurityFlags = newFlags;
            account.mSecuritySyncKey = syncKey;
            if (update) {
                if (account.isSaved()) {
                    ContentValues cv = new ContentValues();
                    cv.put(AccountColumns.SECURITY_FLAGS, account.mSecurityFlags);
                    cv.put(AccountColumns.SECURITY_SYNC_KEY, account.mSecuritySyncKey);
                    account.update(context, cv);
                } else {
                    account.save(context);
                }
            }
            return dirty;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof PolicySet) {
                PolicySet other = (PolicySet)o;
                return (this.mMinPasswordLength == other.mMinPasswordLength)
                        && (this.mPasswordMode == other.mPasswordMode)
                        && (this.mMaxPasswordFails == other.mMaxPasswordFails)
                        && (this.mMaxScreenLockTime == other.mMaxScreenLockTime)
                        && (this.mRequireRemoteWipe == other.mRequireRemoteWipe);
            }
            return false;
        }

        /**
         * Note: the hash code is defined as the encoding used in Account
         */
        @Override
        public int hashCode() {
            int flags = 0;
            flags = mMinPasswordLength << PASSWORD_LENGTH_SHIFT;
            flags |= mPasswordMode;
            flags |= mMaxPasswordFails << PASSWORD_MAX_FAILS_SHIFT;
            flags |= mMaxScreenLockTime << SCREEN_LOCK_TIME_SHIFT;
            if (mRequireRemoteWipe) {
                flags |= REQUIRE_REMOTE_WIPE;
            }
            return flags;
        }

        @Override
        public String toString() {
            return "{ " + "pw-len-min=" + mMinPasswordLength + " pw-mode=" + mPasswordMode
                    + " pw-fails-max=" + mMaxPasswordFails + " screenlock-max="
                    + mMaxScreenLockTime + " remote-wipe-req=" + mRequireRemoteWipe + "}";
        }
    }

    /**
     * If we are not the active device admin, try to become so.
     *
     * @return true if we are already active, false if we are not
     */
    public boolean isActiveAdmin() {
        DevicePolicyManager dpm = getDPM();
        return dpm.isAdminActive(mAdminName);
    }

    /**
     * Report admin component name - for making calls into device policy manager
     */
    public ComponentName getAdminComponent() {
        return mAdminName;
    }

    /**
     * Internal handler for enabled/disabled transitions.  Handles DeviceAdmin.onEnabled and
     * and DeviceAdmin.onDisabled.
     */
    private void onAdminEnabled(boolean isEnabled) {
        if (isEnabled && !mAdminEnabled) {
            // TODO: transition to enabled state
        } else if (!isEnabled && mAdminEnabled) {
            // TODO: transition to disabled state
        }
        mAdminEnabled = isEnabled;
    }

    /**
     * Device Policy administrator.  This is primarily a listener for device state changes.
     * Note:  This is instantiated by incoming messages.
     * Note:  We do not implement onPasswordFailed() because the default behavior of the
     *        DevicePolicyManager - complete local wipe after 'n' failures - is sufficient.
     */
    public static class PolicyAdmin extends DeviceAdmin {

        /**
         * Called after the administrator is first enabled.
         */
        @Override
        public void onEnabled(Context context, Intent intent) {
            SecurityPolicy.getInstance(context).onAdminEnabled(true);
        }
        
        /**
         * Called prior to the administrator being disabled.
         */
        @Override
        public void onDisabled(Context context, Intent intent) {
            SecurityPolicy.getInstance(context).onAdminEnabled(false);
        }
        
        /**
         * Called after the user has changed their password.
         */
        @Override
        public void onPasswordChanged(Context context, Intent intent) {
            SecurityPolicy.getInstance(context).clearAccountHoldFlags();
        }
    }
}
