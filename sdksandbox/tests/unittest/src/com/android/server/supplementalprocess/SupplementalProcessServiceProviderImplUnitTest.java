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

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;

import androidx.test.InstrumentationRegistry;

import com.android.supplemental.process.ISupplementalProcessService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.time.Duration;

/**
 * Unit tests for {@link SupplementalProcessServiceProviderImpl}.
 */
@RunWith(JUnit4.class)
public class SupplementalProcessServiceProviderImplUnitTest {

    SupplementalProcessServiceProvider mServiceProvider;
    Context mContext;

    @Before
    public void setup() {
        mContext = Mockito.spy(InstrumentationRegistry.getContext());
        mServiceProvider = new SupplementalProcessServiceProviderImpl(mContext, new Injector());
    }

    @Test
    public void testSupplementalProcessServiceBinding() throws InterruptedException {
        final int curUid = myUid();

        // Supplemental process is loaded on demand, so should not be there initially
        assertThat(mServiceProvider.getBoundServiceForApp(curUid)).isNull();

        mServiceProvider.bindService(curUid, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mServiceProvider.registerServiceForApp(
                        curUid, ISupplementalProcessService.Stub.asInterface(service));
            }
            @Override
            public void onServiceDisconnected(ComponentName name) {}
        });

        Duration timeout = Duration.ofSeconds(1);
        Duration waitingTime = Duration.ofMillis(100);
        while (!timeout.isNegative() && mServiceProvider.getBoundServiceForApp(curUid) == null) {
            Thread.sleep(waitingTime.toMillis());
            timeout = timeout.minus(waitingTime);
        }
        assertThat(mServiceProvider.getBoundServiceForApp(curUid)).isNotNull();
    }

    private static class Injector extends SupplementalProcessServiceProviderImpl.Injector {
        @Override
        public String getServicePackage() {
            return "com.android.serviceprovider";
        }

        @Override
        public String getServiceClass() {
            return "com.android.serviceprovider.TestService";
        }
    }
}
