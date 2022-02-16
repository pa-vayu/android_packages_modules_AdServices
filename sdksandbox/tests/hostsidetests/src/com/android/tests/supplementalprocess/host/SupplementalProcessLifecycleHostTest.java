/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.tests.supplementalprocess.host;


import static com.google.common.truth.Truth.assertThat;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public final class SupplementalProcessLifecycleHostTest extends BaseHostJUnit4Test {

    private static final String APP_PACKAGE = "com.android.supplementalprocess.app";
    private static final String APP_2_PACKAGE = "com.android.supplementalprocess.app2";

    private static final String APP_ACTIVITY = "SupplementalProcessTestActivity";
    private static final String APP_2_ACTIVITY = "SupplementalProcessTestActivity2";

    private static final String CODE_APK = "TestCodeProvider.apk";
    private static final String CODE_APK_2 = "TestCodeProvider2.apk";

    private void clearProcess(String pkg) throws Exception {
        getDevice().executeShellCommand(String.format("pm clear %s", pkg));
    }

    private void startActivity(String pkg, String activity) throws Exception {
        getDevice().executeShellCommand(String.format("am start -W -n %s/.%s", pkg, activity));
    }

    private void killApp(String pkg) throws Exception {
        getDevice().executeShellCommand(String.format("am force-stop %s", pkg));
    }

    private String getUidForPackage(String pkg) throws Exception {
        String pid = getDevice().getProcessPid(pkg);
        if (pid == null) {
            throw new Exception(String.format("Could not find PID for %s", pkg));
        }
        String result = getDevice().executeAdbCommand("shell", "ps", "-p", pid, "-o", "uid");
        String[] sections = result.split("\n");
        return sections[sections.length - 1];
    }

    // TODO(b/216302023): Update supplemental process name format
    private String getSupplementalProcessNameForPackage(String pkg) throws Exception {
        String appUid = getUidForPackage(pkg);
        return String.format("supplemental_process_%s", appUid);
    }

    @Before
    public void setUp() throws Exception {
        assertThat(getBuild()).isNotNull();
        assertThat(getDevice()).isNotNull();

        // Ensure neither app is currently running
        for (String pkg : new String[]{APP_PACKAGE, APP_2_PACKAGE}) {
            clearProcess(pkg);
        }

        // Workaround for autoTeardown which removes packages installed in test
        for (String apk : new String[]{CODE_APK, CODE_APK_2}) {
            if (!isPackageInstalled(apk)) {
                installPackage(apk, "-d");
            }
        }
    }

    @Test
    public void testSupplementalProcessIsDestroyedOnAppDestroy() throws Exception {
        startActivity(APP_PACKAGE, APP_ACTIVITY);
        String processDump = getDevice().executeAdbCommand("shell", "ps", "-A");
        assertThat(processDump).contains(APP_PACKAGE);
        String supplementalProcess = getSupplementalProcessNameForPackage(APP_PACKAGE);
        assertThat(processDump).contains(supplementalProcess);

        killApp(APP_PACKAGE);
        processDump = getDevice().executeAdbCommand("shell", "ps", "-A");
        assertThat(processDump).doesNotContain(APP_PACKAGE);
        assertThat(processDump).doesNotContain(supplementalProcess);
    }

    @Test
    public void testSupplementalProcessIsCreatedPerApp() throws Exception {
        startActivity(APP_PACKAGE, APP_ACTIVITY);
        String processDump = getDevice().executeAdbCommand("shell", "ps", "-A");
        assertThat(processDump).contains(APP_PACKAGE);
        String supplementalProcess1 = getSupplementalProcessNameForPackage(APP_PACKAGE);
        assertThat(processDump).contains(supplementalProcess1);

        startActivity(APP_2_PACKAGE, APP_2_ACTIVITY);
        processDump = getDevice().executeAdbCommand("shell", "ps", "-A");
        assertThat(processDump).contains(APP_2_PACKAGE);
        String supplementalProcess2 = getSupplementalProcessNameForPackage(APP_2_PACKAGE);
        assertThat(processDump).contains(supplementalProcess2);
        assertThat(processDump).contains(APP_PACKAGE);
        assertThat(processDump).contains(supplementalProcess1);

        killApp(APP_2_PACKAGE);
        processDump = getDevice().executeAdbCommand("shell", "ps", "-A");
        assertThat(processDump).doesNotContain(APP_2_PACKAGE);
        assertThat(processDump).doesNotContain(supplementalProcess2);
        assertThat(processDump).contains(APP_PACKAGE);
        assertThat(processDump).contains(supplementalProcess1);
    }

    @Test
    public void testAppAndSupplementalProcessAreKilledOnLoadedCodeUpdate() throws Exception {
        startActivity(APP_PACKAGE, APP_ACTIVITY);

        // Should see app/supplemental process running
        String processDump = getDevice().executeAdbCommand("shell", "ps", "-A");
        assertThat(processDump).contains(APP_PACKAGE);

        String supplementalProcess = getSupplementalProcessNameForPackage(APP_PACKAGE);
        assertThat(processDump).contains(supplementalProcess);

        // Update package loaded by app
        installPackage(CODE_APK, "-d");

        // Should no longer see app/supplemental process running
        processDump = getDevice().executeAdbCommand("shell", "ps", "-A");
        assertThat(processDump).doesNotContain(APP_PACKAGE);
        assertThat(processDump).doesNotContain(supplementalProcess);
    }

    @Test
    public void testAppAndSupplementalProcessAreNotKilledForNonLoadedCodeUpdate() throws Exception {
        startActivity(APP_PACKAGE, APP_ACTIVITY);

        // Should see app/supplemental process running
        String processDump = getDevice().executeAdbCommand("shell", "ps", "-A");
        assertThat(processDump).contains(APP_PACKAGE);

        String supplementalProcess = getSupplementalProcessNameForPackage(APP_PACKAGE);
        assertThat(processDump).contains(supplementalProcess);

        // Simulate update of package not loaded by app
        installPackage(CODE_APK_2, "-d");

        // Should still see app/supplemental process running
        processDump = getDevice().executeAdbCommand("shell", "ps", "-A");
        assertThat(processDump).contains(APP_PACKAGE);
        assertThat(processDump).contains(supplementalProcess);
    }

    @Test
    public void testOnlyRelevantAppIsKilledForLoadedCodeUpdate() throws Exception {
        startActivity(APP_PACKAGE, APP_ACTIVITY);
        startActivity(APP_2_PACKAGE, APP_2_ACTIVITY);

        // See processes for both apps
        String processDump = getDevice().executeAdbCommand("shell", "ps", "-A");
        assertThat(processDump).contains(APP_PACKAGE);

        String supplementalProcess = getSupplementalProcessNameForPackage(APP_PACKAGE);
        assertThat(processDump).contains(supplementalProcess);

        assertThat(processDump).contains(APP_2_PACKAGE);

        String supplementalProcess2 = getSupplementalProcessNameForPackage(APP_PACKAGE);
        assertThat(processDump).contains(supplementalProcess2);

        installPackage(CODE_APK_2, "-d");

        processDump = getDevice().executeAdbCommand("shell", "ps", "-A");
        assertThat(processDump).contains(APP_PACKAGE);
        assertThat(processDump).doesNotContain(APP_2_PACKAGE);

        // TODO(b/215012578) check that supplemental process for app 1 is still running
    }
}
