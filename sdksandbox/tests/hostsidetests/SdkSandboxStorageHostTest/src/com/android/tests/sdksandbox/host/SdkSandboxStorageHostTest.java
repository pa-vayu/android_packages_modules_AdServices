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

package com.android.tests.sdksandbox.host;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.cts.install.lib.host.InstallUtilsHost;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@RunWith(DeviceJUnit4ClassRunner.class)
public final class SdkSandboxStorageHostTest extends BaseHostJUnit4Test {

    private int mOriginalUserId;
    private int mSecondaryUserId = -1;
    private boolean mWasRoot;

    private static final String CODE_PROVIDER_APK_NAME = "StorageTestCodeProvider.apk";
    private static final String TEST_APP_APK_NAME = "SdkSandboxStorageTestApp.apk";
    private static final String TEST_APP_PACKAGE = "com.android.tests.sdksandbox";

    private static final String SYS_PROP_DEFAULT_CERT_DIGEST =
            "debug.pm.uses_sdk_library_default_cert_digest";

    private static final long SWITCH_USER_COMPLETED_NUMBER_OF_POLLS = 60;
    private static final long SWITCH_USER_COMPLETED_POLL_INTERVAL_IN_MILLIS = 1000;

    private final InstallUtilsHost mHostUtils = new InstallUtilsHost(this);

    /**
     * Runs the given phase of a test by calling into the device.
     * Throws an exception if the test phase fails.
     * <p>
     * For example, <code>runPhase("testExample");</code>
     */
    private void runPhase(String phase) throws Exception {
        assertThat(runDeviceTests("com.android.tests.sdksandbox",
                "com.android.tests.sdksandbox.SdkSandboxStorageTestApp",
                phase)).isTrue();
    }

    @Before
    public void setUp() throws Exception {
        // TODO(b/209061624): See if we can remove root privilege when instrumentation support for
        // sdk sandbox is added.
        mWasRoot = getDevice().isAdbRoot();
        getDevice().enableAdbRoot();
        mOriginalUserId = getDevice().getCurrentUser();
        setSystemProperty(SYS_PROP_DEFAULT_CERT_DIGEST, getPackageCertDigest(
                CODE_PROVIDER_APK_NAME));
        // TODO(b/211766362): remove sdksandbox data manually for now
        getDevice().deleteFile("/data/misc_ce/0/sdksandbox/" + TEST_APP_PACKAGE);
        getDevice().deleteFile("/data/misc_de/0/sdksandbox/" + TEST_APP_PACKAGE);
    }

    @After
    public void tearDown() throws Exception {
        removeSecondaryUserIfNecessary();
        uninstallPackage(TEST_APP_PACKAGE);
        setSystemProperty(SYS_PROP_DEFAULT_CERT_DIGEST, "invalid");
        // TODO(b/211766362): remove sdksandbox data manually for now
        getDevice().deleteFile("/data/misc_ce/0/sdksandbox/" + TEST_APP_PACKAGE);
        getDevice().deleteFile("/data/misc_de/0/sdksandbox/" + TEST_APP_PACKAGE);
        if (!mWasRoot) {
            getDevice().disableAdbRoot();
        }
    }

    /**
     * Verify that {@code /data/misc_{ce,de}/<user-id>/sdksandbox} is created when
     * {@code <user-id>} is created.
     */
    @Test
    public void testSdkSandboxDataRootDirectory_IsCreatedOnUserCreate() throws Exception {
        {
            // Verify root directory exists for primary user
            final String cePath = getSdkSandboxCeDataRootPath(0);
            final String dePath = getSdkSandboxDeDataRootPath(0);
            assertThat(getDevice().isDirectory(dePath)).isTrue();
            assertThat(getDevice().isDirectory(cePath)).isTrue();
        }

        {
            // Verify root directory is created for new user
            mSecondaryUserId = createAndStartSecondaryUser();
            final String cePath = getSdkSandboxCeDataRootPath(mSecondaryUserId);
            final String dePath = getSdkSandboxDeDataRootPath(mSecondaryUserId);
            assertThat(getDevice().isDirectory(dePath)).isTrue();
            assertThat(getDevice().isDirectory(cePath)).isTrue();
        }
    }

