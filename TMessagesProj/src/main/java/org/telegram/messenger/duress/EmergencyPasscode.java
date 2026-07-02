/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger.duress;

import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

// FOLDOGRAM-DURESS: Fork-owned emergency passcode state machine and persistence.
public final class EmergencyPasscode {

    // FOLDOGRAM-DURESS: Unlock decision constants are kept out of SharedConfig.
    public static final int PASSCODE_RESULT_NONE = 0;
    public static final int PASSCODE_RESULT_CURRENT = 1;
    public static final int PASSCODE_RESULT_OWNER = 2;
    public static final int PASSCODE_RESULT_EMERGENCY = 3;

    // FOLDOGRAM-DURESS: Persisted emergency credential.
    public static String emergencyPasscodeHash = "";
    public static byte[] emergencyPasscodeSalt = new byte[0];

    // FOLDOGRAM-DURESS: Persisted owner credential snapshot.
    public static String ownerPasscodeHash = "";
    public static byte[] ownerPasscodeSalt = new byte[0];
    public static int ownerPasscodeType;

    // FOLDOGRAM-DURESS: Persisted per-account hidden chat set and sticky mode flag.
    public static volatile int emergencyAccountId = -1;
    public static volatile HashSet<Long> emergencyHiddenChats = new HashSet<>();
    public static volatile boolean emergencyModeActive;

    private EmergencyPasscode() {
    }

    // FOLDOGRAM-DURESS: Emergency credential presence check.
    public static boolean hasEmergency() {
        return emergencyPasscodeHash != null && emergencyPasscodeHash.length() > 0;
    }

    // FOLDOGRAM-DURESS: Owner credential presence check.
    public static boolean hasOwner() {
        return ownerPasscodeHash != null && ownerPasscodeHash.length() > 0;
    }

    // FOLDOGRAM-DURESS: Duplicate SharedConfig's salted passcode hash without touching its hot path.
    public static String computeHash(byte[] salt, String passcode) {
        try {
            byte[] safeSalt = salt != null ? salt : new byte[0];
            byte[] passcodeBytes = passcode.getBytes("UTF-8");
            byte[] bytes = new byte[safeSalt.length * 2 + passcodeBytes.length];
            System.arraycopy(safeSalt, 0, bytes, 0, safeSalt.length);
            System.arraycopy(passcodeBytes, 0, bytes, safeSalt.length, passcodeBytes.length);
            System.arraycopy(safeSalt, 0, bytes, safeSalt.length + passcodeBytes.length, safeSalt.length);
            return Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
        } catch (Exception ignore) {
            return "";
        }
    }

    // FOLDOGRAM-DURESS: Emergency mode is account-scoped.
    public static boolean isActive(int account) {
        return emergencyModeActive && account == emergencyAccountId;
    }

    // FOLDOGRAM-DURESS: Pure hidden-chat predicate used by all UI and notification hooks.
    public static boolean isHidden(int account, long did) {
        return isActive(account) && emergencyHiddenChats.contains(did);
    }

    // FOLDOGRAM-DURESS: Persisted state loader delegated from SharedConfig.
    public static void load(SharedPreferences prefs) {
        emergencyPasscodeHash = prefs.getString("emergencyPasscodeHash1", "");
        emergencyPasscodeSalt = decodeSalt(prefs.getString("emergencyPasscodeSalt", ""));
        ownerPasscodeHash = prefs.getString("ownerPasscodeHash1", "");
        ownerPasscodeSalt = decodeSalt(prefs.getString("ownerPasscodeSalt", ""));
        ownerPasscodeType = prefs.getInt("ownerPasscodeType", SharedConfig.PASSCODE_TYPE_PIN);
        emergencyAccountId = prefs.getInt("emergencyAccountId", -1);
        emergencyModeActive = prefs.getBoolean("emergencyModeActive", false);
        emergencyHiddenChats = parseHiddenChats(prefs.getString("emergencyHiddenChats", ""));
    }

