package app.wi_fiheatmapper;

import android.content.Intent;
import android.graphics.Color;
import android.location.Location;
import android.net.wifi.ScanResult;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.TextView;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Acitivity show RSSI heatmap of selected network on map.
 */
public class MainActivity extends AppCompatActivity {

    private static final int[] LEGEND_LABEL_VIEWS = new int[]{R.id.legend_label_1,
                                                              R.id.legend_label_2,
                                                              R.id.legend_label_3,
                                                              R.id.legend_label_4,
                                                              R.id.legend_label_5};

    private static final int[] LEGEND_COLOR_VIEWS = new int[]{R.id.legend_color_1,
                                                              R.id.legend_color_2,
                                                              R.id.legend_color_3,
                                                              R.id.legend_color_4,
                                                              R.id.legend_color_5};

    private final SurveyingService.SurveyingServiceConnection mSurveyingServiceConnection
            = new SurveyingService.SurveyingServiceConnection(new SurveyingServiceListener());
    /**
     * Map hold for circles drawed on map for corresponding location.
     */
    private final Map<Location, Circle> mLocToCircleMap = new HashMap<>();

    private GoogleMap mMap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(new MapReadyCallback());

        // Show dialog only if this is new launch.
        if (savedInstanceState == null) {
            SsidPickerDialog dialog = SsidPickerDialog.newInstance(new SsidSelectedCallback());
            dialog.show(getSupportFragmentManager(), SsidPickerDialog.TAG);
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        // Start service for network surveying....
        startService(new Intent(this, SurveyingService.class));
        // ....and bound to it for getting events.
        mSurveyingServiceConnection.bound(this);
    }

    @Override
    public void onStop() {
        super.onStop();

        mSurveyingServiceConnection.unbound(this);

        // Stop service only if app is terminated.
        if (isFinishing()) {
            stopService(new Intent(this, SurveyingService.class));
        }
    }

    /**
     * Get and show last known location.
     */
    private void showLastLocation() {
        if (mSurveyingServiceConnection.getService() != null) {
            Location loc = mSurveyingServiceConnection.getService().getLastLocation();
            showLocation(loc);
        }
    }

