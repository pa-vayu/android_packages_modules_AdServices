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

package com.android.sdksandbox.inprocess;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.media.MediaDrm;
import android.media.UnsupportedSchemeException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.UUID;

/**
 * Tests to check SDK sandbox process restrictions.
 */
@RunWith(JUnit4.class)
public class SdkSandboxRestrictionsTest {

    /**
     * Tests that sandbox cannot access the Widevine ID.
     */
    @Test
    public void testNoWidevineAccess() throws Exception {
        UUID widevineUuid = new UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL);

        UnsupportedSchemeException thrown = assertThrows(
                UnsupportedSchemeException.class,
                () -> new MediaDrm(widevineUuid));
        assertThat(thrown).hasMessageThat().contains("NO_INIT");
    }
}
