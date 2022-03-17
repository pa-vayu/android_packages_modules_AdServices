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

package com.android.server.sdksandbox;

import android.annotation.SdkConstant;
import android.annotation.SystemApi;

/**
 * Exposes APIs to {@code system_server} components outside of the module boundaries.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface SdkSandboxManagerLocal {

    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    String SERVICE_INTERFACE = "com.android.sdksandbox.SdkSandboxService";

    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    String VERIFIER_RECEIVER = "com.android.server.sdksandbox.SdkSandboxVerifierReceiver";
}