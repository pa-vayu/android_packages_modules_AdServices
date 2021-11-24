/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.supplementalprocess;

import static android.os.Process.myUid;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link SupplementalProcessServiceProviderImpl}.
 */
@RunWith(JUnit4.class)
public class SupplementalProcessServiceProviderImplUnitTest {

    SupplementalProcessServiceProvider mServiceProvider;

    @Before
    public void setup() {
        Context context = InstrumentationRegistry.getContext();
        mServiceProvider = new SupplementalProcessServiceProviderImpl(context);
    }

    @Test
    public void testSupplementalProcessBinding() throws Exception {
        final int curUid = myUid();

        // Supplemental process is loaded on demand, so should not be there initially
        assertThat(mServiceProvider.isServiceBound(curUid)).isFalse();
        mServiceProvider.bindService(curUid, new Binder());
        assertThat(mServiceProvider.isServiceBound(curUid)).isTrue();
    }

    @Test
    public void testSupplementalProcessUnbinding() throws Exception {
        final int curUid = myUid();

        ArgumentCaptor<IBinder.DeathRecipient> deathRecipient = ArgumentCaptor
                .forClass(IBinder.DeathRecipient.class);
        IBinder appBinder = mock(Binder.class);
        mServiceProvider.bindService(curUid, appBinder);

        verify(appBinder).linkToDeath(deathRecipient.capture(), eq(0));
        assertThat(mServiceProvider.isServiceBound(curUid)).isTrue();
        deathRecipient.getValue().binderDied();
        assertThat(mServiceProvider.isServiceBound(curUid)).isFalse();
    }
}
