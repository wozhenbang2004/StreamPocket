package dev.streampocket;

import org.junit.Test;

public final class SubtitleTracksTest {
    @Test public void metadataIsTypedAndLanguageIsSpecific() { SubtitleTracksSelfTest.metadata(); }
    @Test public void onlyExplicitLocalUrisCanBeLoaded() { SubtitleTracksSelfTest.uris(); }
    @Test public void malformedAndWrongFormatHeadersAreRejected() { SubtitleTracksSelfTest.headers(); }
    @Test public void optionalFailureMustBelongToTheSubtitle() { SubtitleTracksSelfTest.failures(); }
}
