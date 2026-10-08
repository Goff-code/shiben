package cn.focusledger.core;

import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public final class CompletionAlertModeTest {
    @Test public void everyReleasedPreferencePairKeepsItsMeaning() {
        assertEquals(CompletionAlertMode.SILENT, CompletionAlertMode.from(false, false));
        assertEquals(CompletionAlertMode.SOUND, CompletionAlertMode.from(true, false));
        assertEquals(CompletionAlertMode.VIBRATION, CompletionAlertMode.from(false, true));
        assertEquals(CompletionAlertMode.SOUND_AND_VIBRATION, CompletionAlertMode.from(true, true));
        for (CompletionAlertMode mode : CompletionAlertMode.values()) {
            assertSame(mode, CompletionAlertMode.from(mode.sound, mode.vibration));
        }
    }

    @Test public void channelIdsRemainStableAcrossUpdates() {
        assertEquals("shiben_done_quiet_still", CompletionAlertMode.SILENT.channelId());
        assertEquals("shiben_done_sound_still", CompletionAlertMode.SOUND.channelId());
        assertEquals("shiben_done_quiet_vibrate", CompletionAlertMode.VIBRATION.channelId());
        assertEquals("shiben_done_sound_vibrate", CompletionAlertMode.SOUND_AND_VIBRATION.channelId());
        Set<String> ids = new HashSet<>();
        for (CompletionAlertMode mode : CompletionAlertMode.values()) assertTrue(ids.add(mode.channelId()));
    }
}