    @Test
    public void testSdkSandboxDataAppDirectory_IsCreatedOnInstall() throws Exception {
        // Directory should not exist before install
        final String cePath = getSdkSandboxCeDataAppPath(0, TEST_APP_PACKAGE);
        final String dePath = getSdkSandboxDeDataAppPath(0, TEST_APP_PACKAGE);
        assertThat(getDevice().isDirectory(cePath)).isFalse();
        assertThat(getDevice().isDirectory(dePath)).isFalse();

        // Install the app
        installPackage(TEST_APP_APK_NAME);

        // Verify directory is created
        assertThat(getDevice().isDirectory(cePath)).isTrue();
        assertThat(getDevice().isDirectory(dePath)).isTrue();
    }

    @Test
    public void testSdkSandboxDataAppDirectory_IsUserSpecific() throws Exception {
        // Install first before creating the user
        installPackage(TEST_APP_APK_NAME, "--user all");

        mSecondaryUserId = createAndStartSecondaryUser();

        // Data directories should not exist as the package is not installed on new user
        final String ceAppPath = getAppCeDataPath(mSecondaryUserId, TEST_APP_PACKAGE);
        final String deAppPath = getAppDeDataPath(mSecondaryUserId, TEST_APP_PACKAGE);
        final String cePath = getSdkSandboxCeDataAppPath(mSecondaryUserId, TEST_APP_PACKAGE);
        final String dePath = getSdkSandboxDeDataAppPath(mSecondaryUserId, TEST_APP_PACKAGE);

        assertThat(getDevice().isDirectory(ceAppPath)).isFalse();
        assertThat(getDevice().isDirectory(deAppPath)).isFalse();
        assertThat(getDevice().isDirectory(cePath)).isFalse();
        assertThat(getDevice().isDirectory(dePath)).isFalse();

        // Install the app on new user
        installPackage(TEST_APP_APK_NAME);

        assertThat(getDevice().isDirectory(cePath)).isTrue();
        assertThat(getDevice().isDirectory(dePath)).isTrue();
    }

    @Test
    public void testSelinuxLabel() throws Exception {
        installPackage(TEST_APP_APK_NAME);

        {
            // Check label of /data/misc_ce/0/sdksandbox
            final String path = "/data/misc_ce/0/sdksandbox";
            final String output = getDevice().executeShellCommand("ls -ldZ " + path);
            assertThat(output).contains("u:object_r:system_data_file");
        }
        {
            // Check label of /data/misc_de/0/sdksandbox
            final String path = "/data/misc_de/0/sdksandbox";
            final String output = getDevice().executeShellCommand("ls -ldZ " + path);
            assertThat(output).contains("u:object_r:system_data_file");
        }
        {
            // Check label of /data/misc_ce/0/sdksandbox/<app-name>/shared
            final String path = getSdkSandboxCeDataAppSharedPath(0, TEST_APP_PACKAGE);
            final String output = getDevice().executeShellCommand("ls -ldZ " + path);
            assertThat(output).contains("u:object_r:sdk_sandbox_data_file");
        }
        {
            // Check label of /data/misc_de/0/sdksandbox/<app-name>/shared
            final String path = getSdkSandboxDeDataAppSharedPath(0, TEST_APP_PACKAGE);
            final String output = getDevice().executeShellCommand("ls -ldZ " + path);
            assertThat(output).contains("u:object_r:sdk_sandbox_data_file");
        }
    }

    @Test
    public void testSdkSandboxDataAppDirectory_SharedStorageIsUsable() throws Exception {
        installPackage(TEST_APP_APK_NAME);

        // Verify that shared storage exist
        final String sharedCePath = getSdkSandboxCeDataAppSharedPath(0, TEST_APP_PACKAGE);
        assertThat(getDevice().isDirectory(sharedCePath)).isTrue();

        // Write a file in the shared storage that code needs to read and write it back
        // in another file
        String fileToRead = sharedCePath + "/readme.txt";
        getDevice().executeShellCommand("echo something to read > " + fileToRead);
        assertThat(getDevice().doesFileExist(fileToRead)).isTrue();

        runPhase("testSdkSandboxDataAppDirectory_SharedStorageIsUsable");

        // Assert that code was able to create file and directories
        assertThat(getDevice().isDirectory(sharedCePath + "/dir")).isTrue();
        assertThat(getDevice().doesFileExist(sharedCePath + "/dir/file")).isTrue();
        String content = getDevice().executeShellCommand("cat " + sharedCePath + "/dir/file");
        assertThat(content).isEqualTo("something to read");
    }


