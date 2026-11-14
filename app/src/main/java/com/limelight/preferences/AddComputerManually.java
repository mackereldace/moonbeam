package com.limelight.preferences;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.concurrent.LinkedBlockingQueue;

import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerManagerService;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;
import com.limelight.utils.ZfwDeviceApi;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

public class AddComputerManually extends Activity {
    private TextView hostText;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    /** One host to add: the address the user picked plus the fallback for it. */
    private static final class HostEntry {
        final String address;
        final String fallback;

        HostEntry(String address, String fallback) {
            this.address = address;
            this.fallback = fallback;
        }
    }

    private final LinkedBlockingQueue<HostEntry> computersToAdd = new LinkedBlockingQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger pendingAdds =
            new java.util.concurrent.atomic.AtomicInteger();
    private Thread addThread;
    /** Filled by the campus lookup: the host's stable Tailscale address, used as the fallback. */
    private String fallbackAddress;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, final IBinder binder) {
            managerBinder = ((ComputerManagerService.ComputerManagerBinder)binder);
            startAddThread();
        }

        public void onServiceDisconnected(ComponentName className) {
            joinAddThread();
            managerBinder = null;
        }
    };

    private boolean isWrongSubnetSiteLocalAddress(String address) {
        try {
            InetAddress targetAddress = InetAddress.getByName(address);
            if (!(targetAddress instanceof Inet4Address) || !targetAddress.isSiteLocalAddress()) {
                return false;
            }

            // We have a site-local address. Look for a matching local interface.
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                    if (!(addr.getAddress() instanceof Inet4Address) || !addr.getAddress().isSiteLocalAddress()) {
                        // Skip non-site-local or non-IPv4 addresses
                        continue;
                    }

                    byte[] targetAddrBytes = targetAddress.getAddress();
                    byte[] ifaceAddrBytes = addr.getAddress().getAddress();

                    // Compare prefix to ensure it's the same
                    boolean addressMatches = true;
                    for (int i = 0; i < addr.getNetworkPrefixLength(); i++) {
                        if ((ifaceAddrBytes[i / 8] & (1 << (i % 8))) != (targetAddrBytes[i / 8] & (1 << (i % 8)))) {
                            addressMatches = false;
                            break;
                        }
                    }

                    if (addressMatches) {
                        return false;
                    }
                }
            }

            // Couldn't find a matching interface
            return true;
        } catch (Exception e) {
            // Catch all exceptions because some broken Android devices
            // will throw an NPE from inside getNetworkInterfaces().
            e.printStackTrace();
            return false;
        }
    }

    private URI parseRawUserInputToUri(String rawUserInput) {
        try {
            // Try adding a scheme and parsing the remaining input.
            // This handles input like 127.0.0.1:47989, [::1], [::1]:47989, and 127.0.0.1.
            URI uri = new URI("moonlight://" + rawUserInput);
            if (uri.getHost() != null && !uri.getHost().isEmpty()) {
                return uri;
            }
        } catch (URISyntaxException ignored) {}

        try {
            // Attempt to escape the input as an IPv6 literal.
            // This handles input like ::1.
            URI uri = new URI("moonlight://[" + rawUserInput + "]");
            if (uri.getHost() != null && !uri.getHost().isEmpty()) {
                return uri;
            }
        } catch (URISyntaxException ignored) {}

        return null;
    }

    private void doAddPc(HostEntry entry) throws InterruptedException {
        final String rawUserInput = entry.address;
        boolean wrongSiteLocal = false;
        boolean invalidInput = false;
        boolean success;
        int portTestResult;

        SpinnerDialog dialog = SpinnerDialog.displayDialog(this, getResources().getString(R.string.title_add_pc),
            getResources().getString(R.string.msg_add_pc), false);

        try {
            ComputerDetails details = new ComputerDetails();

            // Check if we parsed a host address successfully
            URI uri = parseRawUserInputToUri(rawUserInput);
            if (uri != null && uri.getHost() != null && !uri.getHost().isEmpty()) {
                String host = uri.getHost();
                int port = uri.getPort();

                // If a port was not specified, use the default
                if (port == -1) {
                    port = NvHTTP.DEFAULT_HTTP_PORT;
                }

                // Campus first, Tailscale second: the poller tries local, then manual, so the
                // native path wins when the phone is on the campus network and the tunnel is
                // only used when that address is not reachable.
                if (entry.fallback != null && !entry.fallback.isEmpty() && !entry.fallback.equals(host)) {
                    details.localAddress = new ComputerDetails.AddressTuple(host, port);
                    details.manualAddress = new ComputerDetails.AddressTuple(entry.fallback, port);
                }
                else {
                    details.manualAddress = new ComputerDetails.AddressTuple(host, port);
                }
                success = managerBinder.addComputerBlocking(details);
                if (!success){
                    wrongSiteLocal = isWrongSubnetSiteLocalAddress(host);
                }
            } else {
                // Invalid user input
                success = false;
                invalidInput = true;
            }
        } catch (InterruptedException e) {
            // Propagate the InterruptedException to the caller for proper handling
            dialog.dismiss();
            throw e;
        } catch (IllegalArgumentException e) {
            // This can be thrown from OkHttp if the host fails to canonicalize to a valid name.
            // https://github.com/square/okhttp/blob/okhttp_27/okhttp/src/main/java/com/squareup/okhttp/HttpUrl.java#L705
            e.printStackTrace();
            success = false;
            invalidInput = true;
        }

        // Keep the SpinnerDialog open while testing connectivity
        if (!success && !wrongSiteLocal && !invalidInput) {
            // Run the test before dismissing the spinner because it can take a few seconds.
            portTestResult = MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER, 443,
                    MoonBridge.ML_PORT_FLAG_TCP_47984 | MoonBridge.ML_PORT_FLAG_TCP_47989);
        } else {
            // Don't bother with the test if we succeeded or the IP address was bogus
            portTestResult = MoonBridge.ML_TEST_RESULT_INCONCLUSIVE;
        }

        dialog.dismiss();

        if (invalidInput) {
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title), getResources().getString(R.string.addpc_unknown_host), false);
        }
        else if (wrongSiteLocal) {
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title), getResources().getString(R.string.addpc_wrong_sitelocal), false);
        }
        else if (!success) {
            String dialogText;
            if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0)  {
                dialogText = getResources().getString(R.string.nettest_text_blocked);
            }
            else {
                dialogText = getResources().getString(R.string.addpc_fail);
            }
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title), dialogText, false);
        }
        else {
            AddComputerManually.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                Toast.makeText(AddComputerManually.this,
                        getResources().getString(R.string.addpc_success), Toast.LENGTH_LONG).show();
                }
            });
        }

    }

    private void startAddThread() {
        addThread = new Thread() {
            @Override
            public void run() {
                while (!isInterrupted()) {
                    try {
                        HostEntry entry = computersToAdd.take();
                        doAddPc(entry);
                        if (pendingAdds.decrementAndGet() <= 0) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (!isFinishing()) {
                                        finish();
                                    }
                                }
                            });
                        }
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        };
        addThread.setName("UI - AddComputerManually");
        addThread.start();
    }

    private void joinAddThread() {
        if (addThread != null) {
            addThread.interrupt();

            try {
                addThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();

                // InterruptedException clears the thread's interrupt status. Since we can't
                // handle that here, we will re-interrupt the thread to set the interrupt
                // status back to true.
                Thread.currentThread().interrupt();
            }

            addThread = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        Dialog.closeDialogs();
        SpinnerDialog.closeDialogs(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (managerBinder != null) {
            joinAddThread();
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UiHelper.setLocale(this);

        setContentView(R.layout.activity_add_computer_manually);

        UiHelper.notifyNewRootView(this);

        this.hostText = findViewById(R.id.hostTextView);
        hostText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        hostText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView textView, int actionId, KeyEvent keyEvent) {
                if (actionId == EditorInfo.IME_ACTION_DONE ||
                        (keyEvent != null &&
                                keyEvent.getAction() == KeyEvent.ACTION_DOWN &&
                                keyEvent.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    return handleDoneEvent();
                }
                else if (actionId == EditorInfo.IME_ACTION_PREVIOUS) {
                    // This is how the Fire TV dismisses the keyboard
                    InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(hostText.getWindowToken(), 0);
                    return false;
                }

                return false;
            }
        });

        final View lookupButton = findViewById(R.id.zfwLookupButton);
        if (ZfwDeviceApi.isConfigured(this)) {
            lookupButton.setVisibility(View.VISIBLE);
            lookupButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showCampusDevices();
                }
            });
        }

        findViewById(R.id.addPcButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                handleDoneEvent();
            }
        });

        // Bind to the ComputerManager service
        bindService(new Intent(AddComputerManually.this,
                    ComputerManagerService.class), serviceConnection, Service.BIND_AUTO_CREATE);
    }

    /**
     * Lists what the campus service sees and lets the user pick the machine, instead of asking
     * them to name it in settings beforehand: the point of the lookup is that the app knows which
     * machines exist, so requiring the name up front defeats it.
     */
    private void showCampusDevices() {
        Toast.makeText(this, R.string.zfw_lookup_running, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ZfwDeviceApi.DeviceList list = ZfwDeviceApi.listDevices(AddComputerManually.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!list.isSuccess()) {
                            Toast.makeText(AddComputerManually.this,
                                    describeZfwError(list.error), Toast.LENGTH_LONG).show();
                            return;
                        }
                        java.util.List<ZfwDeviceApi.Device> hosts = new java.util.ArrayList<>();
                        for (ZfwDeviceApi.Device device : list.devices) {
                            // Only machines that could host a stream and are reachable right now.
                            if (device.looksLikeComputer && device.online) {
                                hosts.add(device);
                            }
                        }
                        if (hosts.isEmpty()) {
                            Toast.makeText(AddComputerManually.this,
                                    R.string.zfw_no_devices, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showDeviceDialog(hosts);
                    }
                });
            }
        }, "zfw-devices").start();
    }

    /** Turns the lookup's error code into something the user can act on. */
    private String describeZfwError(String code) {
        if (ZfwDeviceApi.ERROR_NO_URL.equals(code)) {
            return getString(R.string.zfw_err_no_url);
        }
        if (ZfwDeviceApi.ERROR_NO_TOKEN.equals(code)) {
            return getString(R.string.zfw_err_no_token);
        }
        if (ZfwDeviceApi.ERROR_UNRESOLVED.equals(code)) {
            return getString(R.string.zfw_err_unresolved);
        }
        if (ZfwDeviceApi.ERROR_TIMEOUT.equals(code)) {
            return getString(R.string.zfw_err_timeout);
        }
        if (ZfwDeviceApi.ERROR_BAD_JSON.equals(code)) {
            return getString(R.string.zfw_err_bad_json);
        }
        return getString(R.string.zfw_err_http, code == null ? "?" : code);
    }

    private void showDeviceDialog(final java.util.List<ZfwDeviceApi.Device> devices) {
        final String[] labels = new String[devices.size()];
        final boolean[] checked = new boolean[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            ZfwDeviceApi.Device device = devices.get(i);
            labels[i] = device.name.isEmpty() ? device.ip : device.name + "  ·  " + device.ip;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.zfw_pick_host)
                .setMultiChoiceItems(labels, checked,
                        new android.content.DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which,
                                                boolean isChecked) {
                                checked[which] = isChecked;
                            }
                        })
                .setPositiveButton(R.string.zfw_add_selected,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                String fallback = ZfwDeviceApi.serviceHost(AddComputerManually.this);
                                int queued = 0;
                                for (int i = 0; i < devices.size(); i++) {
                                    if (!checked[i]) {
                                        continue;
                                    }
                                    pendingAdds.incrementAndGet();
                                    computersToAdd.add(new HostEntry(devices.get(i).ip, fallback));
                                    queued++;
                                }
                                if (queued == 0) {
                                    Toast.makeText(AddComputerManually.this,
                                            R.string.zfw_none_selected, Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // Returns true if the event should be eaten
    private boolean handleDoneEvent() {
        String hostAddress = hostText.getText().toString().trim();

        if (hostAddress.length() == 0) {
            Toast.makeText(AddComputerManually.this, getResources().getString(R.string.addpc_enter_ip), Toast.LENGTH_LONG).show();
            return true;
        }

        pendingAdds.incrementAndGet();
        computersToAdd.add(new HostEntry(hostAddress, fallbackAddress));
        return false;
    }
}
