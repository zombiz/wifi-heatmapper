package app.wi_fiheatmapper;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.location.Location;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service collect location and network data for create signal heatmap.
 */
public class SurveyingService extends Service {

    // Setting for location Google location provider.
    private static final int LOCAION_INTERVAL = 1000;
    private static final int LOCATION_FASTEST_INTERVAL = 500;

    private final IBinder mBinder = new LocalBinder();
    private final List<ServiceListener> mServiceListeners = new ArrayList<>();
    private final LocationListener mLocationListener = new LocationListener();

    /**
     * Heatmap data consist of geolocation and signal strenght for that location.
     * Signal strenght can be updated for last stored location because location provider can send
     * new location only after device move by some distance from previous location.
     */
    private final Map<Location, Integer> mHeatmapData = new HashMap<>();

    private WiFiScanReceiver mWiFiScanReceiver;
    private GoogleApiClient mGoogleApiClient;

    /**
     * SSID selected for surveying.
     */
    private String mSurveyedSsid;

    /**
     * Last collected data.
     */
    private Location mLastLocation;

    /**
     * If no signal detected than {@link Integer#MIN_VALUE} is used.
     */
    private int mLastRssi = Integer.MIN_VALUE;

    @Override
    public void onCreate() {
        super.onCreate();

        // Start receiving surrouding Wi-Fi informations.
        registerReceiver(mWiFiScanReceiver = new WiFiScanReceiver(),
                         new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(new GApiClientConnCallback())
                .addApi(LocationServices.API)
                .build();
        mGoogleApiClient.connect();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, mLocationListener);

        mGoogleApiClient.disconnect();

        unregisterReceiver(mWiFiScanReceiver);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * Register listener for this service and provide heatmap data for this listener.
     *
     * @param listener Listener for listening on events from this service.
     */
    public void registerListener(ServiceListener listener) {
        if (listener == null) return;

        mServiceListeners.add(listener);

        listener.onHeatmapDataUpdated(getHeatmapData());
    }

    public void unregisterListener(ServiceListener listener) {
        mServiceListeners.remove(listener);
    }

    /**
     * Set for which SSID will be collected heatmap data.
     * This also start receiving location updates.
     *
     * @param ssid SSID of desired network
     */
    public void surveySsid(String ssid) {
        mSurveyedSsid = ssid;

        // We want high accuracy with updates every 1.5 meter.
        LocationRequest locReq = new LocationRequest().setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                                                      .setInterval(LOCAION_INTERVAL)
                                                      .setFastestInterval(LOCATION_FASTEST_INTERVAL)
                                                      .setSmallestDisplacement(getResources().getInteger(R.integer.diameter) / 2f);
        LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, locReq,
                                                                 mLocationListener);
    }

    /**
     * Get last location from location provider;
     *
     * @return
     */
    public Location getLastLocation() {
        return LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
    }

    /**
     * Get actual heatmap data.
     * @return Map with heatmap data.
     */
    public Map<Location, Integer> getHeatmapData() {
        return new HashMap<>(mHeatmapData);
    }

    /**
     * Update heatmap with lastest data and notify listeners.
     * @param location
     */
    private void updateHeatmap(Location location) {
        if (location == null || location == mLastLocation || mLastRssi == Integer.MIN_VALUE) return;

        mHeatmapData.put(location, mLastRssi);
        mLastLocation = location;

        for (ServiceListener listener : mServiceListeners) {
            listener.onHeatmapDataUpdated(getHeatmapData());
        }
    }

    public interface ServiceListener {
        /**
         * Called if scanning of surrounding network is complete.
         *
         * @param scanResults Result of scan.
         */
        void onWiFiScanCompleted(List<ScanResult> scanResults);

        /**
         * Called if new info about RSSI is available about selected network.
         *
         * @param surveyedSsid SSID of surveyed network.
         * @param rssi         Signal strenght.
         */
        void onSurveyedWiFiUpdated(String surveyedSsid, int rssi);

        void onHeatmapDataUpdated(Map<Location, Integer> heatmapData);

        /**
         * Called if last location from provider is updated.
         *
         * @param lastLocation Last loaction.
         */
        void onLastLocationUpdated(Location lastLocation);
    }

    /**
     * This is used as connection between service and clients (activity etc.).
     * It's kind of helper to get rid of bounding a unbounding boilerplate code.
     */
    public static class SurveyingServiceConnection implements ServiceConnection {
        private final ServiceListener mListener;
        private SurveyingService mService;
        private boolean mBounded;

        public SurveyingServiceConnection(ServiceListener listener) {
            mListener = listener;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBounded = true;
            mService = ((SurveyingService.LocalBinder) service).getService();
            mService.registerListener(mListener);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBounded = false;
            mService.unregisterListener(mListener);
        }

        public SurveyingService getService() {
            return mService;
        }

        public void bound(Context context) {
            Intent intent = new Intent(context, SurveyingService.class);
            context.bindService(intent, this, Context.BIND_AUTO_CREATE);
        }

        public void unbound(Context context) {
            if (mService != null) mService.unregisterListener(mListener);

            if (mBounded) {
                mBounded = false;
                context.unbindService(this);
            }
        }
    }

    public class LocalBinder extends Binder {

        SurveyingService getService() {
            return SurveyingService.this;
        }
    }

    /**
     * Callback for GoogleApiClient. Notify service listeners about last know location after connect.
     */
    private class GApiClientConnCallback implements GoogleApiClient.ConnectionCallbacks {

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            for (ServiceListener listener : mServiceListeners) {
                listener.onLastLocationUpdated(getLastLocation());
            }
        }

        @Override
        public void onConnectionSuspended(int i) {
        }
    }

    /**
     * Listener for location updates from provider.
     */
    private class LocationListener implements com.google.android.gms.location.LocationListener {

        @Override
        public void onLocationChanged(Location location) {
            updateHeatmap(location);
        }
    }

    /**
     * Receiver of surrounding networks status.
     * onReceive is called if scan is complate so we can get result by {@link WifiManager#getScanResults}.
     * Than start new scan.
     */
    private class WiFiScanReceiver extends BroadcastReceiver {
        private WifiManager mWifiManager;

        public WiFiScanReceiver() {
            mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
            mWifiManager.setWifiEnabled(true);
            mWifiManager.startScan();
        }

        @Override
        public void onReceive(Context c, Intent intent) {
            for (ServiceListener listener : mServiceListeners) {
                listener.onWiFiScanCompleted(mWifiManager.getScanResults());
            }

            if (mSurveyedSsid == null) {
                // No network selected yet.
                mWifiManager.startScan();
                return;
            }

            // Get RSSI of surveyed network if is in range.
            int rssi = Integer.MIN_VALUE;
            for (ScanResult scanResult : mWifiManager.getScanResults()) {
                String scanedSsid = !TextUtils.isEmpty(scanResult.SSID) ? scanResult.SSID : scanResult.BSSID;
                if (scanedSsid.equals(mSurveyedSsid)) {
                    rssi = scanResult.level;
                    break;
                }
            }

            mLastRssi = rssi;

            for (ServiceListener listener : mServiceListeners) {
                listener.onSurveyedWiFiUpdated(mSurveyedSsid, mLastRssi);
            }

            mWifiManager.startScan();
        }
    }
}