    // FOLDOGRAM-DURESS: Persisted state saver delegated from SharedConfig.
    public static void save(SharedPreferences.Editor editor) {
        editor.putString("emergencyPasscodeHash1", emergencyPasscodeHash);
        editor.putString("emergencyPasscodeSalt", emergencyPasscodeSalt.length > 0 ? Base64.encodeToString(emergencyPasscodeSalt, Base64.DEFAULT) : "");
        editor.putString("ownerPasscodeHash1", ownerPasscodeHash);
        editor.putString("ownerPasscodeSalt", ownerPasscodeSalt.length > 0 ? Base64.encodeToString(ownerPasscodeSalt, Base64.DEFAULT) : "");
        editor.putInt("ownerPasscodeType", ownerPasscodeType);
        editor.putInt("emergencyAccountId", emergencyAccountId);
        editor.putString("emergencyHiddenChats", TextUtils.join(",", emergencyHiddenChats));
        editor.putBoolean("emergencyModeActive", emergencyModeActive);
    }

    // FOLDOGRAM-DURESS: Reset emergency state during full config clears.
    public static void clear() {
        emergencyPasscodeHash = "";
        emergencyPasscodeSalt = new byte[0];
        ownerPasscodeHash = "";
        ownerPasscodeSalt = new byte[0];
        ownerPasscodeType = SharedConfig.PASSCODE_TYPE_PIN;
        emergencyAccountId = -1;
        emergencyHiddenChats = new HashSet<>();
        emergencyModeActive = false;
    }

    // FOLDOGRAM-DURESS: Pure three-way passcode decision table.
    public static int checkType(String passcode) {
        if (hasEmergency() && computeHash(emergencyPasscodeSalt, passcode).equals(emergencyPasscodeHash)) {
            return PASSCODE_RESULT_EMERGENCY;
        }
        if (hasOwner() && computeHash(ownerPasscodeSalt, passcode).equals(ownerPasscodeHash)) {
            return PASSCODE_RESULT_OWNER;
        }
        if (SharedConfig.checkPasscode(passcode)) {
            return PASSCODE_RESULT_CURRENT;
        }
        return PASSCODE_RESULT_NONE;
    }

    // FOLDOGRAM-DURESS: Apply sticky mode changes after a successful passcode decision.
    public static void applyUnlock(int result) {
        if (result == PASSCODE_RESULT_EMERGENCY) {
            emergencyModeActive = true;
        } else if (result == PASSCODE_RESULT_OWNER) {
            emergencyModeActive = false;
            if (hasOwner() && (!ownerPasscodeHash.equals(SharedConfig.passcodeHash) || !Arrays.equals(ownerPasscodeSalt, SharedConfig.passcodeSalt) || ownerPasscodeType != SharedConfig.passcodeType)) {
                SharedConfig.passcodeHash = ownerPasscodeHash;
                SharedConfig.passcodeSalt = ownerPasscodeSalt;
                SharedConfig.passcodeType = ownerPasscodeType;
            }
        }
    }

    // FOLDOGRAM-DURESS: Single lock-screen acceptance side-effect hook.
    public static void onPasscodeAccepted(int account, int result) {
        applyUnlock(result);
        SharedConfig.saveConfig();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        // FOLDOGRAM-DURESS: Remove any already-visible notifications for hidden chats.
        clearHiddenNotifications(account);
        NotificationsController.getInstance(account).updateBadge();
        MediaDataController.getInstance(account).buildShortcuts();
    }

    // FOLDOGRAM-DURESS: Normal-mode passcode disable wipes the whole emergency feature.
    public static void wipeAll() {
        clear();
        SharedConfig.saveConfig();
    }

