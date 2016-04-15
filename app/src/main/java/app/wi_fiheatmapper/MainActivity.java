package app.wi_fiheatmapper;

import android.content.Intent;
import android.graphics.Color;
import android.location.Location;
import android.net.wifi.ScanResult;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private final SurveyingService.SurveyingServiceConnection mSurveyingServiceConnection
            = new SurveyingService.SurveyingServiceConnection(new SurveyingServiceListener());
    private final Map<Location, Circle> mCirclesMap = new HashMap<>();
    private GoogleMap mMap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(new MapReadyCallback());


        if (savedInstanceState == null) {
            SsidPickerDialog dialog = SsidPickerDialog.newInstance(new SsidSelectedCallback());
            dialog.show(getSupportFragmentManager(), SsidPickerDialog.TAG);
        }

        startService(new Intent(this, SurveyingService.class));
    }

    @Override
    public void onStart() {
        super.onStart();

        mSurveyingServiceConnection.bound(this);
    }

    @Override
    public void onStop() {
        super.onStop();

        mSurveyingServiceConnection.unbound(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        stopService(new Intent(this, SurveyingService.class));
    }

    private void showLastLocation() {
        if (mSurveyingServiceConnection.getService() != null) {
            Location loc = mSurveyingServiceConnection.getService().getLastLocation();
            showLocation(loc);
        }
    }

    private void showLocation(Location loc) {
        if (loc != null && mMap != null) {
            LatLng latLng = locationToLatLng(loc);
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 20));
        }
    }

    @NonNull
    private LatLng locationToLatLng(Location loc) {
        return new LatLng(loc.getLatitude(), loc.getLongitude());
    }

    private class SurveyingServiceListener implements SurveyingService.ServiceListener {
        @Override
        public void onScanCompleted(List<ScanResult> scanResults) {
        }

        @Override
        public void onHeatmapDataUpdated(Map<Location, Integer> heatmapData) {
            if (heatmapData.isEmpty()) return;

            int min = Collections.max(heatmapData.values());
            int max = Collections.min(heatmapData.values());

            for (Map.Entry<Location, Integer> entry : heatmapData.entrySet()) {
                Location loc = entry.getKey();
                Circle circle = mCirclesMap.get(loc);
                if (circle == null) {
                    CircleOptions circleOptions = new CircleOptions().center(locationToLatLng(loc))
                                                                     .radius(getResources().getInteger(R.integer.radius) / 2d)
                                                                     .strokeWidth(0);
                    circle = mMap.addCircle(circleOptions);
                    mCirclesMap.put(loc, circle);
                }

                // 0 = full signal
                // 1 = no signal
                double signalLoss;
                if (max - min == 0) {
                    signalLoss = 0.5;
                } else {
                    signalLoss = (entry.getValue() - min) / (double) (max - min);
                }
                int red = 0xFF;
                int green = 0xFF;
                if (signalLoss >= 0.5) {
                    // to half add green to make orange
                    green = (int) ((1 - signalLoss) * 0xFF / 0.5);
                } else {    // signalLoss < 0.5
                    // from half substract red to make green
                    red = (int) ((signalLoss) * 0xFF / 0.5);
                }

                int color = Color.rgb(red, green, 0);
                if (circle.getFillColor() != color) {
                    circle.setFillColor(color);
                }
            }
        }

        @Override
        public void onLocationUpdated(Location location) {
            showLocation(location);
        }
    }

    private class SsidSelectedCallback implements SsidPickerDialog.DialogCallback {
        @Override
        public void onSsidSelected(String ssid) {
            if (mSurveyingServiceConnection.getService() != null) {
                mSurveyingServiceConnection.getService().surveySsid(ssid);
            }
        }
    }

    private class MapReadyCallback implements com.google.android.gms.maps.OnMapReadyCallback {
        @Override
        public void onMapReady(GoogleMap googleMap) {
            mMap = googleMap;

            mMap.setMyLocationEnabled(true);

            showLastLocation();
        }
    }
}