    /**
     * Show desired location.
     *
     * @param loc Location to show.
     */
    private void showLocation(Location loc) {
        if (loc != null && mMap != null) {
            LatLng latLng = locationToLatLng(loc);
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 20));
        }
    }

    /**
     * Draw haetmap on map.
     *
     * @param heatmapData Data for drawing heatmap. RSSI on location.
     */
    private void repaintMap(Map<Location, Integer> heatmapData) {
        if (heatmapData.isEmpty()) return;

        // Count only with valid RSSI values.
        Collection<Integer> values = new ArrayList<>(heatmapData.values());
        values.removeAll(Collections.singleton(Integer.MIN_VALUE));
        if (values.isEmpty()) return;

        int maxRssi = Collections.max(values);
        int minRssi = Collections.min(values);

        for (Map.Entry<Location, Integer> entry : heatmapData.entrySet()) {
            Location loc = entry.getKey();
            Integer rssi = entry.getValue();
            Circle circle = mLocToCircleMap.get(loc);

            // If signal lost on current place remove it from map.
            if (rssi == Integer.MIN_VALUE) {
                if (circle != null) {
                    circle.remove();
                    mLocToCircleMap.remove(loc);
                }

                // This signal is lost, nothing to draw.
                continue;
            }

            // Create circle for new location.
            if (circle == null) {
                CircleOptions circleOptions = new CircleOptions().center(locationToLatLng(loc))
                                                                 .radius(getResources().getInteger(R.integer.diameter) / 2d)
                                                                 .strokeWidth(0);
                circle = mMap.addCircle(circleOptions);
                mLocToCircleMap.put(loc, circle);
            }

            // Signal strenght relative to range of currently collected RSSIs.
            // 0 = strongest signal
            // 1 = weakest signal
            double relativeSignalStrenght;
            if (minRssi - maxRssi == 0) {
                relativeSignalStrenght = 0.5;
            } else {
                relativeSignalStrenght = (rssi - maxRssi) / (double) (minRssi - maxRssi);
            }

            int color = generateColor(relativeSignalStrenght);
            if (circle.getFillColor() != color) {
                circle.setFillColor(color);
            }
        }
    }

    /**
     * Compute and show color legend for drawed data.
     * Collected RSSI range is divided on n part and each part have color corresponding to RSSI value.
     *
     * @param rssis Collection of RSSIs.
     */
    private void repaintLegend(Collection<Integer> rssis) {
        View legendLayout = findViewById(R.id.legend_layout);

        // Count only with valid RSSI values.
        Collection<Integer> values = new ArrayList<>(rssis);
        values.removeAll(Collections.singleton(Integer.MIN_VALUE));
        if (values.isEmpty()) {
            // Hide legend of no data available.
            if (legendLayout != null) {
                legendLayout.setVisibility(View.GONE);
            }
            return;
        }

        int maxRssi = Collections.max(values);
        int minRssi = Collections.min(values);

        double legendCount = LEGEND_LABEL_VIEWS.length;
        if (legendLayout != null) {
            legendLayout.setVisibility(View.VISIBLE);
            for (int i = 0; i < legendCount; i++) {
                // Divide RSSI range to parts and get color for this parts.
                int signalStrength = (int) ((minRssi - maxRssi) * i / (legendCount - 1)) + maxRssi;
                int color = generateColor(i / (legendCount - 1));

                View labelTv = legendLayout.findViewById(LEGEND_LABEL_VIEWS[i]);
                if (labelTv instanceof TextView) {
                    String label = String.format(getString(R.string.signal_strength), signalStrength);
                    ((TextView) labelTv).setText(label);
                }

                View colorView = legendLayout.findViewById(LEGEND_COLOR_VIEWS[i]);
                if (colorView != null) colorView.setBackgroundColor(color);
            }
        }
    }

    /**
     * Show info about currently surveyed network.
     *
     * @param surveyedSsid SSID of surveyed network.
     * @param lastRssi     Last RSSI for this network.
     */
    private void repaintSurveyedWiFiInfo(String surveyedSsid, int lastRssi) {
        View legendLayout = findViewById(R.id.legend_layout);
        if (legendLayout != null) {
            View currentWifiTv = legendLayout.findViewById(R.id.legend_current_wifi);
            if (currentWifiTv instanceof TextView) {
                String signalString;
                if (lastRssi == Integer.MIN_VALUE) {
                    signalString = String.format(getString(R.string.network_not_in_range), surveyedSsid);
                } else {
                    signalString = String.format(getString(R.string.current_wifi), surveyedSsid,
                                                 lastRssi);
                }
                ((TextView) currentWifiTv).setText(signalString);
            }
        }
    }

    private int generateColor(double relativeSignal) {
        int red = 0xFF;
        int green = 0xFF;
        if (relativeSignal >= 0.5) {
            // To half of range add green to red to make orange.
            green = (int) ((1 - relativeSignal) * 0xFF / 0.5);
        } else {
            // From half substract red from green to make full green.
            red = (int) ((relativeSignal) * 0xFF / 0.5);
        }

        return Color.rgb(red, green, 0);
    }

    /**
     * Helper to transform {@link Location} to {@link LatLng}.
     *
     * @param location Location to transform
     * @return Transformed location
     */
    @NonNull
    private LatLng locationToLatLng(Location location) {
        return new LatLng(location.getLatitude(), location.getLongitude());
    }

    private class SurveyingServiceListener implements SurveyingService.ServiceListener {
        @Override
        public void onWiFiScanCompleted(List<ScanResult> scanResults) {
        }

        @Override
        public void onHeatmapDataUpdated(Map<Location, Integer> heatmapData) {
            repaintLegend(heatmapData.values());
            repaintMap(heatmapData);
        }

        @Override
        public void onLastLocationUpdated(Location location) {
            showLocation(location);
        }

        @Override
        public void onSurveyedWiFiUpdated(String surveyedSsid, int rssi) {
            repaintSurveyedWiFiInfo(surveyedSsid, rssi);
        }
    }

    /**
     * Callback for SSID picker dialog.
     */
    private class SsidSelectedCallback implements SsidPickerDialog.DialogCallback {
        @Override
        public void onSsidSelected(String ssid) {
            // Start surveying selected network.
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