    // FOLDOGRAM-DURESS: Owner snapshot follows normal-mode passcode changes only.
    public static void snapshotOwnerIfNeeded(int account) {
        if (hasEmergency() && !emergencyModeActive) {
            ownerPasscodeHash = SharedConfig.passcodeHash;
            ownerPasscodeSalt = SharedConfig.passcodeSalt;
            ownerPasscodeType = SharedConfig.passcodeType;
            emergencyAccountId = account;
            SharedConfig.saveConfig();
        }
    }

    // FOLDOGRAM-DURESS: Set or change the emergency credential.
    public static boolean setEmergencyCode(int account, String passcode, int type) {
        int result = checkType(passcode);
        if (result == PASSCODE_RESULT_CURRENT || result == PASSCODE_RESULT_OWNER) {
            return false;
        }
        byte[] salt = new byte[16];
        Utilities.random.nextBytes(salt);
        emergencyPasscodeSalt = salt;
        emergencyPasscodeHash = computeHash(salt, passcode);
        emergencyAccountId = account;
        if (!emergencyModeActive) {
            ownerPasscodeHash = SharedConfig.passcodeHash;
            ownerPasscodeSalt = SharedConfig.passcodeSalt;
            ownerPasscodeType = SharedConfig.passcodeType;
        }
        SharedConfig.saveConfig();
        return true;
    }

    // FOLDOGRAM-DURESS: Replace the hidden set atomically for non-UI readers.
    public static void setHiddenChats(int account, Collection<Long> ids) {
        emergencyHiddenChats = new HashSet<>(ids);
        emergencyAccountId = account;
        SharedConfig.saveConfig();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        // FOLDOGRAM-DURESS: Remove any already-visible notifications for newly hidden chats.
        clearHiddenNotifications(account);
        NotificationsController.getInstance(account).updateBadge();
        MediaDataController.getInstance(account).buildShortcuts();
    }

    // FOLDOGRAM-DURESS: Adjust in-app tab counters without modifying upstream filter state.
    public static int adjustTabCounter(int account, ArrayList<TLRPC.Dialog> tabDialogs, int count) {
        if (!isActive(account) || tabDialogs == null || tabDialogs.isEmpty()) {
            return count;
        }
        int hiddenUnread = 0;
        MessagesController messagesController = MessagesController.getInstance(account);
        for (int i = 0, size = tabDialogs.size(); i < size; i++) {
            TLRPC.Dialog dialog = tabDialogs.get(i);
            if (dialog != null && isHidden(account, dialog.id)) {
                hiddenUnread += messagesController.getDialogUnreadCount(dialog);
            }
        }
        return Math.max(0, count - hiddenUnread);
    }

    // FOLDOGRAM-DURESS: Decode a persisted passcode salt.
    private static byte[] decodeSalt(String value) {
        if (TextUtils.isEmpty(value)) {
            return new byte[0];
        }
        try {
            return Base64.decode(value, Base64.DEFAULT);
        } catch (Exception ignore) {
            return new byte[0];
        }
    }

    // FOLDOGRAM-DURESS: Parse the persisted hidden chat id snapshot.
    private static HashSet<Long> parseHiddenChats(String value) {
        HashSet<Long> result = new HashSet<>();
        if (TextUtils.isEmpty(value)) {
            return result;
        }
        String[] parts = value.split(",");
        for (int i = 0; i < parts.length; i++) {
            try {
                result.add(Long.parseLong(parts[i]));
            } catch (Exception ignore) {
            }
        }
        return result;
    }

    // FOLDOGRAM-DURESS: Clear notification UI for hidden chats without deleting stored messages.
    private static void clearHiddenNotifications(int account) {
        if (!isActive(account) || emergencyHiddenChats.isEmpty()) {
            return;
        }
        HashSet<Long> hiddenChats = new HashSet<>(emergencyHiddenChats);
        for (Long did : hiddenChats) {
            if (did != null) {
                NotificationsController.getInstance(account).removeNotificationsForDialog(did);
            }
        }
    }
}
