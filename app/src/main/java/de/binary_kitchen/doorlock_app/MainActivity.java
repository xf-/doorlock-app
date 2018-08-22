package de.binary_kitchen.doorlock_app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.SoundPool;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.view.ContextThemeWrapper;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import de.binary_kitchen.doorlock_app.doorlock_api.ApiCommand;
import de.binary_kitchen.doorlock_app.doorlock_api.ApiErrorCode;
import de.binary_kitchen.doorlock_app.doorlock_api.DoorlockApi;
import de.binary_kitchen.doorlock_app.doorlock_api.ApiResponse;
import de.binary_kitchen.doorlock_app.doorlock_api.LockState;

import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN;

public class MainActivity extends AppCompatActivity {
    private final static String doorlock_fqdn = "lock.binary.kitchen";
    private boolean do_wifi_switch;
    private boolean connectivity;
    private DoorlockApi api;
    private TextView statusView;
    private ImageView logo;
    private SwipeRefreshLayout swipeRefreshLayout;
    private final static int POS_PERM_REQUEST = 0;
    private SoundPool sp;
    private int s_ok, s_req, s_alert;

    WifiReceiver broadcastReceiver;
    IntentFilter intentFilter;

    public MainActivity()
    {
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.mainToolbar);
        setSupportActionBar(toolbar);

