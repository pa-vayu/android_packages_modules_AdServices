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

package com.android.supplementalprocesscode;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.supplementalprocess.CodeProvider;
import android.view.View;
import android.widget.Toast;

import java.util.Random;
import java.util.concurrent.Executor;

public class SampleCodeProvider extends CodeProvider {

    public SampleCodeProvider(Context codeContext) {
        super(codeContext);
    }

    @Override
    public void initCode(Bundle params, Executor executor, InitCodeCallback callback) {
        callback.onInitCodeFinished(null);
    }

    @Override
    public View getView(Context windowContext, Bundle params) {
        return new TestView(windowContext);
    }

    @Override
    public void onExtraDataReceived(Bundle extraData) {
    }

    private static class TestView extends View {

        TestView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            Paint paint = new Paint();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextSize(50);
            Random random = new Random();
            int c = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256));
            canvas.drawColor(c);
            canvas.drawText("Rendered view", 75, 75, paint);

            setOnClickListener(this::onClickListener);
        }

        private void onClickListener(View view) {
            Context context = view.getContext();
            Toast.makeText(context, "Opening url", Toast.LENGTH_LONG).show();

            String url = "http://www.google.com";
            Intent visitUrl = new Intent(Intent.ACTION_VIEW);
            visitUrl.setData(Uri.parse(url));
            visitUrl.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            mContext.startActivity(visitUrl);
        }

    }
}