    private String getAppCeDataPath(int userId, String packageName) {
        return String.format("/data/user/%d/%s", userId, packageName);
    }

    private String getAppDeDataPath(int userId, String packageName) {
        return String.format("/data/user_de/%d/%s", userId, packageName);
    }

    private String getSdkSandboxCeDataRootPath(int userId) {
        return String.format("/data/misc_ce/%d/sdksandbox", userId);
    }

    private String getSdkSandboxDeDataRootPath(int userId) {
        return String.format("/data/misc_de/%d/sdksandbox", userId);
    }

    private String getSdkSandboxCeDataAppPath(int userId, String packageName) {
        return String.format(
            "%s/%s", getSdkSandboxCeDataRootPath(userId), packageName);
    }

    private String getSdkSandboxDeDataAppPath(int userId, String packageName) {
        return String.format(
            "%s/%s", getSdkSandboxDeDataRootPath(userId), packageName);
    }

    private String getSdkSandboxCeDataAppSharedPath(int userId, String packageName) {
        return String.format(
            "%s/shared", getSdkSandboxCeDataAppPath(userId, packageName));
    }

    private String getSdkSandboxDeDataAppSharedPath(int userId, String packageName) {
        return String.format(
            "%s/shared", getSdkSandboxDeDataAppPath(userId, packageName));
    }

    private int createAndStartSecondaryUser() throws Exception {
        String name = "SdkSandboxStorageHostTest_User" + System.currentTimeMillis();
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
            getDevice().deleteFile(String.format("/data/misc_ce/%d/sdksandbox/"
                      + TEST_APP_PACKAGE, mSecondaryUserId));
            getDevice().deleteFile(String.format("/data/misc_de/%d/sdksandbox/"
                      + TEST_APP_PACKAGE, mSecondaryUserId));
        }
    }

    /**
     * Extracts the certificate used to sign an apk in HexEncoded form.
     */
    private String getPackageCertDigest(String apkFileName) throws Exception {
        File apkFile = mHostUtils.getTestFile(apkFileName);
        JarFile apkJar = new JarFile(apkFile);
        JarEntry manifestEntry = apkJar.getJarEntry("AndroidManifest.xml");
        // #getCertificate can only be called once the JarEntry has been completely
        // verified by reading from the entry input stream until the end of the
        // stream has been reached.
        byte[] readBuffer = new byte[8192];
        InputStream input = new BufferedInputStream(apkJar.getInputStream(manifestEntry));
        while (input.read(readBuffer, 0, readBuffer.length) != -1) {
            // not used
        }
        // We can now call #getCertificates
        Certificate[] certs = manifestEntry.getCertificates();

        // Create SHA256 digest of the certificate
        MessageDigest sha256DigestCreator = MessageDigest.getInstance("SHA-256");
        sha256DigestCreator.update(certs[0].getEncoded());
        byte[] digest = sha256DigestCreator.digest();
        return new String(encodeToHex(digest)).trim();
    }

    /**
     * Encodes the provided data as a sequence of hexadecimal characters.
     */
    private static char[] encodeToHex(byte[] data) {
        final char[] digits = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
        };
        char[] result = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            byte b = data[i];
            int resultIndex = 2 * i;
            result[resultIndex] = (digits[(b >> 4) & 0x0f]);
            result[resultIndex + 1] = (digits[b & 0x0f]);
        }

        return result;
    }

    private void setSystemProperty(String name, String value) throws Exception {
        assertThat(getDevice().executeShellCommand(
              "setprop " + name + " " + value)).isEqualTo("");
    }
}
