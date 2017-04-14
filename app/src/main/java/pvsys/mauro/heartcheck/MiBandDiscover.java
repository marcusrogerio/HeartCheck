package pvsys.mauro.heartcheck;


import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.support.v4.app.ActivityCompat;

import static pvsys.mauro.heartcheck.Callbacks.Callback;

import java.util.ArrayList;
import java.util.List;


import static android.bluetooth.le.ScanSettings.MATCH_MODE_STICKY;
import static android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY;

public class MiBandDiscover {

    public static final short RSSI_UNKNOWN = 0;
    private static final long SCAN_DURATION = 60000; // 60s



    private enum Scanning {
        SCANNING_BT,
        SCANNING_BTLE,
        SCANNING_NEW_BTLE,
        SCANNING_OFF
    }

    private static final Logger LOG = new Logger(MiBandDiscover.class.getSimpleName()).withDebug(true);
    private final Handler handler = new Handler();
    private final Activity activityContext;
    private final boolean forceBond;

    private Scanning scanningState = Scanning.SCANNING_OFF;
    private BluetoothAdapter adapter;
    private DeviceCandidate bondingDevice;
    private ScanCallback newLeScanCallback = null;

    private final Callback<DeviceCandidate> onDeviceFound;

    public MiBandDiscover(Activity activity, Callback<DeviceCandidate> onDeviceFound){
        this.activityContext = activity;
        this.forceBond = false;
        this.onDeviceFound = onDeviceFound;
    }

    public MiBandDiscover(Activity activity, Callback<DeviceCandidate> onDeviceFound, boolean forceBound){
        this.activityContext = activity;
        this.forceBond = forceBound;
        this.onDeviceFound = onDeviceFound;
    }

    public void startBT(){
        LOG.info("starting bluetooth - ");
        IntentFilter bluetoothIntents = new IntentFilter();
        bluetoothIntents.addAction(BluetoothDevice.ACTION_FOUND);
        bluetoothIntents.addAction(BluetoothDevice.ACTION_UUID);
        bluetoothIntents.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        bluetoothIntents.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        bluetoothIntents.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        bluetoothIntents.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);

        this.activityContext.registerReceiver(bluetoothReceiver, bluetoothIntents);

