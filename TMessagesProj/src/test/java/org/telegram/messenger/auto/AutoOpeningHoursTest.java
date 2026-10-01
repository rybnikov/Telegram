package org.telegram.messenger.auto;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AutoOpeningHoursTest {
    @Test public void commonRulesAreSpoken() {
        assertEquals("Monday to Friday, 9:00 to 18:00; Saturday, 10:00 to 14:00; Sunday, closed",
                AutoOpeningHours.speak("Mo-Fr 09:00-18:00; Sa 10:00-14:00; Su off"));
        assertEquals("Monday, Wednesday to Friday, 8:00 to 12:00 and 13:00 to 17:30",
                AutoOpeningHours.speak("Mo,We-Fr 08:00-12:00,13:00-17:30"));
        assertEquals("9:00 to 21:00", AutoOpeningHours.speak("09:00-21:00"));
        assertEquals("open 24 hours, every day", AutoOpeningHours.speak("24/7"));
    }

    @Test public void unsupportedSyntaxIsSkippedNotRead() {
        assertNull(AutoOpeningHours.speak(null));
        assertNull(AutoOpeningHours.speak(""));
        assertNull(AutoOpeningHours.speak("Mo-Fr 09:00-18:00; PH off"));
        assertNull(AutoOpeningHours.speak("sunrise-sunset"));
        assertNull(AutoOpeningHours.speak("Mo-Fr 09:00-18:00 \"by appointment\""));
        assertNull(AutoOpeningHours.speak("Jan-Mar Mo 10:00-12:00"));
        assertNull(AutoOpeningHours.speak("Mo-Xx 10:00-12:00"));
        assertNull(AutoOpeningHours.speak("Mo-Fr 09:00+"));
    }
}
