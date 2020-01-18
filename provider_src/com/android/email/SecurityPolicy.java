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

import android.app.admin.DeviceAdminInfo;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;

import com.android.email.NotificationController;
import com.android.email.NotificationControllerCreatorHolder;
import com.android.email.provider.AccountReconciler;
import com.android.email.provider.EmailProvider;
import com.android.email.service.EmailBroadcastProcessorService;
import com.android.email.service.EmailServiceUtils;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.PolicyColumns;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.utility.TextUtilities;
import com.android.emailcommon.utility.Utility;
import com.android.mail.utils.LogUtils;
import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;

/**
 * Utility functions to support reading and writing security policies, and handshaking the device
 * into and out of various security states.
 */
public class SecurityPolicy {
    private static final String TAG = "Email";
    private static SecurityPolicy sInstance = null;
    private Context mContext;
    private DevicePolicyManager mDPM;
    private final ComponentName mAdminName;
    private Policy mAggregatePolicy;

    // Messages used for DevicePolicyManager callbacks
    private static final int DEVICE_ADMIN_MESSAGE_ENABLED = 1;
    private static final int DEVICE_ADMIN_MESSAGE_DISABLED = 2;
    private static final int DEVICE_ADMIN_MESSAGE_PASSWORD_CHANGED = 3;
    private static final int DEVICE_ADMIN_MESSAGE_PASSWORD_EXPIRING = 4;

    private static final String HAS_PASSWORD_EXPIRATION =
        PolicyColumns.PASSWORD_EXPIRATION_DAYS + ">0";