        LOG.info("starting discovery");
        startDiscovery();
    }


    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case BluetoothAdapter.ACTION_DISCOVERY_STARTED:
                    if (scanningState != Scanning.SCANNING_BTLE && scanningState != Scanning.SCANNING_NEW_BTLE) {
                        scanningState = Scanning.SCANNING_BT;
                        LOG.info("scanning");
                    }
                    break;
                case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            // continue with LE scan, if available
                            if (scanningState == Scanning.SCANNING_BT) {
                                //checkAndRequestLocationPermission
                                if (ActivityCompat.checkSelfPermission(
                                        activityContext.getApplicationContext(),
                                        Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                    ActivityCompat.requestPermissions(activityContext, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 0);
                                }
                                if (Utils.isRunningLollipopOrLater()) {
                                    startDiscovery(Scanning.SCANNING_NEW_BTLE);
                                } else {
                                    startDiscovery(Scanning.SCANNING_BTLE);
                                }
                            } else {
                                scanningState = Scanning.SCANNING_OFF;
                            }
                        }
                    });
                    break;
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    int oldState = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.STATE_OFF);
                    int newState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
                    bluetoothStateChanged(oldState, newState);
                    break;
                case BluetoothDevice.ACTION_FOUND: {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, RSSI_UNKNOWN);
                    handleDeviceFound(device, rssi);
                    break;
                }
                case BluetoothDevice.ACTION_UUID: {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, RSSI_UNKNOWN);
                    Parcelable[] uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
                    ParcelUuid[] uuids2 = Utils.toParcelUUids(uuids);
                    handleDeviceFound(device, rssi, uuids2);
                    break;
                }
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED: {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null && bondingDevice != null && device.getAddress().equals(bondingDevice.getMacAddress())) {
                        int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                        if (bondState == BluetoothDevice.BOND_BONDED) {
                            handleDeviceBonded();
                        }
                    }
                }
            }
        }
    };

    private void handleDeviceBonded() {
        //TODO: connect to the device: GBApplication.deviceService().connect(bondingDevice, true);
        activityContext.finish();
    }

    private final Runnable stopRunnable = new Runnable() {
        @Override
        public void run() {
            stopDiscovery();
        }
    };

    private void bluetoothStateChanged(int oldState, int newState) {
        scanningState = Scanning.SCANNING_OFF;
        if (newState == BluetoothAdapter.STATE_ON) {
            this.adapter = BluetoothAdapter.getDefaultAdapter();
        } else {
            this.adapter = null;
        }
    }

    private void startDiscovery() {
        if (isScanning()) {
            LOG.warn("Not starting discovery, because already scanning.");
            return;
        }
        startDiscovery(Scanning.SCANNING_BT);
    }

    public static boolean isBluetoothEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }

    public boolean supportsBluetoothLE() {
        return activityContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    private void startDiscovery(Scanning what) {
        LOG.info("Starting discovery: " + what);
        scanningState = what;
        if (ensureBluetoothReady()) {
            if (what == Scanning.SCANNING_BT) {
                startBTDiscovery();
            } else if (what == Scanning.SCANNING_BTLE) {
                if (supportsBluetoothLE()) {
                    startBTLEDiscovery();
                } else {
                    scanningState = Scanning.SCANNING_OFF;
                }
            } else if (what == Scanning.SCANNING_NEW_BTLE) {
                if (supportsBluetoothLE()) {
                    startNEWBTLEDiscovery();
                } else  {
                    scanningState = Scanning.SCANNING_OFF;
                }
            }
        } else {
            scanningState = Scanning.SCANNING_OFF;
            LOG.error("bluetooth not available");
        }
    }


    // why use a method to get callback?
    // because this callback need API >= 21
    // we cant add @TARGETAPI("Lollipop") at class header
    // so use a method with SDK check to return this callback
    private ScanCallback getScanCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            newLeScanCallback = new ScanCallback() {
                @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    super.onScanResult(callbackType, result);
                    try {
                        ScanRecord scanRecord = result.getScanRecord();
                        ParcelUuid[] uuids = null;
                        if (scanRecord != null) {
                            //logMessageContent(scanRecord.getBytes());
                            List<ParcelUuid> serviceUuids = scanRecord.getServiceUuids();
                            if (serviceUuids != null) {
                                uuids = serviceUuids.toArray(new ParcelUuid[0]);
                            }
                        }
                        LOG.warn(result.getDevice().getName() + ": " +
                                ((scanRecord != null) ? scanRecord.getBytes().length : -1));
                        handleDeviceFound(result.getDevice(), (short) result.getRssi(), uuids);
                    } catch (NullPointerException e) {
                        LOG.warn("Error handling scan result", e);
                    }
                }
            };
        }
        return newLeScanCallback;
    }

    private List<ScanFilter> getScanFilters() {
        //personalized for mi
        List<ScanFilter> allFilters = new ArrayList<>();

        ParcelUuid mi2Service = new ParcelUuid(MiBandService.UUID_SERVICE_MIBAND2_SERVICE);
        allFilters.add(new ScanFilter.Builder().setServiceUuid(mi2Service).build());

        ParcelUuid miService = new ParcelUuid(MiBandService.UUID_SERVICE_MIBAND_SERVICE);
        allFilters.add(new ScanFilter.Builder().setServiceUuid(miService).build());

        return allFilters;
    }

    private Message getPostMessage(Runnable runnable) {
        Message m = Message.obtain(handler, runnable);
        m.obj = runnable;
        return m;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private ScanSettings getScanSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new ScanSettings.Builder()
                    .setScanMode(SCAN_MODE_LOW_LATENCY)
                    .setMatchMode(MATCH_MODE_STICKY)
                    .build();
        } else {
            return new ScanSettings.Builder()
                    .setScanMode(SCAN_MODE_LOW_LATENCY)
                    .build();
        }
    }

    private void startBTLEDiscovery() {
        LOG.info("Starting BTLE Discovery");
        handler.removeMessages(0, stopRunnable);
        handler.sendMessageDelayed(getPostMessage(stopRunnable), SCAN_DURATION);
        adapter.startLeScan(leScanCallback);
    }

    private final BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            LOG.warn(device.getName() + ": " + ((scanRecord != null) ? scanRecord.length : -1));
            logMessageContent(scanRecord);
            handleDeviceFound(device, (short) rssi);
        }
    };

    public void logMessageContent(byte[] value) {
        if (value != null) {
            for (byte b : value) {
                LOG.warn("DATA: " + String.format("0x%2x", b) + " - " + (char) (b & 0xff));
            }
        }
    }

    private void startBTDiscovery() {
        LOG.info("Starting BT Discovery");
        handler.removeMessages(0, stopRunnable);
        handler.sendMessageDelayed(getPostMessage(stopRunnable), SCAN_DURATION);
        adapter.startDiscovery();
    }

    // New BTLE Discovery use startScan (List<ScanFilter> filters,
    //                                  ScanSettings settings,
    //                                  ScanCallback callback)
    // It's added on API21
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void startNEWBTLEDiscovery() {
        // Only use new API when user uses Lollipop+ device
        LOG.info("Start New BTLE Discovery");
        handler.removeMessages(0, stopRunnable);
        handler.sendMessageDelayed(getPostMessage(stopRunnable), SCAN_DURATION);
        adapter.getBluetoothLeScanner().startScan(getScanFilters(), getScanSettings(), getScanCallback());
    }


    private void stopDiscovery() {
        LOG.info("Stopping discovery");
        if (isScanning()) {
            Scanning wasScanning = scanningState;
            // unfortunately, we don't always get a call back when stopping the scan, so
            // we do it manually; BEFORE stopping the scan!
            scanningState  = Scanning.SCANNING_OFF;

            if (wasScanning == Scanning.SCANNING_BT) {
                stopBTDiscovery();
            } else if (wasScanning == Scanning.SCANNING_BTLE) {
                stopBTLEDiscovery();
            } else if (wasScanning == Scanning.SCANNING_NEW_BTLE) {
                stopNewBTLEDiscovery();
            }
            handler.removeMessages(0, stopRunnable);
        }
    }

    private void stopBTLEDiscovery() {
        adapter.stopLeScan(leScanCallback);
    }

    private void stopBTDiscovery() {
        adapter.cancelDiscovery();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void stopNewBTLEDiscovery() {
        adapter.getBluetoothLeScanner().stopScan(newLeScanCallback);
    }


    private boolean ensureBluetoothReady() {
        boolean available = checkBluetoothAvailable();
        if (available) {
            adapter.cancelDiscovery();
            // must not return the result of cancelDiscovery()
            // appears to return false when currently not scanning
            return true;
        }
        return false;
    }

    private boolean checkBluetoothAvailable() {
        BluetoothManager bluetoothService = (BluetoothManager) activityContext.getSystemService(activityContext.BLUETOOTH_SERVICE);
        if (bluetoothService == null) {
            LOG.warn("No bluetooth available");
            this.adapter = null;
            return false;
        }
        BluetoothAdapter adapter = bluetoothService.getAdapter();
        if (adapter == null) {
            LOG.warn("No bluetooth available");
            this.adapter = null;
            return false;
        }
        if (!adapter.isEnabled()) {
            LOG.warn("Bluetooth not enabled");
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activityContext.startActivity(enableBtIntent);
            this.adapter = null;
            return false;
        }
        this.adapter = adapter;
        return true;
    }


    private boolean isScanning() {
        return scanningState != Scanning.SCANNING_OFF;
    }


    private void handleDeviceFound(BluetoothDevice device, short rssi) {
        ParcelUuid[] uuids = device.getUuids();
        if (uuids == null) {
            if (device.fetchUuidsWithSdp()) {
                return;
            }
        }
        handleDeviceFound(device, rssi, uuids);
    }


    private void handleDeviceFound(BluetoothDevice device, short rssi, ParcelUuid[] uuids) {
        LOG.debug("found device: " + device.getName() + ", " + device.getAddress());


        DeviceCandidate candidate = new DeviceCandidate(device, rssi, uuids);
        LOG.debug("candidate: " + candidate.getName() + ", " + candidate.getDeviceType() + ", " + candidate.getMacAddress());



        if(!DeviceCandidate.DEVICE_TYPE_UNKNOWN.equals(candidate.getDeviceType())) {

            if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                LOG.info("device already bonded: " + device.getName() + ", " + device.getAddress());
                if(!forceBond) {
                    return; // ignore already bonded devices
                } else {
                    LOG.warn("Forcing bond to: " + device.getName() + ", " + device.getAddress());
                }
            }

            LOG.info("device good to be used: " + device.getName() + ", " + device.getAddress());

            stopDiscovery();
            this.onDeviceFound.call(candidate);
        }

    }
}