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

public class SurveyingService extends Service {

    public static final int LOCAION_INTERVAL = 1000;
    public static final int LOCATION_FASTEST_INTERVAL = 500;
    private final IBinder mBinder = new LocalBinder();
    private final List<ServiceListener> mListeners = new ArrayList<>();
    private final LocationListener mLocationListener = new LocationListener();
    private final Map<Location, Integer> mHeatmapData = new HashMap<>();
    private ScanResultReceiver mScanReceiver;
    private GoogleApiClient mGoogleApiClient;
    private String mSurveyedSsid;
    private Location mLastLocation;
    private int mLastSignalLevel;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        registerReceiver(mScanReceiver = new ScanResultReceiver(),
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

        unregisterReceiver(mScanReceiver);
    }

    public void registerListener(ServiceListener listener) {
        mListeners.add(listener);

        listener.onHeatmapDataUpdated(mHeatmapData);
    }

    public void unregisterListener(ServiceListener listener) {
        mListeners.remove(listener);
    }

    public void surveySsid(String ssid) {
        mSurveyedSsid = ssid;

        LocationRequest locReq = new LocationRequest().setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                                                      .setInterval(LOCAION_INTERVAL)
                                                      .setFastestInterval(LOCATION_FASTEST_INTERVAL)
                                                      .setSmallestDisplacement(getResources().getInteger(R.integer.radius));
        LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, locReq,
                                                                 mLocationListener);
    }

    public Location getLastLocation() {
        return LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
    }

    private void updateHeatmap() {
        if (mLastLocation == null) return;

        mHeatmapData.put(mLastLocation, mLastSignalLevel);

        for (ServiceListener listener : mListeners) {
            listener.onHeatmapDataUpdated(mHeatmapData);
        }
    }

    public interface ServiceListener {
        void onScanCompleted(List<ScanResult> scanResults);

        void onHeatmapDataUpdated(Map<Location, Integer> heatmapData);

        void onLocationUpdated(Location lastLocation);
    }

    public static class SurveyingServiceConnection implements ServiceConnection {
        private SurveyingService mService;
        private boolean mBounded;
        private ServiceListener mListener;

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
            mService.unregisterListener(mListener);
            if (mBounded) {
                mBounded = false;
                context.unbindService(this);
            }
        }
    }

    private class GApiClientConnCallback implements GoogleApiClient.ConnectionCallbacks {

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            for (ServiceListener listener : mListeners) {
                listener.onLocationUpdated(getLastLocation());
            }
        }

        @Override
        public void onConnectionSuspended(int i) {
        }

    }

    private class LocationListener implements com.google.android.gms.location.LocationListener {

        @Override
        public void onLocationChanged(Location location) {
            mLastLocation = location;

            updateHeatmap();
        }
    }

    private class ScanResultReceiver extends BroadcastReceiver {
        private WifiManager mWifiManager;

        public ScanResultReceiver() {
            mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
            mWifiManager.setWifiEnabled(true);
            mWifiManager.startScan();
        }

        @Override
        public void onReceive(Context c, Intent intent) {
            for (ServiceListener listener : mListeners) {
                listener.onScanCompleted(mWifiManager.getScanResults());
            }

            for (ScanResult scanResult : mWifiManager.getScanResults()) {
                String scanedSsid = !TextUtils.isEmpty(scanResult.SSID) ? scanResult.SSID : scanResult.BSSID;

                if (scanedSsid.equals(mSurveyedSsid)) {
                    mLastSignalLevel = scanResult.level;
                    updateHeatmap();
                    break;
                }
            }

            mWifiManager.startScan();
        }
    }

    public class LocalBinder extends Binder {
        SurveyingService getService() {
            return SurveyingService.this;
        }
    }
}
