package com.magnifierglass

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.DropDownPreference
import androidx.preference.PreferenceFragmentCompat
import kotlin.math.max


class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLocale()
        setContentView(R.layout.settings_activity)
        setTitle(R.string.title_activity_settings)
        supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun applyLocale() {
        LocaleHelper.applyLocale(this)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            initPreviewResolutionWidth()
            initCameraChooser()

            findPreference<DropDownPreference>(getString(R.string.key_preference_language))
                ?.setOnPreferenceChangeListener { _, _ ->
                    activity?.recreate()
                    true
                }
        }

        private fun initCameraChooser() {
            val magnifierSurface = MagnifierGlassSurface.getInstance() ?: return
            val entryValues: MutableList<CharSequence> = ArrayList()
            val entries: MutableList<CharSequence> = ArrayList()
            var haveMain = false
            var haveSelfie = false
            val manager = context?.getSystemService(CAMERA_SERVICE) as CameraManager
            manager.cameraIdList.forEach {
                val cameraCharacteristics = manager.getCameraCharacteristics(it)
                entryValues.add(it)
                if (!haveMain && cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    entries.add(resources.getString(R.string.main_camera))
                    haveMain = true
                } else if (!haveSelfie && cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    entries.add(resources.getString(R.string.selfie_camera))
                    haveSelfie = true
                } else
                    entries.add(resources.getString(R.string.wide_or_other_camera))
            }
            val cameraIdPreference = findPreference<DropDownPreference>(resources.getString(R.string.key_preference_camera_id))
            if (cameraIdPreference != null) {
                cameraIdPreference.entries = entries.toTypedArray()
                cameraIdPreference.entryValues = entryValues.toTypedArray()
                cameraIdPreference.setValueIndex(magnifierSurface.preferredCameraId)
            }
        }

        private fun initPreviewResolutionWidth() {
            val magnifierSurface = MagnifierGlassSurface.getInstance() ?: return
            val availablePreviewWidths: Array<CharSequence>? = magnifierSurface.availablePreviewWidths
            val previewResolutionPreference = findPreference<DropDownPreference>(resources.getString(R.string.key_preference_preview_resolution))
            if (previewResolutionPreference != null && availablePreviewWidths != null) {
                previewResolutionPreference.entries = availablePreviewWidths
                previewResolutionPreference.entryValues = availablePreviewWidths
                val currentPreviewWidth = magnifierSurface.cameraPreviewWidth
                val currentIndex = max(0, availablePreviewWidths.indexOf(currentPreviewWidth.toString()))
                previewResolutionPreference.setValueIndex(currentIndex)
            }
        }

    }
}