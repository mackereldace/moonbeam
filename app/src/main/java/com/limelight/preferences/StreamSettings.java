package com.limelight.preferences;

import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.widget.Toast;
import com.limelight.utils.ConfigurationPort;
import java.io.IOException;
import java.io.InputStream;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.MediaCodecInfo;
import android.os.Build;
import android.os.Bundle;
import android.app.Activity;
import android.os.Handler;
import android.os.Vibrator;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.util.DisplayMetrics;
import android.util.Range;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import com.limelight.LimeLog;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.UiHelper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class StreamSettings extends Activity {
    private PreferenceConfiguration previousPrefs;
    private int previousDisplayPixelCount;

    // HACK for Android 9
    static DisplayCutout displayCutoutP;

    void reloadSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode mode = getWindowManager().getDefaultDisplay().getMode();
            previousDisplayPixelCount = mode.getPhysicalWidth() * mode.getPhysicalHeight();
        }
        getFragmentManager().beginTransaction().replace(
                R.id.stream_settings, new SettingsFragment()
        ).commitAllowingStateLoss();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        previousPrefs = PreferenceConfiguration.readPreferences(this);

        UiHelper.setLocale(this);

        setContentView(R.layout.activity_stream_settings);

        UiHelper.notifyNewRootView(this);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        // We have to use this hack on Android 9 because we don't have Display.getCutout()
        // which was added in Android 10.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
            // Insets can be null when the activity is recreated on screen rotation
            // https://stackoverflow.com/questions/61241255/windowinsets-getdisplaycutout-is-null-everywhere-except-within-onattachedtowindo
            WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) {
                displayCutoutP = insets.getDisplayCutout();
            }
        }

        reloadSettings();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode mode = getWindowManager().getDefaultDisplay().getMode();

            // If the display's physical pixel count has changed, we consider that it's a new display
            // and we should reload our settings (which include display-dependent values).
            //
            // NB: We aren't using displayId here because that stays the same (DEFAULT_DISPLAY) when
            // switching between screens on a foldable device.
            if (mode.getPhysicalWidth() * mode.getPhysicalHeight() != previousDisplayPixelCount) {
                reloadSettings();
            }
        }
    }

    @Override
    // NOTE: This will NOT be called on Android 13+ with android:enableOnBackInvokedCallback="true"
    public void onBackPressed() {
        finish();

        // Language changes are handled via configuration changes in Android 13+,
        // so manual activity relaunching is no longer required.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            PreferenceConfiguration newPrefs = PreferenceConfiguration.readPreferences(this);
            if (!newPrefs.language.equals(previousPrefs.language)) {
                // Restart the PC view to apply UI changes
                Intent intent = new Intent(this, PcView.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent, null);
            }
        }
    }

    public static class SettingsFragment extends PreferenceFragment {
        /** Request code for picking a Moonlight V+ backup file. */
        private static final int REQUEST_IMPORT_VPLUS_BACKUP = 0x5650;

        /** Backup bytes buffered between the file picker and the password prompt. */
        private byte[] pendingDocument;
        private static final String RESTORE_BACKUP_KEY = "restore_vplus_backup";

        private int nativeResolutionStartIndex = Integer.MAX_VALUE;
        private boolean nativeFramerateShown = false;

        private void setValue(String preferenceKey, String value) {
            ListPreference pref = (ListPreference) findPreference(preferenceKey);

            pref.setValue(value);
        }

        private void appendPreferenceEntry(ListPreference pref, String newEntryName, String newEntryValue) {
            CharSequence[] newEntries = Arrays.copyOf(pref.getEntries(), pref.getEntries().length + 1);
            CharSequence[] newValues = Arrays.copyOf(pref.getEntryValues(), pref.getEntryValues().length + 1);

            // Add the new option
            newEntries[newEntries.length - 1] = newEntryName;
            newValues[newValues.length - 1] = newEntryValue;

            pref.setEntries(newEntries);
            pref.setEntryValues(newValues);
        }

        private void addNativeResolutionEntry(int nativeWidth, int nativeHeight, boolean insetsRemoved, boolean portrait) {
            ListPreference pref = (ListPreference) findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING);

            String newName;

            if (insetsRemoved) {
                newName = getResources().getString(R.string.resolution_prefix_native_fullscreen);
            }
            else {
                newName = getResources().getString(R.string.resolution_prefix_native);
            }

            if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
                if (portrait) {
                    newName += " " + getResources().getString(R.string.resolution_prefix_native_portrait);
                }
                else {
                    newName += " " + getResources().getString(R.string.resolution_prefix_native_landscape);
                }
            }

            newName += " ("+nativeWidth+"x"+nativeHeight+")";

            String newValue = nativeWidth+"x"+nativeHeight;

            // Check if the native resolution is already present
            for (CharSequence value : pref.getEntryValues()) {
                if (newValue.equals(value.toString())) {
                    // It is present in the default list, so don't add it again
                    return;
                }
            }

            if (pref.getEntryValues().length < nativeResolutionStartIndex) {
                nativeResolutionStartIndex = pref.getEntryValues().length;
            }
            appendPreferenceEntry(pref, newName, newValue);
        }

        private void addNativeResolutionEntries(int nativeWidth, int nativeHeight, boolean insetsRemoved) {
            if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
                addNativeResolutionEntry(nativeHeight, nativeWidth, insetsRemoved, true);
            }
            addNativeResolutionEntry(nativeWidth, nativeHeight, insetsRemoved, false);
        }

        private void addNativeFrameRateEntry(float framerate) {
            int frameRateRounded = Math.round(framerate);
            if (frameRateRounded == 0) {
                return;
            }

            ListPreference pref = (ListPreference) findPreference(PreferenceConfiguration.FPS_PREF_STRING);
            String fpsValue = Integer.toString(frameRateRounded);
            String fpsName = getResources().getString(R.string.resolution_prefix_native) +
                    " (" + fpsValue + " " + getResources().getString(R.string.fps_suffix_fps) + ")";

            // Check if the native frame rate is already present
            for (CharSequence value : pref.getEntryValues()) {
                if (fpsValue.equals(value.toString())) {
                    // It is present in the default list, so don't add it again
                    nativeFramerateShown = false;
                    return;
                }
            }

            appendPreferenceEntry(pref, fpsName, fpsValue);
            nativeFramerateShown = true;
        }

        private void removeValue(String preferenceKey, String value, Runnable onMatched) {
            int matchingCount = 0;

            ListPreference pref = (ListPreference) findPreference(preferenceKey);

            // Count the number of matching entries we'll be removing
            for (CharSequence seq : pref.getEntryValues()) {
                if (seq.toString().equalsIgnoreCase(value)) {
                    matchingCount++;
                }
            }

            // Create the new arrays
            CharSequence[] entries = new CharSequence[pref.getEntries().length-matchingCount];
            CharSequence[] entryValues = new CharSequence[pref.getEntryValues().length-matchingCount];
            int outIndex = 0;
            for (int i = 0; i < pref.getEntryValues().length; i++) {
                if (pref.getEntryValues()[i].toString().equalsIgnoreCase(value)) {
                    // Skip matching values
                    continue;
                }

                entries[outIndex] = pref.getEntries()[i];
                entryValues[outIndex] = pref.getEntryValues()[i];
                outIndex++;
            }

            if (pref.getValue().equalsIgnoreCase(value)) {
                onMatched.run();
            }

            // Update the preference with the new list
            pref.setEntries(entries);
            pref.setEntryValues(entryValues);
        }

        private void resetBitrateToDefault(SharedPreferences prefs, String res, String fps) {
            if (res == null) {
                res = prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION);
            }
            if (fps == null) {
                fps = prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS);
            }

            prefs.edit()
                    .putInt(PreferenceConfiguration.BITRATE_PREF_STRING,
                            PreferenceConfiguration.getDefaultBitrate(res, fps))
                    .apply();
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = super.onCreateView(inflater, container, savedInstanceState);
            UiHelper.applyStatusBarPadding(view);
            return view;
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            addPreferencesFromResource(R.xml.preferences);
            PreferenceScreen screen = getPreferenceScreen();

            // hide on-screen controls category on non touch screen devices
            if (!getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_onscreen_controls");
                screen.removePreference(category);
            }

            // Hide remote desktop mouse mode on pre-Oreo (which doesn't have pointer capture)
            // and NVIDIA SHIELD devices (which support raw mouse input in pointer capture mode)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    getActivity().getPackageManager().hasSystemFeature("com.nvidia.feature.shield")) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_input_settings");
                category.removePreference(findPreference("checkbox_absolute_mouse_mode"));
            }

            // Hide gamepad motion sensor option when running on OSes before Android 12.
            // Support for motion, LED, battery, and other extensions were introduced in S.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_gamepad_settings");
                category.removePreference(findPreference("checkbox_gamepad_motion_sensors"));
            }

            // Hide gamepad motion sensor fallback option if the device has no gyro or accelerometer
            if (!getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER) &&
                    !getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_gamepad_settings");
                category.removePreference(findPreference("checkbox_gamepad_motion_fallback"));
            }

            // Hide USB driver options on devices without USB host support
            if (!getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_gamepad_settings");
                category.removePreference(findPreference("checkbox_usb_bind_all"));
                category.removePreference(findPreference("checkbox_usb_driver"));
            }

            // Remove PiP mode on devices pre-Oreo, where the feature is not available (some low RAM devices),
            // and on Fire OS where it violates the Amazon App Store guidelines for some reason.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    !getActivity().getPackageManager().hasSystemFeature("android.software.picture_in_picture") ||
                    getActivity().getPackageManager().hasSystemFeature("com.amazon.software.fireos")) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_ui_settings");
                category.removePreference(findPreference("checkbox_enable_pip"));
            }

            // Fire TV apps are not allowed to use WebViews or browsers, so hide the Help category
            /*if (getActivity().getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_help");
                screen.removePreference(category);
            }*/
            PreferenceCategory category_gamepad_settings =
                    (PreferenceCategory) findPreference("category_gamepad_settings");
            // Remove the vibration options if the device can't vibrate
            if (!((Vibrator)getActivity().getSystemService(Context.VIBRATOR_SERVICE)).hasVibrator()) {
                category_gamepad_settings.removePreference(findPreference("checkbox_vibrate_fallback"));
                category_gamepad_settings.removePreference(findPreference("seekbar_vibrate_fallback_strength"));
                // The entire OSC category may have already been removed by the touchscreen check above
                PreferenceCategory category = (PreferenceCategory) findPreference("category_onscreen_controls");
                if (category != null) {
                    category.removePreference(findPreference("checkbox_vibrate_osc"));
                }
            }
            else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    !((Vibrator)getActivity().getSystemService(Context.VIBRATOR_SERVICE)).hasAmplitudeControl() ) {
                // Remove the vibration strength selector of the device doesn't have amplitude control
                category_gamepad_settings.removePreference(findPreference("seekbar_vibrate_fallback_strength"));
            }

            Display display = getActivity().getWindowManager().getDefaultDisplay();
            float maxSupportedFps = display.getRefreshRate();

            // Hide non-supported resolution/FPS combinations
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int maxSupportedResW = 0;

                // Add a native resolution with any insets included for users that don't want content
                // behind the notch of their display
                boolean hasInsets = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    DisplayCutout cutout;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Use the much nicer Display.getCutout() API on Android 10+
                        cutout = display.getCutout();
                    }
                    else {
                        // Android 9 only
                        cutout = displayCutoutP;
                    }

                    if (cutout != null) {
                        int widthInsets = cutout.getSafeInsetLeft() + cutout.getSafeInsetRight();
                        int heightInsets = cutout.getSafeInsetBottom() + cutout.getSafeInsetTop();

                        if (widthInsets != 0 || heightInsets != 0) {
                            DisplayMetrics metrics = new DisplayMetrics();
                            display.getRealMetrics(metrics);

                            int width = Math.max(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets);
                            int height = Math.min(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets);

                            addNativeResolutionEntries(width, height, false);
                            hasInsets = true;
                        }
                    }
                }

                // Always allow resolutions that are smaller or equal to the active
                // display resolution because decoders can report total non-sense to us.
                // For example, a p201 device reports:
                // AVC Decoder: OMX.amlogic.avc.decoder.awesome
                // HEVC Decoder: OMX.amlogic.hevc.decoder.awesome
                // AVC supported width range: 64 - 384
                // HEVC supported width range: 64 - 544
                for (Display.Mode candidate : display.getSupportedModes()) {
                    // Some devices report their dimensions in the portrait orientation
                    // where height > width. Normalize these to the conventional width > height
                    // arrangement before we process them.

                    int width = Math.max(candidate.getPhysicalWidth(), candidate.getPhysicalHeight());
                    int height = Math.min(candidate.getPhysicalWidth(), candidate.getPhysicalHeight());

                    // Some TVs report strange values here, so let's avoid native resolutions on a TV
                    // unless they report greater than 4K resolutions.
                    if (!getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                            (width > 3840 || height > 2160)) {
                        addNativeResolutionEntries(width, height, hasInsets);
                    }

                    if ((width >= 3840 || height >= 2160) && maxSupportedResW < 3840) {
                        maxSupportedResW = 3840;
                    }
                    else if ((width >= 2560 || height >= 1440) && maxSupportedResW < 2560) {
                        maxSupportedResW = 2560;
                    }
                    else if ((width >= 1920 || height >= 1080) && maxSupportedResW < 1920) {
                        maxSupportedResW = 1920;
                    }

                    if (candidate.getRefreshRate() > maxSupportedFps) {
                        maxSupportedFps = candidate.getRefreshRate();
                    }
                }

                // This must be called to do runtime initialization before calling functions that evaluate
                // decoder lists.
                MediaCodecHelper.initialize(getContext(), GlPreferences.readPreferences(getContext()).glRenderer);

                MediaCodecInfo avcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/avc", -1);
                MediaCodecInfo hevcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1);

                if (avcDecoder != null) {
                    Range<Integer> avcWidthRange = avcDecoder.getCapabilitiesForType("video/avc").getVideoCapabilities().getSupportedWidths();

                    LimeLog.info("AVC supported width range: "+avcWidthRange.getLower()+" - "+avcWidthRange.getUpper());

                    // If 720p is not reported as supported, ignore all results from this API
                    if (avcWidthRange.contains(1280)) {
                        if (avcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                            maxSupportedResW = 3840;
                        }
                        else if (avcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                            maxSupportedResW = 1920;
                        }
                        else if (maxSupportedResW < 1280) {
                            maxSupportedResW = 1280;
                        }
                    }
                }

                if (hevcDecoder != null) {
                    Range<Integer> hevcWidthRange = hevcDecoder.getCapabilitiesForType("video/hevc").getVideoCapabilities().getSupportedWidths();

                    LimeLog.info("HEVC supported width range: "+hevcWidthRange.getLower()+" - "+hevcWidthRange.getUpper());

                    // If 720p is not reported as supported, ignore all results from this API
                    if (hevcWidthRange.contains(1280)) {
                        if (hevcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                            maxSupportedResW = 3840;
                        }
                        else if (hevcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                            maxSupportedResW = 1920;
                        }
                        else if (maxSupportedResW < 1280) {
                            maxSupportedResW = 1280;
                        }
                    }
                }

                LimeLog.info("Maximum resolution slot: "+maxSupportedResW);

                if (maxSupportedResW != 0) {
                    if (maxSupportedResW < 3840) {
                        // 4K is unsupported
                        removeValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_4K, new Runnable() {
                            @Override
                            public void run() {
                                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                                setValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1440P);
                                resetBitrateToDefault(prefs, null, null);
                            }
                        });
                    }
                    if (maxSupportedResW < 2560) {
                        // 1440p is unsupported
                        removeValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1440P, new Runnable() {
                            @Override
                            public void run() {
                                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                                setValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1080P);
                                resetBitrateToDefault(prefs, null, null);
                            }
                        });
                    }
                    if (maxSupportedResW < 1920) {
                        // 1080p is unsupported
                        removeValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1080P, new Runnable() {
                            @Override
                            public void run() {
                                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                                setValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_720P);
                                resetBitrateToDefault(prefs, null, null);
                            }
                        });
                    }
                    // Never remove 720p
                }
            }
            else {
                // We can get the true metrics via the getRealMetrics() function (unlike the lies
                // that getWidth() and getHeight() tell to us).
                DisplayMetrics metrics = new DisplayMetrics();
                display.getRealMetrics(metrics);
                int width = Math.max(metrics.widthPixels, metrics.heightPixels);
                int height = Math.min(metrics.widthPixels, metrics.heightPixels);
                addNativeResolutionEntries(width, height, false);
            }

            if (!PreferenceConfiguration.readPreferences(this.getActivity()).unlockFps) {
                // We give some extra room in case the FPS is rounded down
                if (maxSupportedFps < 118) {
                    removeValue(PreferenceConfiguration.FPS_PREF_STRING, "120", new Runnable() {
                        @Override
                        public void run() {
                            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                            setValue(PreferenceConfiguration.FPS_PREF_STRING, "90");
                            resetBitrateToDefault(prefs, null, null);
                        }
                    });
                }
                if (maxSupportedFps < 88) {
                    // 1080p is unsupported
                    removeValue(PreferenceConfiguration.FPS_PREF_STRING, "90", new Runnable() {
                        @Override
                        public void run() {
                            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                            setValue(PreferenceConfiguration.FPS_PREF_STRING, "60");
                            resetBitrateToDefault(prefs, null, null);
                        }
                    });
                }
                // Never remove 30 FPS or 60 FPS
            }
            addNativeFrameRateEntry(maxSupportedFps);

            // Android L introduces the drop duplicate behavior of releaseOutputBuffer()
            // that the unlock FPS option relies on to not massively increase latency.
            findPreference(PreferenceConfiguration.UNLOCK_FPS_STRING).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    // HACK: We need to let the preference change succeed before reinitializing to ensure
                    // it's reflected in the new layout.
                    final Handler h = new Handler();
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            // Ensure the activity is still open when this timeout expires
                            StreamSettings settingsActivity = (StreamSettings) SettingsFragment.this.getActivity();
                            if (settingsActivity != null) {
                                settingsActivity.reloadSettings();
                            }
                        }
                    }, 500);

                    // Allow the original preference change to take place
                    return true;
                }
            });

            // Remove HDR preference for devices below Nougat
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                LimeLog.info("Excluding HDR toggle based on OS");
                removeHdrPreferences();
            }
            else {
                Display.HdrCapabilities hdrCaps = display.getHdrCapabilities();

                // We must now ensure our display is compatible with HDR10
                boolean foundHdr10 = false;
                if (hdrCaps != null) {
                    // getHdrCapabilities() returns null on Lenovo Lenovo Mirage Solo (vega), Android 8.0
                    for (int hdrType : hdrCaps.getSupportedHdrTypes()) {
                        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                            foundHdr10 = true;
                            break;
                        }
                    }
                }

                if (!foundHdr10) {
                    LimeLog.info("Excluding HDR toggle based on display capabilities");
                    removeHdrPreferences();
                }
                else if (PreferenceConfiguration.isShieldAtvFirmwareWithBrokenHdr()) {
                    LimeLog.info("Disabling HDR toggle on old broken SHIELD TV firmware");
                    PreferenceCategory category =
                            (PreferenceCategory) findPreference("category_advanced_settings");
                    CheckBoxPreference hdrPref = (CheckBoxPreference) category.findPreference("checkbox_enable_hdr");
                    hdrPref.setEnabled(false);
                    hdrPref.setChecked(false);
                    hdrPref.setSummary(R.string.hdr_shield_update);
                }
            }

            // Show what this display reports, and let the user inspect the whole picture.
            // The report is read-only on purpose: the numbers are the system's claim, and the
            // only knob worth exposing is the manual peak below it.
            Preference hdrReport = findPreference("hdr_brightness_report");
            if (hdrReport != null) {
                final com.limelight.utils.HdrDisplayCapabilities capabilities =
                        com.limelight.utils.HdrDisplayCapabilities.probe(getActivity());
                hdrReport.setSummary(capabilities.summary(getActivity()));
                hdrReport.setOnPreferenceClickListener(preference -> {
                    new android.app.AlertDialog.Builder(getActivity())
                            .setTitle(R.string.hdr_detail_title)
                            .setMessage(capabilities.detail(getActivity()))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return true;
                });
                LimeLog.info("HDR display: " + capabilities.summary(getActivity()).replace('\n', ' '));
            }

            Preference hdrProfiles = findPreference("hdr_profiles");
            if (hdrProfiles != null) {
                final com.limelight.utils.HdrDisplayCapabilities caps =
                        com.limelight.utils.HdrDisplayCapabilities.probe(getActivity());
                hdrProfiles.setSummary(getResources().getQuantityString(
                        R.plurals.hdr_profiles_summary, com.limelight.utils.HdrProfileStore.SLOT_COUNT));
                hdrProfiles.setOnPreferenceClickListener(preference -> {
                    new android.app.AlertDialog.Builder(getActivity())
                            .setTitle(R.string.title_hdr_profiles)
                            .setMessage(com.limelight.utils.HdrProfileStore.describe(getActivity(), caps))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return true;
                });
            }

            Preference hdrTest = findPreference("hdr_display_test");
            if (hdrTest != null) {
                hdrTest.setOnPreferenceClickListener(preference -> {
                    startActivity(new Intent(getActivity(), com.limelight.HdrTestActivity.class));
                    return true;
                });
            }

            // Add a listener to the FPS and resolution preference
            // so the bitrate can be auto-adjusted
            findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                    String valueStr = (String) newValue;

                    // Detect if this value is the native resolution option
                    CharSequence[] values = ((ListPreference)preference).getEntryValues();
                    boolean isNativeRes = true;
                    for (int i = 0; i < values.length; i++) {
                        // Look for a match prior to the start of the native resolution entries
                        if (valueStr.equals(values[i].toString()) && i < nativeResolutionStartIndex) {
                            isNativeRes = false;
                            break;
                        }
                    }

                    // If this is native resolution, show the warning dialog
                    if (isNativeRes) {
                        Dialog.displayDialog(getActivity(),
                                getResources().getString(R.string.title_native_res_dialog),
                                getResources().getString(R.string.text_native_res_dialog),
                                false);
                    }

                    // Write the new bitrate value
                    resetBitrateToDefault(prefs, valueStr, null);

                    // Allow the original preference change to take place
                    return true;
                }
            });
            findPreference(PreferenceConfiguration.FPS_PREF_STRING).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                    String valueStr = (String) newValue;

                    // If this is native frame rate, show the warning dialog
                    CharSequence[] values = ((ListPreference)preference).getEntryValues();
                    if (nativeFramerateShown && values[values.length - 1].toString().equals(newValue.toString())) {
                        Dialog.displayDialog(getActivity(),
                                getResources().getString(R.string.title_native_fps_dialog),
                                getResources().getString(R.string.text_native_res_dialog),
                                false);
                    }

                    // Write the new bitrate value
                    resetBitrateToDefault(prefs, null, valueStr);

                    // Allow the original preference change to take place
                    return true;
                }
            });

            Preference restoreBackup = findPreference(RESTORE_BACKUP_KEY);
            if (restoreBackup != null) {
                restoreBackup.setOnPreferenceClickListener(preference -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    try {
                        startActivityForResult(intent, REQUEST_IMPORT_VPLUS_BACKUP);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(getActivity(), R.string.toast_no_file_picker, Toast.LENGTH_LONG).show();
                    }
                    return true;
                });
            }

            // Everything that needs the original XML hierarchy is done. Rebuild it now.
            try {
                applyCategoryLayout(getPreferenceScreen());
            }
            catch (RuntimeException e) {
                // A layout experiment must not be able to take the whole settings screen down:
                // 0.4.0 did exactly that by adding entries to a category that was not attached
                // yet. Swallow it, keep whatever hierarchy exists, and leave a trace.
                LimeLog.warning("settings layout failed, keeping the inflated hierarchy: " + e);
            }
        }

        /** Keys of the six top-level categories, in display order. */
        private static final String[] CATEGORY_KEYS = {
                "cat_picture", "cat_audio", "cat_latency", "cat_input", "cat_network", "cat_debug"
        };

        /** Titles matching {@link #CATEGORY_KEYS}, index for index. */
        private static final int[] CATEGORY_TITLES = {
                R.string.category_picture_settings,
                R.string.category_audio_settings,
                R.string.category_latency_settings,
                R.string.category_input_settings,
                R.string.category_network_settings,
                R.string.category_debug_settings,
        };

        /**
         * Drop every HDR-related entry when this device cannot stream HDR.
         *
         * They are removed by key rather than by parent category, because each of them lives in
         * its own group in the XML and the capability checks run before the layout is rebuilt.
         */
        private void removeHdrPreferences() {
            for (String key : new String[] {
                    "checkbox_enable_hdr",
                    "checkbox_hdr_high_brightness",
                    "hdr_brightness_report",
                    "hdr_display_test",
                    "hdr_profiles",
                    "list_hdr_profile_source",
            }) {
                Preference preference = findPreference(key);
                if (preference != null && preference.getParent() != null) {
                    preference.getParent().removePreference(preference);
                }
            }
        }

        /**
         * Where each preference belongs in the refactored layout.
         *
         * Columns: preference key and category index into {@link #CATEGORY_KEYS}. Row order
         * within a category is the display order, so a switch that others depend on has to come
         * before its dependents.
         *
         * The keys are the ones stored in SharedPreferences and the ones the V+ backup importer
         * maps. Do not rename them; add or remove rows instead.
         */
        private static final String[][] PREF_LAYOUT = {
                // Picture - HDR first, then the rest of the picture pipeline
                {"checkbox_enable_hdr", "0"},
                {"checkbox_hdr_high_brightness", "0"},
                {"hdr_brightness_report", "0"},
                {"hdr_display_test", "0"},
                {"hdr_profiles", "0"},
                {"text_sunshine_token", "4"},
                {"text_zfw_api_url", "4"},
                {"text_zfw_api_token", "4"},
                {"list_hdr_profile_source", "0"},
                {"list_resolution", "0"},
                {"list_fps", "0"},
                {"checkbox_stretch_video", "0"},
                {"video_format", "0"},
                {"checkbox_full_range", "0"},
                {"checkbox_enable_sops", "0"},

                // Audio
                {"list_audio_config", "1"},
                {"checkbox_host_audio", "1"},
                {"checkbox_enable_audiofx", "1"},

                // Latency
                {"frame_pacing", "2"},
                {"checkbox_reduce_refresh_rate", "2"},
                {"checkbox_unlock_fps", "2"},
                {"checkbox_enable_perf_overlay", "2"},
                {"list_perf_overlay_mode", "2"},
                {"checkbox_enable_post_stream_toast", "2"},

                // Input - touch and mouse, then on-screen controls, then gamepads
                {"checkbox_touchscreen_trackpad", "3"},
                {"checkbox_mouse_nav_buttons", "3"},
                {"checkbox_absolute_mouse_mode", "3"},
                {"checkbox_show_onscreen_controls", "3"},
                {"checkbox_vibrate_osc", "3"},
                {"checkbox_only_show_L3R3", "3"},
                {"checkbox_show_guide_button", "3"},
                {"seekbar_osc_opacity", "3"},
                {"seekbar_deadzone", "3"},
                {"checkbox_multi_controller", "3"},
                {"checkbox_usb_driver", "3"},
                {"checkbox_usb_bind_all", "3"},
                {"checkbox_mouse_emulation", "3"},
                {"analog_scrolling", "3"},
                {"checkbox_vibrate_fallback", "3"},
                {"seekbar_vibrate_fallback_strength", "3"},
                {"checkbox_flip_face_buttons", "3"},
                {"checkbox_gamepad_touchpad_as_mouse", "3"},
                {"checkbox_gamepad_motion_sensors", "3"},
                {"checkbox_gamepad_motion_fallback", "3"},

                // Network
                {"seekbar_bitrate_kbps", "4"},
                {"checkbox_adaptive_bitrate", "4"},

                // Debug
                {"restore_vplus_backup", "5"},
                {"checkbox_disable_warnings", "5"},
                {"checkbox_enable_pip", "5"},
                {"list_languages", "5"},
                {"checkbox_small_icon_mode", "5"},
        };

        /**
         * Section labels inside a category, inserted immediately before the anchor row.
         *
         * Columns: category index, anchor preference key, title. A label only appears when its
         * anchor survived the capability checks, so a device without a touchscreen does not get
         * an "on-screen controls" heading with nothing under it.
         *
         * A nested PreferenceCategory would be the obvious way to do this, but the framework
         * flattens those, and a nested PreferenceScreen is what broke the status bar in 0.4.1.
         * A plain non-selectable Preference with its own layout avoids both.
         */
        private static final String[][] SECTION_HEADERS = {
                {"3", "checkbox_touchscreen_trackpad", "section_input_touch"},
                {"3", "checkbox_show_onscreen_controls", "section_input_onscreen"},
                {"3", "seekbar_deadzone", "section_input_gamepad"},
        };

        private static int sectionTitleRes(String name) {
            switch (name) {
                case "section_input_touch":
                    return R.string.section_input_touch;
                case "section_input_onscreen":
                    return R.string.section_input_onscreen;
                case "section_input_gamepad":
                    return R.string.section_input_gamepad;
                default:
                    return 0;
            }
        }

        /**
         * Rebuild the inflated preference screen as six flat categories.
         *
         * The XML stays the single source of truth for every preference, its key and its
         * default; this only moves the inflated objects into a new hierarchy. Moving the objects
         * (rather than rewriting the XML) keeps every listener registered earlier in
         * {@link #onCreate} attached, and lets the capability checks keep working against the
         * original category keys.
         *
         * Two rules earned the hard way:
         *  - a group must be attached to the screen before anything is put into it, because
         *    PreferenceGroup hands its own PreferenceManager down to the child and a detached
         *    group has none (0.4.0 crashed on open because of this);
         *  - no nested PreferenceScreen. The framework renders one as its own dialog window,
         *    which ignores the activity's inset padding and draws over the status bar (0.4.1
         *    shipped that). A flat list has neither problem.
         */
        private void applyCategoryLayout(PreferenceScreen screen) {
            if (screen == null) {
                return;
            }

            // Remember the old top-level groups so they can be dropped once they are empty.
            List<Preference> oldGroups = new ArrayList<>();
            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                oldGroups.add(screen.getPreference(i));
            }

            PreferenceCategory[] categories = new PreferenceCategory[CATEGORY_KEYS.length];
            int[] nextOrder = new int[CATEGORY_KEYS.length];

            // Attach first, fill second - see the class comment above.
            for (int i = 0; i < CATEGORY_KEYS.length; i++) {
                PreferenceCategory category = new PreferenceCategory(screen.getContext());
                category.setKey(CATEGORY_KEYS[i]);
                category.setTitle(CATEGORY_TITLES[i]);
                category.setOrder(i);
                screen.addPreference(category);
                categories[i] = category;
            }

            int moved = 0;
            int missing = 0;
            int unattached = 0;

            for (String[] row : PREF_LAYOUT) {
                Preference preference = findPreference(row[0]);
                if (preference == null) {
                    LimeLog.warning("settings layout: key not present, skipped: " + row[0] + " (removed by a capability check?)");
                    missing++;
                    continue;
                }

                int categoryIndex = Integer.parseInt(row[1]);

                // A section label sits right before its anchor row.
                for (String[] header : SECTION_HEADERS) {
                    if (Integer.parseInt(header[0]) == categoryIndex && header[1].equals(row[0])) {
                        Preference label = new Preference(screen.getContext());
                        label.setLayoutResource(R.layout.preference_section_header);
                        label.setSelectable(false);
                        label.setTitle(sectionTitleRes(header[2]));
                        label.setOrder(nextOrder[categoryIndex]++);
                        categories[categoryIndex].addPreference(label);
                    }
                }

                PreferenceGroup parent = preference.getParent();
                if (parent != null) {
                    parent.removePreference(preference);
                }

                preference.setOrder(nextOrder[categoryIndex]++);
                categories[categoryIndex].addPreference(preference);

                if (preference.getPreferenceManager() == null) {
                    unattached++;
                }
                moved++;
            }

            // The old groups are empty now; leaving them would show stray headers.
            for (Preference oldGroup : oldGroups) {
                screen.removePreference(oldGroup);
            }

            if (unattached != 0) {
                // Would mean a group was filled while detached again; visible as a crash on open.
                LimeLog.warning("settings layout: " + unattached + " preferences ended up without a manager");
            }
            LimeLog.info("settings layout: " + moved + " preferences in "
                    + CATEGORY_KEYS.length + " categories, " + missing + " unavailable on this device");
        }

        /** Prompt for the backup password, then run the import with it. */
        private void askBackupPassword() {
            final android.widget.EditText input = new android.widget.EditText(getActivity());
            input.setHint(R.string.vplus_password_hint);
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            new android.app.AlertDialog.Builder(getActivity())
                    .setTitle(R.string.vplus_password_title)
                    .setMessage(R.string.vplus_password_message)
                    .setView(input)
                    .setPositiveButton(R.string.button_import, (dialog, which) ->
                            runImport(pendingDocument, input.getText().toString()))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        /** Import the buffered document and show the per-step report. */
        private void runImport(byte[] document, String password) {
            try (InputStream in = new java.io.ByteArrayInputStream(document)) {
                ConfigurationPort.ImportResult result =
                        ConfigurationPort.importVPlusBackup(getActivity(), in, password);
                pendingDocument = null;
                showImportReport(result.report());
            } catch (Exception e) {
                showImportReport(getString(R.string.vplus_import_failed, String.valueOf(e.getMessage())));
            }
        }

        private void showImportReport(String text) {
            new android.app.AlertDialog.Builder(getActivity())
                    .setTitle(R.string.vplus_import_title)
                    .setMessage(text)
                    // The computer list is cached by the running service, so the restored PCs
                    // only show up after the process restarts. Offer that instead of making the
                    // user discover it (and blame the import).
                    .setPositiveButton(R.string.button_restart_app, (dialog, which) -> restartApp())
                    .setNegativeButton(R.string.button_later, null)
                    .show();
        }

        private void restartApp() {
            Intent intent = getActivity().getPackageManager()
                    .getLaunchIntentForPackage(getActivity().getPackageName());
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                getActivity().startActivity(intent);
            }
            android.os.Process.killProcess(android.os.Process.myPid());
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            if (requestCode == REQUEST_IMPORT_VPLUS_BACKUP) {
                if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                    return;
                }
                Uri uri = data.getData();
                try (InputStream in = getActivity().getContentResolver().openInputStream(uri)) {
                    if (in == null) {
                        throw new IOException(getString(R.string.vplus_cannot_open));
                    }
                    final byte[] document = ConfigurationPort.readAll(in);
                    if (ConfigurationPort.looksEncrypted(document)) {
                        pendingDocument = document;
                        askBackupPassword();
                    } else {
                        runImport(document, null);
                    }
                } catch (Exception e) {
                    showImportReport(getString(R.string.vplus_import_failed, String.valueOf(e.getMessage())));
                }
                return;
            }
            super.onActivityResult(requestCode, resultCode, data);
        }

    }
}
