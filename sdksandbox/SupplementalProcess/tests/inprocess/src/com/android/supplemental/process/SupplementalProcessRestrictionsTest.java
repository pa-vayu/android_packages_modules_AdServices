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

package com.android.supplemental.process;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.os.SELinux;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;

/**
 * Tests exercising various restrictions imposed on the supplemental process.
 *
 * <p>These tests instrument the SupplementalProcess APK.
 */
@RunWith(JUnit4.class)
public class SupplementalProcessRestrictionsTest {

    /**
     * Tests that supplemental process runs in its own SELinux domain.
     */
    @Test
    public void testSepolicyDomain() throws Exception {
        String ctx = SELinux.getContext();
        assertThat(ctx).startsWith("u:r:supplemental_process:s0");
    }

    /**
     * Tests that supplemental process doesn't have internal storage.
     */
    @Test
    public void testNoInternalStorage() throws Exception {
        Context ceCtx = getInstrumentation().getTargetContext();
        File ceDataDir = ceCtx.getDataDir();
        assertThat(ceDataDir.getAbsolutePath())
                .isEqualTo("/data/user/0/com.android.supplemental.process");
        assertThat(ceDataDir.exists()).isFalse();
        assertThat(ceDataDir.mkdirs()).isFalse();

        Context deCtx = ceCtx.createDeviceProtectedStorageContext();
        File deDataDir = deCtx.getDataDir();
        assertThat(deDataDir.getAbsolutePath())
                .isEqualTo("/data/user_de/0/com.android.supplemental.process");
        assertThat(deDataDir.exists()).isFalse();
        assertThat(deDataDir.mkdirs()).isFalse();
    }

    // TODO(b/211761016): add tests for external storage
}
