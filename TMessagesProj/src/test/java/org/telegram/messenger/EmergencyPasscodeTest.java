package org.telegram.messenger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.duress.EmergencyPasscode;

import java.util.Arrays;
import java.util.HashSet;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, manifest = Config.NONE, sdk = 28)
public final class EmergencyPasscodeTest {

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        EmergencyPasscode.clear();
        SharedConfig.passcodeHash = "";
        SharedConfig.passcodeSalt = new byte[0];
        SharedConfig.passcodeType = SharedConfig.PASSCODE_TYPE_PIN;
    }

    @Test
    public void computeHashIsDeterministicAndSaltSensitive() {
        byte[] salt = salt(1);
        assertEquals(EmergencyPasscode.computeHash(salt, "1234"), EmergencyPasscode.computeHash(salt, "1234"));
        assertNotEquals(EmergencyPasscode.computeHash(salt, "1234"), EmergencyPasscode.computeHash(salt(2), "1234"));
    }

    @Test
    public void checkTypeFollowsEmergencyOwnerCurrentNoneOrder() {
        setCurrent("current", salt(1), SharedConfig.PASSCODE_TYPE_PIN);
        setOwner("owner", salt(2), SharedConfig.PASSCODE_TYPE_PIN);
        setEmergency("duress", salt(3));

        assertEquals(EmergencyPasscode.PASSCODE_RESULT_EMERGENCY, EmergencyPasscode.checkType("duress"));
        assertEquals(EmergencyPasscode.PASSCODE_RESULT_OWNER, EmergencyPasscode.checkType("owner"));
        assertEquals(EmergencyPasscode.PASSCODE_RESULT_CURRENT, EmergencyPasscode.checkType("current"));
        assertEquals(EmergencyPasscode.PASSCODE_RESULT_NONE, EmergencyPasscode.checkType("wrong"));
    }

    @Test
    public void checkTypePrefersEmergencyThenOwnerBeforeCurrentForSameValue() {
        setCurrent("same", salt(1), SharedConfig.PASSCODE_TYPE_PASSWORD);
        setOwner("same", salt(2), SharedConfig.PASSCODE_TYPE_PASSWORD);

        assertEquals(EmergencyPasscode.PASSCODE_RESULT_OWNER, EmergencyPasscode.checkType("same"));

        setEmergency("same", salt(3));

        assertEquals(EmergencyPasscode.PASSCODE_RESULT_EMERGENCY, EmergencyPasscode.checkType("same"));
    }

    @Test
    public void checkTypeMatchesOwnerByValueWhenCurrentGateDiffers() {
        setCurrent("attacker", salt(1), SharedConfig.PASSCODE_TYPE_PIN);
        setOwner("owner", salt(2), SharedConfig.PASSCODE_TYPE_PIN);

        assertEquals(EmergencyPasscode.PASSCODE_RESULT_OWNER, EmergencyPasscode.checkType("owner"));
        assertEquals(EmergencyPasscode.PASSCODE_RESULT_CURRENT, EmergencyPasscode.checkType("attacker"));
    }

    @Test
    public void applyUnlockKeepsCurrentStickyAndOwnerClearsAndHealsGate() {
        byte[] ownerSalt = salt(7);
        setCurrent("attacker", salt(1), SharedConfig.PASSCODE_TYPE_PIN);
        setOwner("owner", ownerSalt, SharedConfig.PASSCODE_TYPE_PASSWORD);

        EmergencyPasscode.emergencyModeActive = true;
        EmergencyPasscode.applyUnlock(EmergencyPasscode.PASSCODE_RESULT_CURRENT);
        assertTrue(EmergencyPasscode.emergencyModeActive);

        EmergencyPasscode.emergencyModeActive = false;
        EmergencyPasscode.applyUnlock(EmergencyPasscode.PASSCODE_RESULT_EMERGENCY);
        assertTrue(EmergencyPasscode.emergencyModeActive);

        EmergencyPasscode.applyUnlock(EmergencyPasscode.PASSCODE_RESULT_OWNER);
        assertFalse(EmergencyPasscode.emergencyModeActive);
        assertEquals(EmergencyPasscode.ownerPasscodeHash, SharedConfig.passcodeHash);
        assertArrayEquals(ownerSalt, SharedConfig.passcodeSalt);
        assertEquals(SharedConfig.PASSCODE_TYPE_PASSWORD, SharedConfig.passcodeType);
    }

    @Test
    public void setEmergencyCodeRejectsOwnerAndCurrentValues() {
        setCurrent("current", salt(1), SharedConfig.PASSCODE_TYPE_PIN);
        setOwner("owner", salt(2), SharedConfig.PASSCODE_TYPE_PIN);

        assertFalse(EmergencyPasscode.setEmergencyCode(0, "owner", SharedConfig.PASSCODE_TYPE_PIN));
        assertFalse(EmergencyPasscode.setEmergencyCode(0, "current", SharedConfig.PASSCODE_TYPE_PIN));
        assertTrue(EmergencyPasscode.setEmergencyCode(0, "duress", SharedConfig.PASSCODE_TYPE_PIN));
        assertEquals(0, EmergencyPasscode.emergencyAccountId);
        assertTrue(EmergencyPasscode.hasEmergency());
    }

    @Test
    public void setEmergencyCodeInEmergencyModeDoesNotReplaceOwnerCredential() {
        byte[] ownerSalt = salt(2);
        setCurrent("attacker", salt(1), SharedConfig.PASSCODE_TYPE_PIN);
        setOwner("owner", ownerSalt, SharedConfig.PASSCODE_TYPE_PASSWORD);
        String ownerHash = EmergencyPasscode.ownerPasscodeHash;
        EmergencyPasscode.emergencyModeActive = true;

        assertTrue(EmergencyPasscode.setEmergencyCode(0, "newduress", SharedConfig.PASSCODE_TYPE_PIN));
        assertEquals(ownerHash, EmergencyPasscode.ownerPasscodeHash);
        assertArrayEquals(ownerSalt, EmergencyPasscode.ownerPasscodeSalt);
        assertEquals(SharedConfig.PASSCODE_TYPE_PASSWORD, EmergencyPasscode.ownerPasscodeType);
    }

    @Test
    public void isHiddenRequiresModeAccountAndMembership() {
        EmergencyPasscode.emergencyAccountId = 1;
        EmergencyPasscode.emergencyHiddenChats = new HashSet<>(Arrays.asList(10L, -20L));

        EmergencyPasscode.emergencyModeActive = false;
        assertFalse(EmergencyPasscode.isHidden(1, 10L));

        EmergencyPasscode.emergencyModeActive = true;
        assertFalse(EmergencyPasscode.isHidden(0, 10L));
        assertFalse(EmergencyPasscode.isHidden(1, 30L));
        assertTrue(EmergencyPasscode.isHidden(1, 10L));
        assertTrue(EmergencyPasscode.isHidden(1, -20L));
    }

    private static void setCurrent(String passcode, byte[] salt, int type) {
        SharedConfig.passcodeSalt = salt;
        SharedConfig.passcodeHash = EmergencyPasscode.computeHash(salt, passcode);
        SharedConfig.passcodeType = type;
    }

    private static void setOwner(String passcode, byte[] salt, int type) {
        EmergencyPasscode.ownerPasscodeSalt = salt;
        EmergencyPasscode.ownerPasscodeHash = EmergencyPasscode.computeHash(salt, passcode);
        EmergencyPasscode.ownerPasscodeType = type;
    }

    private static void setEmergency(String passcode, byte[] salt) {
        EmergencyPasscode.emergencyPasscodeSalt = salt;
        EmergencyPasscode.emergencyPasscodeHash = EmergencyPasscode.computeHash(salt, passcode);
    }

    private static byte[] salt(int seed) {
        byte[] salt = new byte[16];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) (seed + i);
        }
        return salt;
    }
}