    /**
     * Get the security policy instance
     */
    public synchronized static SecurityPolicy getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new SecurityPolicy(context.getApplicationContext());
        }
        return sInstance;
    }

    /**
     * Private constructor (one time only)
     */
    private SecurityPolicy(Context context) {
        mContext = context.getApplicationContext();
        mDPM = null;
        mAdminName = new ComponentName(context, PolicyAdmin.class);
        mAggregatePolicy = null;
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
     *  password history            take the max (strongest mode)
     *  password expiration         take the min (strongest mode)
     *  password complex chars      take the max (strongest mode)
     *  encryption                  take the max (logical or)
     *
     * @return a policy representing the strongest aggregate.  If no policy sets are defined,
     * a lightweight "nothing required" policy will be returned.  Never null.
     */
    @VisibleForTesting
    Policy computeAggregatePolicy() {
        return Policy.NO_POLICY;
    }

    /**
     * Return updated aggregate policy, from cached value if possible
     */
    public synchronized Policy getAggregatePolicy() {
        if (mAggregatePolicy == null) {
            mAggregatePolicy = computeAggregatePolicy();
        }
        return mAggregatePolicy;
    }

    /**
     * Get the dpm.  This mainly allows us to make some utility calls without it, for testing.
     */
    /* package */ synchronized DevicePolicyManager getDPM() {
        if (mDPM == null) {
            mDPM = (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        }
        return mDPM;
    }

    /**
     * API: Report that policies may have been updated due to rewriting values in an Account; we
     * clear the aggregate policy (so it can be recomputed) and set the policies in the DPM
     */
    public synchronized void policiesUpdated() {
        mAggregatePolicy = null;
        setActivePolicies();
    }

    /**
     * API: Report that policies may have been updated *and* the caller vouches that the
     * change is a reduction in policies.  This forces an immediate change to device state.
     * Typically used when deleting accounts, although we may use it for server-side policy
     * rollbacks.
     */
    public void reducePolicies() {
        if (DebugUtils.DEBUG) {
            LogUtils.d(TAG, "reducePolicies");
        }
        policiesUpdated();
    }

    /**
     * API: Query used to determine if a given policy is "active" (the device is operating at
     * the required security level).
     *
     * @param policy the policies requested, or null to check aggregate stored policies
     * @return true if the requested policies are active, false if not.
     */
    public boolean isActive(Policy policy) {
        int reasons = getInactiveReasons(policy);
        if (DebugUtils.DEBUG && (reasons != 0)) {
            StringBuilder sb = new StringBuilder("isActive for " + policy + ": ");
            sb.append("FALSE -> ");
            if ((reasons & INACTIVE_NEED_ACTIVATION) != 0) {
                sb.append("no_admin ");
            }
            if ((reasons & INACTIVE_NEED_CONFIGURATION) != 0) {
                sb.append("config ");
            }
            if ((reasons & INACTIVE_NEED_PASSWORD) != 0) {
                sb.append("password ");
            }
            if ((reasons & INACTIVE_NEED_ENCRYPTION) != 0) {
                sb.append("encryption ");
            }
            if ((reasons & INACTIVE_PROTOCOL_POLICIES) != 0) {
                sb.append("protocol ");
            }
            LogUtils.d(TAG, sb.toString());
        }
        return reasons == 0;
    }

    /**
     * Return bits from isActive:  Device Policy Manager has not been activated
     */
    public final static int INACTIVE_NEED_ACTIVATION = 1;

    /**
     * Return bits from isActive:  Some required configuration is not correct (no user action).
     */
    public final static int INACTIVE_NEED_CONFIGURATION = 2;

    /**
     * Return bits from isActive:  Password needs to be set or updated
     */
    public final static int INACTIVE_NEED_PASSWORD = 4;

    /**
     * Return bits from isActive:  Encryption has not be enabled
     */
    public final static int INACTIVE_NEED_ENCRYPTION = 8;

    /**
     * Return bits from isActive:  Protocol-specific policies cannot be enforced
     */
    public final static int INACTIVE_PROTOCOL_POLICIES = 16;

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
     * NOTE:  If there are multiple accounts with password expiration policies, the device
     * password will be set to expire in the shortest required interval (most secure).  This method
     * will return 'false' as soon as the password expires - irrespective of which account caused
     * the expiration.  In other words, all accounts (that require expiration) will run/stop
     * based on the requirements of the account with the shortest interval.
     *
     * @param policy the policies requested, or null to check aggregate stored policies
     * @return zero if the requested policies are active, non-zero bits indicates that more work
     * is needed (typically, by the user) before the required security polices are fully active.
     */
    public int getInactiveReasons(Policy policy) {
        return 0;
    }

    /**
     * Set the requested security level based on the aggregate set of requests.
     * If the set is empty, we release our device administration.  If the set is non-empty,
     * we only proceed if we are already active as an admin.
     */
    public void setActivePolicies() {
        DevicePolicyManager dpm = getDPM();
        // compute aggregate set of policies
        Policy aggregatePolicy = getAggregatePolicy();
        // if empty set, detach from policy manager
        if (aggregatePolicy == Policy.NO_POLICY) {
            if (DebugUtils.DEBUG) {
                LogUtils.d(TAG, "setActivePolicies: none, remove admin");
            }
            dpm.removeActiveAdmin(mAdminName);
        }
    }

    /**
     * Convenience method; see javadoc below
     */
    public static void setAccountHoldFlag(Context context, long accountId, boolean newState) {
        Account account = Account.restoreAccountWithId(context, accountId);
        if (account != null) {
            setAccountHoldFlag(context, account, newState);
            if (newState) {
                // Make sure there's a notification up
                final NotificationController nc =
                        NotificationControllerCreatorHolder.getInstance(context);
                nc.showSecurityNeededNotification(account);
            }
        }
    }

    /**
     * API: Set/Clear the "hold" flag in any account.  This flag serves a dual purpose:
     * Setting it gives us an indication that it was blocked, and clearing it gives EAS a
     * signal to try syncing again.
     * @param context context
     * @param account the account whose hold flag is to be set/cleared
     * @param newState true = security hold, false = free to sync
     */
    public static void setAccountHoldFlag(Context context, Account account, boolean newState) {
        if (newState) {
            account.mFlags |= Account.FLAGS_SECURITY_HOLD;
        } else {
            account.mFlags &= ~Account.FLAGS_SECURITY_HOLD;
        }
        ContentValues cv = new ContentValues();
        cv.put(AccountColumns.FLAGS, account.mFlags);
        account.update(context, cv);
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
        Account account = Account.restoreAccountWithId(mContext, accountId);
        // In case the account has been deleted, just return
        if (account == null) return;
        if (account.mPolicyKey == 0) return;
        Policy policy = Policy.restorePolicyWithId(mContext, account.mPolicyKey);
        if (policy == null) return;
        if (DebugUtils.DEBUG) {
            LogUtils.d(TAG, "policiesRequired for " + account.mDisplayName + ": " + policy);
        }

        // Mark the account as "on hold".
        setAccountHoldFlag(mContext, account, true);

        // Put up an appropriate notification
        final NotificationController nc =
                NotificationControllerCreatorHolder.getInstance(mContext);
        if (policy.mProtocolPoliciesUnsupported == null) {
            nc.showSecurityNeededNotification(account);
        } else {
            nc.showSecurityUnsupportedNotification(account);
        }
    }

    public static void clearAccountPolicy(Context context, Account account) {
        setAccountPolicy(context, account, null, null);
    }

    /**
     * Set the policy for an account atomically; this also removes any other policy associated with
     * the account and sets the policy key for the account.  If policy is null, the policyKey is
     * set to 0 and the securitySyncKey to null.  Also, update the account object to reflect the
     * current policyKey and securitySyncKey
     * @param context the caller's context
     * @param account the account whose policy is to be set
     * @param policy the policy to set, or null if we're clearing the policy
     * @param securitySyncKey the security sync key for this account (ignored if policy is null)
     */
    public static void setAccountPolicy(Context context, Account account, Policy policy,
            String securitySyncKey) {
        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

        // Make sure this is a valid policy set
        if (policy != null) {
            policy.normalize();
            // Add the new policy (no account will yet reference this)
            ops.add(ContentProviderOperation.newInsert(
                    Policy.CONTENT_URI).withValues(policy.toContentValues()).build());
            // Make the policyKey of the account our newly created policy, and set the sync key
            ops.add(ContentProviderOperation.newUpdate(
                    ContentUris.withAppendedId(Account.CONTENT_URI, account.mId))
                    .withValueBackReference(AccountColumns.POLICY_KEY, 0)
                    .withValue(AccountColumns.SECURITY_SYNC_KEY, securitySyncKey)
                    .build());
        } else {
            ops.add(ContentProviderOperation.newUpdate(
                    ContentUris.withAppendedId(Account.CONTENT_URI, account.mId))
                    .withValue(AccountColumns.SECURITY_SYNC_KEY, null)
                    .withValue(AccountColumns.POLICY_KEY, 0)
                    .build());
        }

        // Delete the previous policy associated with this account, if any
        if (account.mPolicyKey > 0) {
            ops.add(ContentProviderOperation.newDelete(
                    ContentUris.withAppendedId(
                            Policy.CONTENT_URI, account.mPolicyKey)).build());
        }

        try {
            context.getContentResolver().applyBatch(EmailContent.AUTHORITY, ops);
            account.refresh(context);
            syncAccount(context, account);
        } catch (RemoteException e) {
           // This is fatal to a remote process
            throw new IllegalStateException("Exception setting account policy.");
        } catch (OperationApplicationException e) {
            // Can't happen; our provider doesn't throw this exception
        }
    }

    private static void syncAccount(final Context context, final Account account) {
        final EmailServiceUtils.EmailServiceInfo info =
                EmailServiceUtils.getServiceInfo(context, account.getProtocol(context));
        final android.accounts.Account amAccount =
                new android.accounts.Account(account.mEmailAddress, info.accountType);
        final Bundle extras = new Bundle(3);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, true);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        ContentResolver.requestSync(amAccount, EmailContent.AUTHORITY, extras);
        LogUtils.i(TAG, "requestSync SecurityPolicy syncAccount %s, %s", account.toString(),
                extras.toString());
    }

    public void syncAccount(final Account account) {
        syncAccount(mContext, account);
    }

    public void setAccountPolicy(long accountId, Policy policy, String securityKey,
            boolean notify) {
        Account account = Account.restoreAccountWithId(mContext, accountId);
        // In case the account has been deleted, just return
        if (account == null) {
            return;
        }
        Policy oldPolicy = null;
        if (account.mPolicyKey > 0) {
            oldPolicy = Policy.restorePolicyWithId(mContext, account.mPolicyKey);
        }

        // If attachment policies have changed, fix up any affected attachment records
        if (oldPolicy != null && securityKey != null) {
            if ((oldPolicy.mDontAllowAttachments != policy.mDontAllowAttachments) ||
                    (oldPolicy.mMaxAttachmentSize != policy.mMaxAttachmentSize)) {
                Policy.setAttachmentFlagsForNewPolicy(mContext, account, policy);
            }
        }

        boolean policyChanged = (oldPolicy == null) || !oldPolicy.equals(policy);
        if (!policyChanged && (TextUtilities.stringOrNullEquals(securityKey,
                account.mSecuritySyncKey))) {
            LogUtils.d(Logging.LOG_TAG, "setAccountPolicy; policy unchanged");
        } else {
            setAccountPolicy(mContext, account, policy, securityKey);
            policiesUpdated();
        }

        boolean setHold = false;
        final NotificationController nc =
                NotificationControllerCreatorHolder.getInstance(mContext);
        if (policy.mProtocolPoliciesUnsupported != null) {
            // We can't support this, reasons in unsupportedRemotePolicies
            LogUtils.d(Logging.LOG_TAG,
                    "Notify policies for " + account.mDisplayName + " not supported.");
            setHold = true;
            if (notify) {
                nc.showSecurityUnsupportedNotification(account);
            }
            // Erase data
            Uri uri = EmailProvider.uiUri("uiaccountdata", accountId);
            mContext.getContentResolver().delete(uri, null, null);
        } else if (isActive(policy)) {
            if (policyChanged) {
                LogUtils.d(Logging.LOG_TAG, "Notify policies for " + account.mDisplayName
                        + " changed.");
                if (notify) {
                    // Notify that policies changed
                    nc.showSecurityChangedNotification(account);
                }
            } else {
                LogUtils.d(Logging.LOG_TAG, "Policy is active and unchanged; do not notify.");
            }
        } else {
            setHold = true;
            LogUtils.d(Logging.LOG_TAG, "Notify policies for " + account.mDisplayName +
                    " are not being enforced.");
            if (notify) {
                // Put up a notification
                nc.showSecurityNeededNotification(account);
            }
        }
        // Set/clear the account hold.
        setAccountHoldFlag(mContext, account, setHold);
    }

    /**
     * Called from the notification's intent receiver to register that the notification can be
     * cleared now.
     */
    public void clearNotification() {
        final NotificationController nc =
                NotificationControllerCreatorHolder.getInstance(mContext);

        nc.cancelSecurityNeededNotification();
    }

    /**
     * API: Remote wipe (from server).  This is final, there is no confirmation.  It will only
     * return to the caller if there is an unexpected failure.  The wipe includes external storage.
     */
    public void remoteWipe() {
    }
    /**
     * If we are not the active device admin, try to become so.
     *
     * Also checks for any policies that we have added during the lifetime of this app.
     * This catches the case where the user granted an earlier (smaller) set of policies
     * but an app upgrade requires that new policies be granted.
     *
     * @return true if we are already active, false if we are not
     */
    public boolean isActiveAdmin() {
        return true;
    }

    /**
     * Report admin component name - for making calls into device policy manager
     */
    public ComponentName getAdminComponent() {
        return mAdminName;
    }

    /**
     * Delete all accounts whose security flags aren't zero (i.e. they have security enabled).
     * This method is synchronous, so it should normally be called within a worker thread (the
     * exception being for unit tests)
     *
     * @param context the caller's context
     */
    /*package*/ void deleteSecuredAccounts(Context context) {
        ContentResolver cr = context.getContentResolver();
        // Find all accounts with security and delete them
        Cursor c = cr.query(Account.CONTENT_URI, EmailContent.ID_PROJECTION,
                Account.SECURITY_NONZERO_SELECTION, null, null);
        try {
            LogUtils.w(TAG, "Email administration disabled; deleting " + c.getCount() +
                    " secured account(s)");
            while (c.moveToNext()) {
                long accountId = c.getLong(EmailContent.ID_PROJECTION_COLUMN);
                Uri uri = EmailProvider.uiUri("uiaccount", accountId);
                cr.delete(uri, null, null);
            }
        } finally {
            c.close();
        }
        policiesUpdated();
        AccountReconciler.reconcileAccounts(context);
    }

    /**
     * Internal handler for enabled->disabled transitions.  Deletes all secured accounts.
     * Must call from worker thread, not on UI thread.
     */
    /*package*/ void onAdminEnabled(boolean isEnabled) {
        if (!isEnabled) {
            deleteSecuredAccounts(mContext);
        }
    }

    /**
     * Handle password expiration - if any accounts appear to have triggered this, put up
     * warnings, or even shut them down.
     *
     * NOTE:  If there are multiple accounts with password expiration policies, the device
     * password will be set to expire in the shortest required interval (most secure).  The logic
     * in this method operates based on the aggregate setting - irrespective of which account caused
     * the expiration.  In other words, all accounts (that require expiration) will run/stop
     * based on the requirements of the account with the shortest interval.
     */
    private void onPasswordExpiring(Context context) {
        // 1.  Do we have any accounts that matter here?
        long nextExpiringAccountId = findShortestExpiration(context);

        // 2.  If not, exit immediately
        if (nextExpiringAccountId == -1) {
            return;
        }

        // 3.  If yes, are we warning or expired?
        long expirationDate = getDPM().getPasswordExpiration(mAdminName);
        long timeUntilExpiration = expirationDate - System.currentTimeMillis();
        boolean expired = timeUntilExpiration < 0;
        final NotificationController nc =
                NotificationControllerCreatorHolder.getInstance(context);
        if (!expired) {
            // 4.  If warning, simply put up a generic notification and report that it came from
            // the shortest-expiring account.
            nc.showPasswordExpiringNotificationSynchronous(nextExpiringAccountId);
        } else {
            // 5.  Actually expired - find all accounts that expire passwords, and wipe them
            boolean wiped = wipeExpiredAccounts(context);
            if (wiped) {
                nc.showPasswordExpiredNotificationSynchronous(nextExpiringAccountId);
            }
        }
    }

    /**
     * Find the account with the shortest expiration time.  This is always assumed to be
     * the account that forces the password to be refreshed.
     * @return -1 if no expirations, or accountId if one is found
     */
    @VisibleForTesting
    /*package*/ static long findShortestExpiration(Context context) {
        long policyId = Utility.getFirstRowLong(context, Policy.CONTENT_URI, Policy.ID_PROJECTION,
                HAS_PASSWORD_EXPIRATION, null, PolicyColumns.PASSWORD_EXPIRATION_DAYS + " ASC",
                EmailContent.ID_PROJECTION_COLUMN, -1L);
        if (policyId < 0) return -1L;
        return Policy.getAccountIdWithPolicyKey(context, policyId);
    }

    /**
     * For all accounts that require password expiration, put them in security hold and wipe
     * their data.
     * @param context context
     * @return true if one or more accounts were wiped
     */
    @VisibleForTesting
    /*package*/ static boolean wipeExpiredAccounts(Context context) {
        boolean result = false;
        Cursor c = context.getContentResolver().query(Policy.CONTENT_URI,
                Policy.ID_PROJECTION, HAS_PASSWORD_EXPIRATION, null, null);
        if (c == null) {
            return false;
        }
        try {
            while (c.moveToNext()) {
                long policyId = c.getLong(Policy.ID_PROJECTION_COLUMN);
                long accountId = Policy.getAccountIdWithPolicyKey(context, policyId);
                if (accountId < 0) continue;
                Account account = Account.restoreAccountWithId(context, accountId);
                if (account != null) {
                    // Mark the account as "on hold".
                    setAccountHoldFlag(context, account, true);
                    // Erase data
                    Uri uri = EmailProvider.uiUri("uiaccountdata", accountId);
                    context.getContentResolver().delete(uri, null, null);
                    // Report one or more were found
                    result = true;
                }
            }
        } finally {
            c.close();
        }
        return result;
    }

    /**
     * Callback from EmailBroadcastProcessorService.  This provides the workers for the
     * DeviceAdminReceiver calls.  These should perform the work directly and not use async
     * threads for completion.
     */
    public static void onDeviceAdminReceiverMessage(Context context, int message) {
        SecurityPolicy instance = SecurityPolicy.getInstance(context);
        switch (message) {
            case DEVICE_ADMIN_MESSAGE_ENABLED:
                instance.onAdminEnabled(true);
                break;
            case DEVICE_ADMIN_MESSAGE_DISABLED:
                instance.onAdminEnabled(false);
                break;
            case DEVICE_ADMIN_MESSAGE_PASSWORD_CHANGED:
                // TODO make a small helper for this
                // Clear security holds (if any)
                Account.clearSecurityHoldOnAllAccounts(context);
                // Cancel any active notifications (if any are posted)
                final NotificationController nc =
                        NotificationControllerCreatorHolder.getInstance(context);

                nc.cancelPasswordExpirationNotifications();
                break;
            case DEVICE_ADMIN_MESSAGE_PASSWORD_EXPIRING:
                instance.onPasswordExpiring(instance.mContext);
                break;
        }
    }

    /**
     * Device Policy administrator.  This is primarily a listener for device state changes.
     * Note:  This is instantiated by incoming messages.
     * Note:  This is actually a BroadcastReceiver and must remain within the guidelines required
     *        for proper behavior, including avoidance of ANRs.
     * Note:  We do not implement onPasswordFailed() because the default behavior of the
     *        DevicePolicyManager - complete local wipe after 'n' failures - is sufficient.
     */
    public static class PolicyAdmin extends DeviceAdminReceiver {

        /**
         * Called after the administrator is first enabled.
         */
        @Override
        public void onEnabled(Context context, Intent intent) {
            EmailBroadcastProcessorService.processDevicePolicyMessage(context,
                    DEVICE_ADMIN_MESSAGE_ENABLED);
        }

        /**
         * Called prior to the administrator being disabled.
         */
        @Override
        public void onDisabled(Context context, Intent intent) {
            EmailBroadcastProcessorService.processDevicePolicyMessage(context,
                    DEVICE_ADMIN_MESSAGE_DISABLED);
        }

        /**
         * Called when the user asks to disable administration; we return a warning string that
         * will be presented to the user
         */
        @Override
        public CharSequence onDisableRequested(Context context, Intent intent) {
            return context.getString(R.string.disable_admin_warning);
        }

        /**
         * Called after the user has changed their password.
         */
        @Override
        public void onPasswordChanged(Context context, Intent intent) {
            EmailBroadcastProcessorService.processDevicePolicyMessage(context,
                    DEVICE_ADMIN_MESSAGE_PASSWORD_CHANGED);
        }

        /**
         * Called when device password is expiring
         */
        @Override
        public void onPasswordExpiring(Context context, Intent intent) {
            EmailBroadcastProcessorService.processDevicePolicyMessage(context,
                    DEVICE_ADMIN_MESSAGE_PASSWORD_EXPIRING);
        }
    }
}
