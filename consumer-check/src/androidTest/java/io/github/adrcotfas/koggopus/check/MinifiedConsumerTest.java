package io.github.adrcotfas.koggopus.check;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Written in Java and against framework API only: the test shares the app's shrunk Kotlin
 * runtime, and must not depend on anything R8 may have removed or renamed.
 */
@RunWith(AndroidJUnit4.class)
public class MinifiedConsumerTest {

    @Test
    public void libraryWorksInAMinifiedApp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent().setClassName(context, "io.github.adrcotfas.koggopus.check.CheckActivity");
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> assertEquals("passed", activity.getTitle().toString()));
        }
    }
}
