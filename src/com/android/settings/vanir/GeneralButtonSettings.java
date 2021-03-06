/*
 * Copyright (C) 2014 Vanir-Exodus
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.vanir;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.Handler;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.SearchIndexableResource;
import android.provider.Settings;

import android.view.IWindowManager;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.WindowManagerGlobal;

import com.android.settings.R;
import com.android.settings.cyanogenmod.ButtonBacklightBrightness;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.search.Indexable;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.Utils;

import org.cyanogenmod.hardware.KeyDisabler;
import com.vanir.util.DeviceUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GeneralButtonSettings extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener, Indexable {

    private static final String KEY_BUTTON_BACKLIGHT = "button_backlight";
    private static final String KEY_VOLUME_KEY_CURSOR_CONTROL = "volume_key_cursor_control";
    private static final String KEY_VOLUME_BUTTON_MUSIC_CONTROL = "volbtn_music_controls";
    private static final String KEY_SWAP_VOLUME_BUTTONS = "swap_volume_buttons";
    private static final String KEY_VOLUME_WAKE = "volume_wake_screen";
    private static final String KEY_BLUETOOTH_INPUT_SETTINGS = "bluetooth_input_settings";
    private static final String HEADSET_CONNECT_MUSIC_PLAYER = "headset_connect_player";
    private static final String DISABLE_NAV_KEYS = "disable_nav_keys";
    private static final String FORCE_DISABLE_HARDWARE_KEYS = "force_disable_hardware_keys";

    private static final String KEY_POWER_END_CALL = "power_end_call";
    private static final String CATEGORY_POWER = "power_key";
    private static final String CATEGORY_GENERAL = "category_general";
    private static final String CATEGORY_VOLUME = "volume_keys";
    private static final String CATEGORY_POWER_BUTTON = "power_key";
    private static final String CATEGORY_HEADSETHOOK = "button_headsethook";
    private static final String CATEGORY_NAVBAR = "navigation_bar";

    public static final int KEY_MASK_CAMERA = 0x20;
    public static final int KEY_MASK_VOLUME = 0x40;

    private SwitchPreference mDisableNavigationKeys;
    private CheckBoxPreference mForceDisableHardkeys;
    private PreferenceCategory mNavigationPreferencesCat;
    private CheckBoxPreference mHeadsetConnectPlayer;
    private ListPreference mVolumeKeyCursorControl;
    private CheckBoxPreference mSwapVolumeButtons;
    private CheckBoxPreference mPowerEndCall;
    private CheckBoxPreference mVolWake;

    Handler mHandler = new Handler();

    private SettingsObserver mSettingsObserver;

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = getActivity().getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.DEV_FORCE_SHOW_NAVBAR), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            ContentResolver resolver = getActivity().getContentResolver();

            boolean enabled = Settings.System.getInt(resolver,
                         Settings.System.DEV_FORCE_SHOW_NAVBAR, 0) == 1;

            if (mDisableNavigationKeys != null) {
                mDisableNavigationKeys.setChecked(enabled);
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.general_button_settings);

        final Resources res = getResources();
        final ContentResolver resolver = getActivity().getContentResolver();
        final PreferenceScreen prefScreen = getPreferenceScreen();

        final int deviceWakeKeys = getResources().getInteger(
                com.android.internal.R.integer.config_deviceHardwareWakeKeys);
        final int deviceKeys = getResources().getInteger(
                com.android.internal.R.integer.config_deviceHardwareKeys);
        final boolean hasPowerKey = KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_POWER);
        final boolean hasVolumeKeys = (deviceKeys & KEY_MASK_VOLUME) != 0;
        final boolean showVolumeWake = (deviceWakeKeys & KEY_MASK_VOLUME) != 0;

        boolean hasAnyBindableKey = false;
        final PreferenceCategory generalCategory =
                (PreferenceCategory) prefScreen.findPreference(CATEGORY_GENERAL);
        final PreferenceCategory powerCategory =
                (PreferenceCategory) prefScreen.findPreference(CATEGORY_POWER);
        final PreferenceCategory volumeCategory =
                (PreferenceCategory) prefScreen.findPreference(CATEGORY_VOLUME);

        // Force Navigation bar related options
        mNavigationPreferencesCat = (PreferenceCategory) findPreference(CATEGORY_NAVBAR);
        mDisableNavigationKeys = (SwitchPreference) findPreference(DISABLE_NAV_KEYS);
        mForceDisableHardkeys = (CheckBoxPreference) findPreference(FORCE_DISABLE_HARDWARE_KEYS);
        mForceDisableHardkeys.setChecked(Settings.System.getIntForUser(resolver,
                Settings.System.DEV_FORCE_DISABLE_HARDKEYS, 0, UserHandle.USER_CURRENT) == 1);

        final ButtonBacklightBrightness backlight =
                (ButtonBacklightBrightness) findPreference(KEY_BUTTON_BACKLIGHT);
        if (!backlight.isButtonSupported(getActivity()) &&
                !backlight.isKeyboardSupported(getActivity())) {
            prefScreen.removePreference(backlight);
        }

        // Only visible on devices that does not have a navigation bar already,
        // and don't even try unless the existing keys can be disabled
        boolean needsNavigationBar = false;
        if (KeyDisabler.isSupported()) {
            try {
                IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
                needsNavigationBar = wm.needsNavigationBar();
            } catch (RemoteException e) {
            }

            if (needsNavigationBar) {
                prefScreen.removePreference(mDisableNavigationKeys);
                prefScreen.removePreference(mForceDisableHardkeys);
                if (backlight != null) prefScreen.removePreference(backlight);
                prefScreen.removePreference(mNavigationPreferencesCat);
            }
        } else {
            prefScreen.removePreference(mDisableNavigationKeys);
            prefScreen.removePreference(mForceDisableHardkeys);
            if (backlight != null) prefScreen.removePreference(backlight);
            prefScreen.removePreference(mNavigationPreferencesCat);
        }

        // Power button ends calls.
        mPowerEndCall = (CheckBoxPreference) findPreference(KEY_POWER_END_CALL);

        if (hasPowerKey) {
            if (!Utils.isVoiceCapable(getActivity())) {
                powerCategory.removePreference(mPowerEndCall);
                mPowerEndCall = null;
                prefScreen.removePreference(powerCategory);
            }
        } else {
            prefScreen.removePreference(powerCategory);
        }

        if (Utils.hasVolumeRocker(getActivity())) {
            mVolWake = (CheckBoxPreference) findPreference(KEY_VOLUME_WAKE);
            mVolWake.setChecked(Settings.System.getInt(resolver,
                    Settings.System.VOLUME_WAKE_SCREEN, 0) == 1);
            if (!showVolumeWake) {
                volumeCategory.removePreference(findPreference(Settings.System.VOLUME_WAKE_SCREEN));
            }

            int cursorControlAction = Settings.System.getInt(getContentResolver(),
                    Settings.System.VOLUME_KEY_CURSOR_CONTROL, 0);
            mVolumeKeyCursorControl = initActionList(KEY_VOLUME_KEY_CURSOR_CONTROL,
                    cursorControlAction);

            int swapVolumeKeys = Settings.System.getInt(resolver,
                    Settings.System.SWAP_VOLUME_KEYS_ON_ROTATION, 0);
            mSwapVolumeButtons = (CheckBoxPreference)
                    prefScreen.findPreference(KEY_SWAP_VOLUME_BUTTONS);
            mSwapVolumeButtons.setChecked(swapVolumeKeys > 0);
        } else {
            prefScreen.removePreference(volumeCategory);
        }

        mHeadsetConnectPlayer = (CheckBoxPreference) findPreference(HEADSET_CONNECT_MUSIC_PLAYER);
        mHeadsetConnectPlayer.setChecked(Settings.System.getInt(resolver,
                Settings.System.HEADSET_CONNECT_PLAYER, 0) == 1);

        final PreferenceCategory powerButtonCategory =
                (PreferenceCategory) prefScreen.findPreference(CATEGORY_POWER_BUTTON);
        final boolean isTorchSupported = DeviceUtils.isPackageInstalled(getActivity(), "net.cactii.flash2");
        final boolean isCameraPresent = getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA);

        final boolean hasAnyPowerButtonOptions = isTorchSupported  || isCameraPresent /* || etc. */;
        if (!hasAnyPowerButtonOptions) {
            prefScreen.removePreference(powerButtonCategory);
            if (!Utils.isVoiceCapable(getActivity())) {
                powerButtonCategory.removePreference(mPowerEndCall);
                mPowerEndCall = null;
           }
        }
        Utils.updatePreferenceToSpecificActivityFromMetaDataOrRemove(getActivity(),
                getPreferenceScreen(), KEY_BLUETOOTH_INPUT_SETTINGS);
    }

    @Override
    public void onResume() {
        super.onResume();

        // Power button ends calls.
        if (mPowerEndCall != null) {
            final int incallPowerBehavior = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR,
                    Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR_DEFAULT);
            final boolean powerButtonEndsCall =
                    (incallPowerBehavior == Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR_HANGUP);
            mPowerEndCall.setChecked(powerButtonEndsCall);
        }

        if (mSettingsObserver == null) {
            mSettingsObserver = new SettingsObserver(mHandler);
            mSettingsObserver.observe();
            mSettingsObserver.onChange(true);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mSettingsObserver != null) {
            ContentResolver resolver = getActivity().getContentResolver();
            resolver.unregisterContentObserver(mSettingsObserver);
            mSettingsObserver = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mSettingsObserver != null) {
            ContentResolver resolver = getActivity().getContentResolver();
            resolver.unregisterContentObserver(mSettingsObserver);
            mSettingsObserver = null;
        }
    }

    private ListPreference initActionList(String key, int value) {
        ListPreference list = (ListPreference) getPreferenceScreen().findPreference(key);
        list.setValue(Integer.toString(value));
        list.setSummary(list.getEntry());
        list.setOnPreferenceChangeListener(this);
        return list;
    }

    private void handleActionListChange(ListPreference pref, Object newValue, String setting) {
        String value = (String) newValue;
        int index = pref.findIndexOfValue(value);

        pref.setSummary(pref.getEntries()[index]);
        Settings.System.putInt(getContentResolver(), setting, Integer.valueOf(value));
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mVolumeKeyCursorControl) {
            handleActionListChange(mVolumeKeyCursorControl, newValue,
                    Settings.System.VOLUME_KEY_CURSOR_CONTROL);
            return true;
        }

        return false;
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        boolean checked = false;
        if (preference instanceof CheckBoxPreference) {
            checked = ((CheckBoxPreference)preference).isChecked();
        }

        if (preference == mDisableNavigationKeys) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    writeDisableNavkeysOption(getActivity(), mDisableNavigationKeys.isChecked());
                }
            }, 1000);
            return true;

        } else if (preference == mForceDisableHardkeys) {
            boolean force = mForceDisableHardkeys.isChecked();
            Settings.System.putInt(getActivity().getContentResolver(),
                    Settings.System.DEV_FORCE_DISABLE_HARDKEYS,
                    force ? 1 : 0);
            getActivity().sendBroadcast(new Intent(
                "vanir.android.settings.TOGGLE_NAVBAR_FOR_HARDKEYS"));
            return true;

        } else if (preference == mSwapVolumeButtons) {
            int value = mSwapVolumeButtons.isChecked()
                    ? (Utils.isTablet(getActivity()) ? 2 : 1) : 0;
            Settings.System.putInt(getActivity().getContentResolver(),
                    Settings.System.SWAP_VOLUME_KEYS_ON_ROTATION, value);

        } else if (preference == mVolWake) {
            Settings.System.putInt(getActivity().getContentResolver(),
                    Settings.System.VOLUME_WAKE_SCREEN,
                    mVolWake.isChecked() ? 1 : 0);
            return true;

        } else if (preference == mHeadsetConnectPlayer) {
            Settings.System.putInt(getActivity().getContentResolver(),
                    Settings.System.HEADSET_CONNECT_PLAYER,
                    mHeadsetConnectPlayer.isChecked() ? 1 : 0);
            return true;

        } else if (preference == mPowerEndCall) {
            handleTogglePowerButtonEndsCallPreferenceClick();
            return true;
        }

        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    private static void writeDisableNavkeysOption(Context context, boolean enabled) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final ContentResolver resolver = context.getContentResolver();
        final int defaultBrightness = context.getResources().getInteger(
                com.android.internal.R.integer.config_buttonBrightnessSettingDefault);

        boolean forceDisabled = Settings.System.getIntForUser(resolver,
                Settings.System.DEV_FORCE_DISABLE_HARDKEYS, 0, UserHandle.USER_CURRENT) == 1;

        Settings.System.putInt(resolver,
                Settings.System.DEV_FORCE_SHOW_NAVBAR, enabled ? 1 : 0);
        context.sendBroadcast(new Intent("vanir.android.settings.TOGGLE_NAVBAR_FOR_HARDKEYS"));

        /* Save/restore button timeouts to disable them in softkey mode */
        Editor editor = prefs.edit();

        if (enabled) {
            int currentBrightness = Settings.System.getInt(resolver,
                    Settings.System.BUTTON_BRIGHTNESS, defaultBrightness);
            if (!prefs.contains("pre_navbar_button_backlight")) {
                editor.putInt("pre_navbar_button_backlight", currentBrightness);
            }
            if (forceDisabled) {
                Settings.System.putInt(resolver,
                        Settings.System.BUTTON_BRIGHTNESS, 0);
            }
        } else {
            if (forceDisabled) {
                Settings.System.putInt(resolver,
                        Settings.System.BUTTON_BRIGHTNESS,
                        prefs.getInt("pre_navbar_button_backlight", defaultBrightness));
                editor.remove("pre_navbar_button_backlight");
            }
        }
        editor.commit();
    }

    public static void restoreKeyDisabler(Context context) {
        if (!KeyDisabler.isSupported()) {
            return;
        }

        writeDisableNavkeysOption(context, Settings.System.getInt(context.getContentResolver(),
                Settings.System.DEV_FORCE_SHOW_NAVBAR, 0) != 0);
    }

    private static boolean isKeyDisablerSupported() {
        try {
            return KeyDisabler.isSupported();
        } catch (NoClassDefFoundError e) {
            // Hardware abstraction framework not installed
            return false;
        }
    }

    private void handleTogglePowerButtonEndsCallPreferenceClick() {
        Settings.Secure.putInt(getContentResolver(),
                Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR, (mPowerEndCall.isChecked()
                        ? Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR_HANGUP
                        : Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR_SCREEN_OFF));
    }

    public static final Indexable.SearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider() {
                @Override
                public List<SearchIndexableResource> getXmlResourcesToIndex(Context context,
                                                                            boolean enabled) {
                    ArrayList<SearchIndexableResource> result =
                            new ArrayList<SearchIndexableResource>();

                    SearchIndexableResource sir = new SearchIndexableResource(context);
                    sir.xmlResId = R.xml.general_button_settings;
                    result.add(sir);

                    return result;
                }

                @Override
                public List<String> getNonIndexableKeys(Context context) {
                    ArrayList<String> result = new ArrayList<String>();

                    Intent intent =
                            new Intent("com.cyanogenmod.action.LAUNCH_BLUETOOTH_INPUT_SETTINGS");
                    intent.setClassName("com.cyanogenmod.settings.device",
                            "com.cyanogenmod.settings.device.BluetoothInputSettings");
                    if (!Utils.doesIntentResolve(context, intent)) {
                        result.add(KEY_BLUETOOTH_INPUT_SETTINGS);
                    }

                    return result;
                }
            };
}
