package com.opensource.routesim.probe;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal, map-free consumer of Android's LocationManager. This app verifies
 * that a route simulator's samples are visible to another ordinary app.
 */
public final class LocationProbeActivity extends Activity implements LocationListener {
    private static final String TAG = "LocationProbe";
    private static final int REQUEST_LOCATION = 41;
    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    };

    private LocationManager locationManager;
    private TextView statusView;
    private TextView metricsView;
    private TextView historyView;
    private final List<String> history = new ArrayList<>();
    private final List<String> registeredProviders = new ArrayList<>();
    private Location previousLocation;
    private double totalDistanceMeters;
    private long sampleCount;
    private long lastSampleElapsedMillis;
    private boolean updatesRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        locationManager = getSystemService(LocationManager.class);
        setContentView(createContentView());

        if (hasLocationPermission()) {
            startLocationUpdates();
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_LOCATION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasLocationPermission() && !updatesRequested) {
            startLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        stopLocationUpdates();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LOCATION) {
            return;
        }
        if (hasLocationPermission()) {
            startLocationUpdates();
        } else {
            statusView.setText("定位权限被拒绝");
            Log.w(TAG, "event=permission_denied");
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) {
            return;
        }
        sampleCount++;
        long now = android.os.SystemClock.elapsedRealtime();
        double intervalSeconds = lastSampleElapsedMillis == 0L
                ? 0.0
                : (now - lastSampleElapsedMillis) / 1000.0;
        lastSampleElapsedMillis = now;

        double segmentMeters = 0.0;
        if (previousLocation != null) {
            segmentMeters = previousLocation.distanceTo(location);
            totalDistanceMeters += segmentMeters;
        }
        previousLocation = new Location(location);

        String provider = location.getProvider();
        String summary = String.format(
                Locale.US,
                "样本 #%d  (%s)\n"
                        + "纬度  %.7f\n经度  %.7f\n"
                        + "速度  %.2f km/h\n方位  %.1f°\n"
                        + "精度  ±%.1f m\n"
                        + "间隔  %.2f s\n"
                        + "位移  %.1f m（累计 %.1f m）",
                sampleCount,
                provider,
                location.getLatitude(),
                location.getLongitude(),
                location.hasSpeed() ? location.getSpeed() * 3.6 : 0.0,
                location.hasBearing() ? location.getBearing() : 0.0,
                location.hasAccuracy() ? location.getAccuracy() : -1.0,
                intervalSeconds,
                segmentMeters,
                totalDistanceMeters
        );
        metricsView.setText(summary);
        history.add(0, String.format(
                Locale.US,
                "#%d %s %.7f, %.7f  %.1fkm/h",
                sampleCount,
                provider,
                location.getLatitude(),
                location.getLongitude(),
                location.hasSpeed() ? location.getSpeed() * 3.6 : 0.0
        ));
        while (history.size() > 8) {
            history.remove(history.size() - 1);
        }
        historyView.setText(TextUtils.join("\n", history));

        Log.i(
                TAG,
                String.format(
                        Locale.US,
                        "event=sample n=%d provider=%s lat=%.7f lng=%.7f "
                                + "speed_kmh=%.2f bearing=%.1f accuracy=%.1f "
                                + "interval_s=%.2f segment_m=%.1f total_m=%.1f",
                        sampleCount,
                        provider,
                        location.getLatitude(),
                        location.getLongitude(),
                        location.hasSpeed() ? location.getSpeed() * 3.6 : 0.0,
                        location.hasBearing() ? location.getBearing() : 0.0,
                        location.hasAccuracy() ? location.getAccuracy() : -1.0,
                        intervalSeconds,
                        segmentMeters,
                        totalDistanceMeters
                )
        );
    }

    @Override
    public void onProviderEnabled(String provider) {
        Log.i(TAG, "event=provider_enabled provider=" + provider);
    }

    @Override
    public void onProviderDisabled(String provider) {
        Log.i(TAG, "event=provider_disabled provider=" + provider);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) {
        Log.i(TAG, "event=provider_status provider=" + provider + " status=" + status);
    }

    private void startLocationUpdates() {
        if (updatesRequested) {
            return;
        }
        registeredProviders.clear();
        for (String provider : new String[]{
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        }) {
            if (!locationManager.isProviderEnabled(provider)) {
                continue;
            }
            try {
                locationManager.requestLocationUpdates(
                        provider,
                        500L,
                        0f,
                        this,
                        Looper.getMainLooper()
                );
                registeredProviders.add(provider);
            } catch (SecurityException | IllegalArgumentException error) {
                Log.e(TAG, "event=request_failed provider=" + provider, error);
            }
        }
        updatesRequested = !registeredProviders.isEmpty();
        statusView.setText(
                updatesRequested
                        ? "正在读取位置：" + TextUtils.join(" + ", registeredProviders)
                        : "没有可用的定位 Provider"
        );
        Log.i(
                TAG,
                "event=updates_started providers=" + TextUtils.join(",", registeredProviders)
        );
    }

    private void stopLocationUpdates() {
        if (!updatesRequested) {
            return;
        }
        locationManager.removeUpdates(this);
        updatesRequested = false;
        Log.i(TAG, "event=updates_stopped samples=" + sampleCount);
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private android.view.View createContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.rgb(247, 250, 249));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(28));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("Location Probe");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(13, 124, 102));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("独立定位读取器 · 不包含地图");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.rgb(100, 115, 111));
        subtitle.setPadding(0, dp(2), 0, dp(20));
        root.addView(subtitle);

        statusView = new TextView(this);
        statusView.setText("等待定位权限");
        statusView.setTextSize(14);
        statusView.setTextColor(Color.rgb(17, 33, 30));
        root.addView(statusView);

        metricsView = new TextView(this);
        metricsView.setText("还没有收到位置样本");
        metricsView.setTextSize(18);
        metricsView.setTextColor(Color.rgb(17, 33, 30));
        metricsView.setGravity(Gravity.START);
        metricsView.setLineSpacing(0f, 1.18f);
        metricsView.setPadding(0, dp(22), 0, dp(24));
        root.addView(metricsView);

        TextView historyTitle = new TextView(this);
        historyTitle.setText("最近样本");
        historyTitle.setTextSize(14);
        historyTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        historyTitle.setTextColor(Color.rgb(17, 33, 30));
        root.addView(historyTitle);

        historyView = new TextView(this);
        historyView.setText("—");
        historyView.setTextSize(12);
        historyView.setTextColor(Color.rgb(100, 115, 111));
        historyView.setLineSpacing(0f, 1.15f);
        historyView.setTextIsSelectable(true);
        historyView.setPadding(0, dp(8), 0, 0);
        root.addView(historyView);

        return scrollView;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