        broadcastReceiver = new WifiReceiver();
        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);

        statusView = findViewById(R.id.statusTextView);
        logo = findViewById(R.id.logo);
        swipeRefreshLayout = findViewById(R.id.swiperefresh);
        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                update_status();
                swipeRefreshLayout.setRefreshing(false);
            }
        });
    }

    @Override
    protected void onResume()
    {
        String username, password;
        SharedPreferences prefs;

        super.onResume();

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        username = prefs.getString("username", "");
        password = prefs.getString("password", "");

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please enter valid credentials", Toast.LENGTH_LONG).show();
            this.startActivity(new Intent(this, SettingsActivity.class));
        }

        if (sp != null) {
            sp.release();
            sp = null;
        }

        if (prefs.getBoolean("soundsEnabled",true)) {
            sp = new SoundPool(5, AudioManager.STREAM_MUSIC, 0);
            s_req = sp.load(this, R.raw.voy_chime_2, 1);
            s_alert = sp.load(this, R.raw.alert20, 1);
            s_ok = sp.load(this, R.raw.input_ok_3_clean, 1);
        }

        api = new DoorlockApi(this, doorlock_fqdn, username, password, "kitchen");

        connectivity = false;
        do_wifi_switch = false;
        if (prefs.getBoolean("wifiSwitchEnabled", false)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                //for versions greater android 8 we need coarse position permissions to get ssid
                if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                    do_wifi_switch = true;
                    registerReceiver(broadcastReceiver, intentFilter);
                } else {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 0);
                }
            }
        } else {
            connectivity = true;
        }

        update_status();
    }

    @Override
    public void onPause()
    {
        super.onPause();

        if (broadcastReceiver != null)
            unregisterReceiver(broadcastReceiver);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        final Context ctx = this;
        final SharedPreferences prefs;
        prefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        switch(requestCode) {
            case POS_PERM_REQUEST:
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                    do_wifi_switch = true;
                else {
                    AlertDialog.Builder dialog;

                    prefs.edit().putBoolean("wifiSwitchEnabled", false).apply();

                    dialog = new AlertDialog.Builder(new ContextThemeWrapper(
                            this, R.style.Theme_AppCompat_Light_Dialog_Alert));
                    dialog.setMessage(
                            "To read the SSID of WiFis the app needs location information.");
                    dialog.setPositiveButton("Change", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface paramDialogInterface, int paramInt) {
                            prefs.edit().putBoolean("wifiSwitchEnabled", true).apply();
                            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 0);
                        }
                    });
                    dialog.setNegativeButton("No", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface paramDialogInterface, int paramInt) {
                        }
                    });

                    dialog.show();
                }
        }
    }

    private void play(int id)
    {
        if (sp != null)
            sp.play(id, 1, 1, 0, 0, 1);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        getMenuInflater().inflate(R.menu.settings_menu, menu);
        return true;
    }

    private void api_request(ApiCommand command)
    {
        if (connectivity) {
            play(s_req);
            api.issueCommand(command);
        } else {
            play(s_alert);
            Toast.makeText(this, "Error: No connectivity", Toast.LENGTH_LONG).show();
        }
    }

    private void state_unknown()
    {
        logo.setImageResource(R.drawable.ic_binary_kitchen_bw_border);
        statusView.setText("");
    }

    public void onUnlock(View view)
    {
        api_request(ApiCommand.UNLOCK);
    }

    public void onLock(View view)
    {
        api_request(ApiCommand.LOCK);
    }

    public void onError(String err)
    {
        play(s_alert);
        state_unknown();
        Toast.makeText(this, err, Toast.LENGTH_SHORT).show();
    }

    public void update_status()
    {
        if (!do_wifi_switch && broadcastReceiver != null)
            unregisterReceiver(broadcastReceiver);

        if (!connectivity && do_wifi_switch) {
            if (!switch_wifi())
                state_unknown();
        }

        if (connectivity)
            api.issueCommand(ApiCommand.STATUS);
        else
            state_unknown();
    }

    public void onUpdateStatus(ApiCommand issued_command, ApiResponse resp)
    {
        LockState state;
        ApiErrorCode err;

        err = resp.getErrorCode();
        if (err == ApiErrorCode.PERMISSION_DENIED || err == ApiErrorCode.INVALID ||
                err == ApiErrorCode.LDAP_ERROR) {
            String msg;

            msg = err.toString() + ": " + resp.getMessage();
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            state_unknown();

            return;
        }

        state = resp.getStatus();
        statusView.setText(state.toString());

        if (state == LockState.CLOSED)
            logo.setImageResource(R.drawable.ic_binary_kitchen_bw_border_closed);
        else
            logo.setImageResource(R.drawable.ic_binary_kitchen_bw_border_open);

        if (issued_command != ApiCommand.STATUS) {
            if (sp != null)
                if (err == ApiErrorCode.SUCCESS || err == ApiErrorCode.ALREADY_LOCKED ||
                        err == ApiErrorCode.ALREADY_OPEN)
                    play(s_ok);
                else
                    play(s_alert);

            Toast.makeText(this, resp.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Checks permissions and location service status to read ssids and change wifi state.
     * If permissions are not granted, request permissions.
     */
    private boolean switch_wifi() {
        List<WifiConfiguration> configured_networks;
        List<ScanResult> scan_results;
        WifiManager wifiManager;
        boolean in_range;
        int wifi_state;
        String ssid;

        wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifi_state = wifiManager.getWifiState();

        if (wifi_state == WIFI_STATE_DISABLED || wifi_state == WIFI_STATE_DISABLING ||
                wifi_state == WIFI_STATE_UNKNOWN) {
            wifiManager.setWifiEnabled(true);
        } else {
            /* Are we already connected to some kitchen network? */
            ssid = wifiManager.getConnectionInfo().getSSID();
            if (is_ssid_valid(ssid)) {
                connectivity = true;
                return true;
            }
            wifiManager.disconnect();
        }

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {

        }

        /* Let's see if any kitchen network is actually in range */
        in_range = false;
        scan_results = wifiManager.getScanResults();
        if (scan_results != null)
            for (ScanResult scan_result: scan_results)
                if (scan_result.SSID.contains(".binary-kitchen.de")) {
                    in_range = true;
                    break;
                }

        if (!in_range) {
            Toast.makeText(this,
                    "Couldn't find valid WiFi. Maybe kitchen out of range?",
                    Toast.LENGTH_LONG).show();
            return false;
        }

        configured_networks = wifiManager.getConfiguredNetworks();
        if (configured_networks == null)
            return false;

        /*
         * First step: search if user has secure.binary.kitchen configured. Prefer this network over
         * others
         */
        for (WifiConfiguration networkConf: configured_networks)
            if (networkConf.SSID.equals("\"secure.binary-kitchen.de\"")) {
                wifiManager.enableNetwork(networkConf.networkId, true);
                return true;
            }


        /* Second step: Fall back to legacy.binary.kitchen */
        for (WifiConfiguration networkConf: configured_networks)
            if (networkConf.SSID.equals("\"legacy.binary-kitchen.de\"")) {
                wifiManager.enableNetwork(networkConf.networkId, true);
                return true;
            }

        Toast.makeText(this,
                "Unable to connect: Kitchen WiFi not configured",
                Toast.LENGTH_LONG).show();
        return false;
    }

    boolean is_ssid_valid(String ssid)
    {
        return ssid != null
                && (ssid.equals("\"legacy.binary-kitchen.de\"")
                || ssid.equals("\"secure.binary-kitchen.de\""));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        switch(item.getItemId()){
            case R.id.settingsMenuSettingsItem:
                this.startActivity(new Intent(this, SettingsActivity.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public class WifiReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            ConnectivityManager connManager;
            WifiManager wifiManager;
            NetworkInfo mWifi;
            WifiInfo wifiInfo;
            String ssid;

            connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            wifiManager = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);

            mWifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            wifiInfo = wifiManager.getConnectionInfo();
            ssid = wifiInfo.getSSID();

            connectivity = is_ssid_valid(ssid) && mWifi.isConnected();
            update_status();
        }
    };
}