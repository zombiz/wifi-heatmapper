package app.wi_fiheatmapper;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.location.Location;
import android.net.wifi.ScanResult;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.widget.ArrayAdapter;

import java.util.List;
import java.util.Map;

/**
 * Dialog for selecting SSID from surrounding networks.
 */
public class SsidPickerDialog extends DialogFragment {

    public static final String TAG = SsidPickerDialog.class.getSimpleName();

    private final SurveyingService.SurveyingServiceConnection mSurveyingServiceConnection
            = new SurveyingService.SurveyingServiceConnection(new SurveyingServiceListener());

    private DialogCallback mCallback;
    private ArrayAdapter<String> mSsidAdapter;

    public static SsidPickerDialog newInstance(DialogCallback callback) {
        SsidPickerDialog dialog = new SsidPickerDialog();
        dialog.setCallback(callback);
        return dialog;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        mSsidAdapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        return new AlertDialog.Builder(getActivity())
                .setAdapter(mSsidAdapter, new SsidSelectionListener())
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();

        mSurveyingServiceConnection.bound(getActivity());
    }

    @Override
    public void onStop() {
        super.onStop();

        mSurveyingServiceConnection.unbound(getActivity());
    }

    public void setCallback(DialogCallback callback) {
        mCallback = callback;
    }

    public interface DialogCallback {
        /**
         * Called when SSID selected from list.
         *
         * @param SSID Selected SSID
         */
        void onSsidSelected(String SSID);
    }

    private class SsidSelectionListener implements DialogInterface.OnClickListener {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (mCallback != null) {
                mCallback.onSsidSelected(mSsidAdapter.getItem(which));
            }
        }
    }

    private class SurveyingServiceListener implements SurveyingService.ServiceListener {

        @Override
        public void onWiFiScanCompleted(List<ScanResult> scanResults) {
            // Create adapter of unique SSIDs.
            mSsidAdapter.clear();
            for (ScanResult scanResult : scanResults) {
                String ssid = !TextUtils.isEmpty(scanResult.SSID) ? scanResult.SSID : scanResult.BSSID;
                if (mSsidAdapter.getPosition(ssid) < 0) {
                    mSsidAdapter.add(ssid);
                }
            }
        }

        @Override
        public void onHeatmapDataUpdated(Map<Location, Integer> heatmapData) {
        }

        @Override
        public void onLastLocationUpdated(Location lastLocation) {
        }

        @Override
        public void onSurveyedWiFiUpdated(String surveyedSsid, int rssi) {
        }
    }
}
