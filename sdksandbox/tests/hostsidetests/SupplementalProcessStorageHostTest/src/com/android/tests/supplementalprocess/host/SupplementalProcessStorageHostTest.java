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

import static org.junit.Assert.fail;

import android.cts.install.lib.host.InstallUtilsHost;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public final class SupplementalProcessStorageHostTest extends BaseHostJUnit4Test {

    private int mOriginalUserId;
    private int mSecondaryUserId = -1;
    private boolean mWasRoot;

    private static final String CODE_PROVIDER_APK_NAME = "SupplementalProcessCodeProvider.apk";
    private static final String CODE_NAME = "com.android.supplementalprocesscode_v1";
    private static final String SYS_PROP_DEFAULT_CERT_DIGEST =
            "debug.pm.uses_sdk_library_default_cert_digest";
    private static final String TEST_APP_APK_NAME = "SupplementalProcessStorageTestApp.apk";
    private static final String TEST_APP_PACKAGE = "com.android.tests.supplementalprocess";

    private static final long SWITCH_USER_COMPLETED_NUMBER_OF_POLLS = 60;
    private static final long SWITCH_USER_COMPLETED_POLL_INTERVAL_IN_MILLIS = 1000;

    private final InstallUtilsHost mHostUtils = new InstallUtilsHost(this);

    @Before
    public void setUp() throws Exception {
        // TODO(b/209061624): See if we can remove root privilege when instrumentation support for
        // supplemental process is added.
        mWasRoot = getDevice().isAdbRoot();
        getDevice().enableAdbRoot();
        mOriginalUserId = getDevice().getCurrentUser();
        // TODO(b/211766362): remove supplemental data manually for now
        getDevice().deleteFile("/data/misc_ce/0/supplemental/" + TEST_APP_PACKAGE);
        getDevice().deleteFile("/data/misc_de/0/supplemental/" + TEST_APP_PACKAGE);
    }

    @After
    public void tearDown() throws Exception {
        removeSecondaryUserIfNecessary();
        uninstallPackage(TEST_APP_PACKAGE);
        // TODO(b/211766362): remove supplemental data manually for now
        getDevice().deleteFile("/data/misc_ce/0/supplemental/" + TEST_APP_PACKAGE);
        getDevice().deleteFile("/data/misc_de/0/supplemental/" + TEST_APP_PACKAGE);
        if (!mWasRoot) {
            getDevice().disableAdbRoot();
        }
    }

    /**
     * Verify that {@code /data/misc_{ce,de}/<user-id>/supplemental} is created when
     * {@code <user-id>} is created.
     */
    @Test
    public void testSupplementalDataRootDirectory_IsCreatedOnUserCreate() throws Exception {
        {
            // Verify root directory exists for primary user
            final String cePath = getSupplementalCeDataRootPath(0);
            final String dePath = getSupplementalDeDataRootPath(0);
            assertThat(getDevice().isDirectory(dePath)).isTrue();
            assertThat(getDevice().isDirectory(cePath)).isTrue();
        }

        {
            // Verify root directory is created for new user
            mSecondaryUserId = createAndStartSecondaryUser();
            final String cePath = getSupplementalCeDataRootPath(mSecondaryUserId);
            final String dePath = getSupplementalDeDataRootPath(mSecondaryUserId);
            assertThat(getDevice().isDirectory(dePath)).isTrue();
            assertThat(getDevice().isDirectory(cePath)).isTrue();
        }
    }

    @Test
    public void testSupplementalDataAppDirectory_IsCreatedOnInstall() throws Exception {
        // Directory should not exist before install
        final String cePath = getSupplementalCeDataAppPath(0, TEST_APP_PACKAGE);
        final String dePath = getSupplementalDeDataAppPath(0, TEST_APP_PACKAGE);
        assertThat(getDevice().isDirectory(cePath)).isFalse();
        assertThat(getDevice().isDirectory(dePath)).isFalse();

        // Install the app
        installPackage(TEST_APP_APK_NAME);

        // Verify directory is created
        assertThat(getDevice().isDirectory(cePath)).isTrue();
        assertThat(getDevice().isDirectory(dePath)).isTrue();
    }

    @Test
    public void testSupplementalDataAppDirectory_IsUserSpecific() throws Exception {
        // Install first before creating the user
        installPackage(TEST_APP_APK_NAME, "--user all");

        mSecondaryUserId = createAndStartSecondaryUser();

        // Data directories should not exist as the package is not installed on new user
        final String ceAppPath = getAppCeDataPath(mSecondaryUserId, TEST_APP_PACKAGE);
        final String deAppPath = getAppDeDataPath(mSecondaryUserId, TEST_APP_PACKAGE);
        final String cePath = getSupplementalCeDataAppPath(mSecondaryUserId, TEST_APP_PACKAGE);
        final String dePath = getSupplementalDeDataAppPath(mSecondaryUserId, TEST_APP_PACKAGE);

        assertThat(getDevice().isDirectory(ceAppPath)).isFalse();
        assertThat(getDevice().isDirectory(deAppPath)).isFalse();
        assertThat(getDevice().isDirectory(cePath)).isFalse();
        assertThat(getDevice().isDirectory(dePath)).isFalse();

        // Install the app on new user
        installPackage(TEST_APP_APK_NAME);

        assertThat(getDevice().isDirectory(cePath)).isTrue();
        assertThat(getDevice().isDirectory(dePath)).isTrue();
    }

    private String getAppCeDataPath(int userId, String packageName) {
        return String.format("/data/user/%d/%s", userId, packageName);
    }

    private String getAppDeDataPath(int userId, String packageName) {
        return String.format("/data/user_de/%d/%s", userId, packageName);
    }

    private String getSupplementalCeDataRootPath(int userId) {
        return String.format("/data/misc_ce/%d/supplemental", userId);
    }

    private String getSupplementalDeDataRootPath(int userId) {
        return String.format("/data/misc_de/%d/supplemental", userId);
    }

    private String getSupplementalCeDataAppPath(int userId, String packageName) {
        return String.format(
            "%s/%s", getSupplementalCeDataRootPath(userId), packageName);
    }

    private String getSupplementalDeDataAppPath(int userId, String packageName) {
        return String.format(
            "%s/%s", getSupplementalDeDataRootPath(userId), packageName);
    }

    private int createAndStartSecondaryUser() throws Exception {
        String name = "SupplementalProcessStorageHostTest_User" + System.currentTimeMillis();
        int newId = getDevice().createUser(name);
        getDevice().startUser(newId);
        // Note we can't install apps on a locked user
        awaitUserUnlocked(newId);
        return newId;
    }

    private void awaitUserUnlocked(int userId) throws Exception {
        for (int i = 0; i < SWITCH_USER_COMPLETED_NUMBER_OF_POLLS; ++i) {
            String userState = getDevice().executeShellCommand("am get-started-user-state "
                    + userId);
            if (userState.contains("RUNNING_UNLOCKED")) {
                return;
            }
            Thread.sleep(SWITCH_USER_COMPLETED_POLL_INTERVAL_IN_MILLIS);
        }
        fail("Timed out in unlocking user: " + userId);
    }

    private void removeSecondaryUserIfNecessary() throws Exception {
        if (mSecondaryUserId != -1) {
            // Can't remove the 2nd user without switching out of it
            assertThat(getDevice().switchUser(mOriginalUserId)).isTrue();
            getDevice().removeUser(mSecondaryUserId);
            mSecondaryUserId = -1;
            getDevice().deleteFile(String.format("/data/misc_ce/%d/supplemental/"
                      + TEST_APP_PACKAGE, mSecondaryUserId));
            getDevice().deleteFile(String.format("/data/misc_de/%d/supplemental/"
                      + TEST_APP_PACKAGE, mSecondaryUserId));
        }
    }
}
