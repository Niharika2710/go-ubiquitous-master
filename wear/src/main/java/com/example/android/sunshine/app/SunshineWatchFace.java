package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {

    private static final String LOG_TAG = SunshineWatchFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final String UPDATE_DATA_WEAR_URI = "/sunshine-wear-update-watchface";

    private static GoogleApiClient googleApiClient = null;

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine
        implements GoogleApiClient.ConnectionCallbacks,
                   ResultCallback<DataItemBuffer>,
                   DataApi.DataListener {

        // Sunshine Application Data

        private String minTemp = null;
        private String maxTemp = null;
        private int weatherId = 800;

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mHourPaint;
        Paint mTempPaint;
        Paint mWeatherIconPaint;

        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        float mXOffset;
        float mYOffset;
        float tempTextOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mHourPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTempPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mWeatherIconPaint = new Paint();

            mTime = new Time();

            googleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .build();

            googleApiClient.connect();

            processPreviousDataItems();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            paint.setTextAlign(Paint.Align.CENTER);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mHourPaint.setTextSize(textSize);
            mTempPaint.setTextSize(resources.getDimension(R.dimen.temp_text_size));
            tempTextOffset = resources.getDimension(R.dimen.temp_text_y_offset);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mHourPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            String hourText = mAmbient
                    ? String.format("%d:%02d", mTime.hour, mTime.minute)
                    : String.format("%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);

            canvas.drawText(hourText, bounds.width() / 2, mYOffset, mHourPaint);

            Bitmap weatherIcon = getWeatherIconById(weatherId);

            if (weatherIcon != null) {
                canvas.drawBitmap(
                        weatherIcon,
                        bounds.width() / 2 - weatherIcon.getWidth() / 2,
                        bounds.height() / 2 - weatherIcon.getHeight() / 2,
                        mWeatherIconPaint
                );
            }

            if (minTemp != null && maxTemp != null) {
                String tempText = minTemp + " - " + maxTemp;

                float height = bounds.height() / 2;

                if (weatherIcon != null) {
                    height += weatherIcon.getHeight() / 2  + tempTextOffset;
                }

                canvas.drawText(tempText, bounds.width() / 2, height, mTempPaint);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.getDataItems(googleApiClient).setResultCallback(this);
            Wearable.DataApi.addListener(googleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onResult(DataItemBuffer dataItems) {}

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {

                Log.d(LOG_TAG, "onDataChanged: " + event.getDataItem().getUri().getPath());

                if (event.getType() == DataEvent.TYPE_CHANGED &&
                    event.getDataItem().getUri().getPath().equals(UPDATE_DATA_WEAR_URI)) {

                    DataItem dataItem = event.getDataItem();
                    processSunshineDataItem(dataItem);
                }

            }

            dataEventBuffer.release();
        }

        private void processPreviousDataItems(){

            PendingResult<DataItemBuffer> results = Wearable.DataApi.getDataItems(googleApiClient);

            results.setResultCallback(new ResultCallback<DataItemBuffer>() {
                @Override
                public void onResult(DataItemBuffer dataItems) {

                    for (DataItem dataItem : dataItems) {
                        Log.d(LOG_TAG, "onResult: " + dataItem.getUri().getPath());
                        if (dataItem.getUri().getPath().equals(UPDATE_DATA_WEAR_URI)) {
                            processSunshineDataItem(dataItem);
                        }
                    }

                    dataItems.release();
                }
            });
        }

        private void processSunshineDataItem(DataItem dataItem) {
            DataMap map = DataMapItem.fromDataItem(dataItem).getDataMap();

            minTemp = map.getString("minTemp", null);
            maxTemp = map.getString("maxTemp", null);
            weatherId = map.getInt("weatherId", 0);

            Log.d(LOG_TAG, "onDataChanged, minTemp: " + minTemp);
            Log.d(LOG_TAG, "onDataChanged, maxTemp: " + maxTemp);
            Log.d(LOG_TAG, "onDataChanged, weatherId: " + Integer.toString(weatherId));
        }

        private Bitmap getWeatherIconById(int weatherId) {

            // http://openweathermap.org/weather-conditions

            int weatherIconRef = 0;

            if (weatherId >= 200 && weatherId <= 232) {
                weatherIconRef = R.mipmap.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                weatherIconRef = R.mipmap.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                weatherIconRef = R.mipmap.ic_rain;
            } else if (weatherId == 511) {
                weatherIconRef = R.mipmap.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                weatherIconRef = R.mipmap.ic_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                weatherIconRef = R.mipmap.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                weatherIconRef = R.mipmap.ic_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                weatherIconRef = R.mipmap.ic_storm;
            } else if (weatherId == 800) {
                weatherIconRef = R.mipmap.ic_clear;
            } else if (weatherId == 801) {
                weatherIconRef = R.mipmap.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                weatherIconRef = R.mipmap.ic_cloudy;
            }

            if (weatherIconRef != 0) {
                return BitmapFactory.decodeResource(SunshineWatchFace.this.getResources(), weatherIconRef);
            } else {
                return null;
            }
        }


    }
}
