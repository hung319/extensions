package com.h4rs.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.h4rs.BuildConfig
import com.h4rs.TorraStreamProvider

class SettingsFragment(
    private val plugin: TorraStreamProvider,
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {

    private val res: Resources = plugin.resources ?: throw Exception("Unable to read resources")

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return inflater.inflate(res.getLayout(id), container, false)
    }

    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return res.getDrawable(id, null)
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id)
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun View?.makeTvCompatible() {
        if (this == null) return
        val outlineId = res.getIdentifier("outline", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (outlineId != 0) {
            val drawable = res.getDrawable(outlineId, null)
            if (drawable != null) background = drawable
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = getLayout("settings", inflater, container)

        // ===== PROVIDERS =====
        val providerTextView = root.findView<TextView>("providers_spinner")
        val providers = listOf(
            "YTS", "EZTV", "RARBG", "1337x", "ThePirateBay", "KickassTorrents",
            "TorrentGalaxy", "MagnetDL", "HorribleSubs", "NyaaSi", "TokyoTosho",
            "AniDex", "Rutor", "RuTracker", "Comando", "BluDV", "Torrent9",
            "ilCorSaRoNeRo", "MejorTorrent", "Wolfmax4k", "Cinecalidad", "BestTorrents"
        )
        val selectedProviders = BooleanArray(providers.size)
        sharedPref.getString("provider", "")?.split(",")?.forEach { saved ->
            val index = providers.indexOf(saved)
            if (index >= 0) selectedProviders[index] = true
        }

        val updateProviderText = {
            val selected = providers.filterIndexed { index, _ -> selectedProviders[index] }
            providerTextView.text =
                if (selected.isEmpty()) "Select Providers" else selected.joinToString(", ")
        }
        updateProviderText()

        providerTextView.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Select Providers")
                .setMultiChoiceItems(providers.toTypedArray(), selectedProviders) { _, which, isChecked ->
                    selectedProviders[which] = isChecked
                }
                .setPositiveButton("OK") { _, _ ->
                    updateProviderText()
                    sharedPref.edit {
                        putString(
                            "provider",
                            providers.filterIndexed { i, _ -> selectedProviders[i] }.joinToString(",")
                        )
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        providerTextView.makeTvCompatible()

        // ===== SORT SPINNER =====
        val sortSpinner = root.findView<Spinner>("sort_spinner")
        val sortOptions = listOf("Seeders", "Qualitysize", "Quality", "Size")
        sortSpinner.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, sortOptions).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        val savedSort = sharedPref.getString("sort", null)
        if (savedSort != null) {
            val pos = sortOptions.indexOf(savedSort)
            if (pos >= 0) sortSpinner.setSelection(pos)
        }
        sortSpinner.makeTvCompatible()

        // ===== LANGUAGES =====
        val languageTextView = root.findView<TextView>("language_spinner")
        val languages = listOf(
            "Japanese", "Russian", "Italian", "Portuguese", "Spanish", "Latino",
            "Korean", "Chinese", "Taiwanese", "French", "German", "Dutch", "Hindi",
            "Telugu", "Tamil", "Polish", "Lithuanian", "Latvian", "Estonian", "Czech",
            "Slovakian", "Slovenian", "Hungarian", "Romanian", "Bulgarian", "Serbian",
            "Croatian", "Ukrainian", "Greek", "Danish", "Finnish", "Swedish",
            "Norwegian", "Turkish", "Arabic", "Persian", "Hebrew", "Vietnamese",
            "Indonesian", "Malay", "Thai"
        )
        val selectedLanguages = BooleanArray(languages.size)
        sharedPref.getString("language", "")?.split(",")?.forEach { saved ->
            val index = languages.indexOf(saved)
            if (index >= 0) selectedLanguages[index] = true
        }

        val updateLanguageText = {
            val selected = languages.filterIndexed { index, _ -> selectedLanguages[index] }
            languageTextView.text =
                if (selected.isEmpty()) "Select Languages"
                else selected.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
        updateLanguageText()

        languageTextView.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Select Languages")
                .setMultiChoiceItems(languages.toTypedArray(), selectedLanguages) { _, which, isChecked ->
                    selectedLanguages[which] = isChecked
                }
                .setPositiveButton("OK") { _, _ ->
                    updateLanguageText()
                    sharedPref.edit {
                        putString(
                            "language",
                            languages.filterIndexed { index, _ -> selectedLanguages[index] }
                                .joinToString(",")
                        )
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        languageTextView.makeTvCompatible()

        // ===== METADATA LANGUAGE =====
        val metadataLanguageSpinner = root.findView<Spinner>("metadata_language_spinner")
        val metadataLanguageOptions = listOf(
            "aa" to "Afar",
            "ab" to "Abkhazian",
            "ae" to "Avestan",
            "af" to "Afrikaans",
            "ak" to "Akan",
            "am" to "Amharic",
            "an" to "Aragonese",
            "ar" to "Arabic",
            "as" to "Assamese",
            "av" to "Avaric",
            "ay" to "Aymara",
            "az" to "Azerbaijani",
            "ba" to "Bashkir",
            "be" to "Belarusian",
            "bg" to "Bulgarian",
            "bi" to "Bislama",
            "bm" to "Bambara",
            "bn" to "Bengali",
            "bo" to "Tibetan",
            "br" to "Breton",
            "bs" to "Bosnian",
            "ca" to "Catalan",
            "ce" to "Chechen",
            "ch" to "Chamorro",
            "cn" to "Cantonese",
            "co" to "Corsican",
            "cr" to "Cree",
            "cs" to "Czech",
            "cu" to "Slavic",
            "cv" to "Chuvash",
            "cy" to "Welsh",
            "da" to "Danish",
            "de" to "German",
            "dv" to "Divehi",
            "dz" to "Dzongkha",
            "ee" to "Ewe",
            "el" to "Greek",
            "en" to "English",
            "eo" to "Esperanto",
            "es" to "Spanish",
            "et" to "Estonian",
            "eu" to "Basque",
            "fa" to "Persian",
            "ff" to "Fulah",
            "fi" to "Finnish",
            "fj" to "Fijian",
            "fo" to "Faroese",
            "fr" to "French",
            "fy" to "Frisian",
            "ga" to "Irish",
            "gd" to "Gaelic",
            "gl" to "Galician",
            "gn" to "Guarani",
            "gu" to "Gujarati",
            "gv" to "Manx",
            "ha" to "Hausa",
            "he" to "Hebrew",
            "hi" to "Hindi",
            "ho" to "Hiri Motu",
            "hr" to "Croatian",
            "ht" to "Haitian; Haitian Creole",
            "hu" to "Hungarian",
            "hy" to "Armenian",
            "hz" to "Herero",
            "ia" to "Interlingua",
            "id" to "Indonesian",
            "ie" to "Interlingue",
            "ig" to "Igbo",
            "ii" to "Yi",
            "ik" to "Inupiaq",
            "io" to "Ido",
            "is" to "Icelandic",
            "it" to "Italian",
            "iu" to "Inuktitut",
            "ja" to "Japanese",
            "jv" to "Javanese",
            "ka" to "Georgian",
            "kg" to "Kongo",
            "ki" to "Kikuyu",
            "kj" to "Kuanyama",
            "kk" to "Kazakh",
            "kl" to "Kalaallisut",
            "km" to "Khmer",
            "kn" to "Kannada",
            "ko" to "Korean",
            "kr" to "Kanuri",
            "ks" to "Kashmiri",
            "ku" to "Kurdish",
            "kv" to "Komi",
            "kw" to "Cornish",
            "ky" to "Kirghiz",
            "la" to "Latin",
            "lb" to "Letzeburgesch",
            "lg" to "Ganda",
            "li" to "Limburgish",
            "ln" to "Lingala",
            "lo" to "Lao",
            "lt" to "Lithuanian",
            "lu" to "Luba-Katanga",
            "lv" to "Latvian",
            "mg" to "Malagasy",
            "mh" to "Marshall",
            "mi" to "Maori",
            "mk" to "Macedonian",
            "ml" to "Malayalam",
            "mn" to "Mongolian",
            "mo" to "Moldavian",
            "mr" to "Marathi",
            "ms" to "Malay",
            "mt" to "Maltese",
            "my" to "Burmese",
            "na" to "Nauru",
            "nb" to "Norwegian Bokmal",
            "nd" to "Ndebele",
            "ne" to "Nepali",
            "ng" to "Ndonga",
            "nl" to "Dutch",
            "nn" to "Norwegian Nynorsk",
            "no" to "Norwegian",
            "nr" to "Ndebele",
            "nv" to "Navajo",
            "ny" to "Chichewa; Nyanja",
            "oc" to "Occitan",
            "oj" to "Ojibwa",
            "om" to "Oromo",
            "or" to "Oriya",
            "os" to "Ossetian; Ossetic",
            "pa" to "Punjabi",
            "pi" to "Pali",
            "pl" to "Polish",
            "ps" to "Pushto",
            "pt" to "Portuguese",
            "qu" to "Quechua",
            "rm" to "Raeto-Romance",
            "rn" to "Rundi",
            "ro" to "Romanian",
            "ru" to "Russian",
            "rw" to "Kinyarwanda",
            "sa" to "Sanskrit",
            "sc" to "Sardinian",
            "sd" to "Sindhi",
            "se" to "Northern Sami",
            "sg" to "Sango",
            "sh" to "Serbo-Croatian",
            "si" to "Sinhalese",
            "sk" to "Slovak",
            "sl" to "Slovenian",
            "sm" to "Samoan",
            "sn" to "Shona",
            "so" to "Somali",
            "sq" to "Albanian",
            "sr" to "Serbian",
            "ss" to "Swati",
            "st" to "Sotho",
            "su" to "Sundanese",
            "sv" to "Swedish",
            "sw" to "Swahili",
            "ta" to "Tamil",
            "te" to "Telugu",
            "tg" to "Tajik",
            "th" to "Thai",
            "ti" to "Tigrinya",
            "tk" to "Turkmen",
            "tl" to "Tagalog",
            "tn" to "Tswana",
            "to" to "Tonga",
            "tr" to "Turkish",
            "ts" to "Tsonga",
            "tt" to "Tatar",
            "tw" to "Twi",
            "ty" to "Tahitian",
            "ug" to "Uighur",
            "uk" to "Ukrainian",
            "ur" to "Urdu",
            "uz" to "Uzbek",
            "ve" to "Venda",
            "vi" to "Vietnamese",
            "vo" to "Volapuk",
            "wa" to "Walloon",
            "wo" to "Wolof",
            "xh" to "Xhosa",
            "xx" to "No Language",
            "yi" to "Yiddish",
            "yo" to "Yoruba",
            "za" to "Zhuang",
            "zh" to "Mandarin",
            "zu" to "Zulu"
        )
        val metadataAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            metadataLanguageOptions.map { "${it.first} - ${it.second}" }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        metadataLanguageSpinner.adapter = metadataAdapter
        val savedMetadataLanguage = sharedPref.getString("metadata_language", "en")?.trim().orEmpty()
        val initialMetadataIndex = metadataLanguageOptions.indexOfFirst { it.first == savedMetadataLanguage }
        if (initialMetadataIndex >= 0) metadataLanguageSpinner.setSelection(initialMetadataIndex)
        metadataLanguageSpinner.makeTvCompatible()

        // ===== QUALITY FILTER =====
        val qualityTextView = root.findView<TextView>("quality_spinner")
        val qualities = listOf(
            "Brremux", "Hdrall", "Dolbyvision", "Dolbyvisionwithhdr",
            "Threed", "Nonthreed", "4k", "1080p", "720p", "480p",
            "Other", "Scr", "Cam", "Unknown"
        )
        val selectedQualities = BooleanArray(qualities.size)
        sharedPref.getString("qualityfilter", "")?.split(",")?.forEach { saved ->
            val index = qualities.indexOf(saved)
            if (index >= 0) selectedQualities[index] = true
        }

        val updateQualityText = {
            val selected = qualities.filterIndexed { i, _ -> selectedQualities[i] }
            qualityTextView.text =
                if (selected.isEmpty()) "Select Qualities" else selected.joinToString(", ")
        }
        updateQualityText()

        qualityTextView.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Select Qualities")
                .setMultiChoiceItems(qualities.toTypedArray(), selectedQualities) { _, which, isChecked ->
                    selectedQualities[which] = isChecked
                }
                .setPositiveButton("OK") { _, _ ->
                    updateQualityText()
                    sharedPref.edit {
                        putString(
                            "qualityfilter",
                            qualities.filterIndexed { i, _ -> selectedQualities[i] }.joinToString(",")
                        )
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        qualityTextView.makeTvCompatible()

        // ===== LIMIT =====
        val limitInput = root.findView<EditText>("limit_input")
        limitInput.setText(sharedPref.getString("limit", ""))
        limitInput.makeTvCompatible()
        limitInput.hint = "Max number of links to load (0 for unlimited)"

        // ===== SIZE =====
        val sizeInput = root.findView<EditText>("size_filter_input")
        sizeInput.setText(sharedPref.getString("sizefilter", ""))
        sizeInput.makeTvCompatible()

        // ===== LINK LIMIT =====
        val linkLimitInput = root.findView<EditText>("link_limit_input")
        linkLimitInput.setText(sharedPref.getString("link_limit", ""))
        linkLimitInput.hint = "Maximum total links to load (0 for unlimited)"
        linkLimitInput.makeTvCompatible()

        // ===== DEBRID PROVIDERS =====
        val debridSpinner = root.findView<Spinner>("debrid_provider_spinner")
        val debridProviders = listOf(
            "None", "RealDebrid", "Premiumize", "AllDebrid", "DebridLink",
            "EasyDebrid", "Offcloud", "TorBox", "TorrServer", "Put.io", "AIO Streams"
        )
        debridSpinner.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, debridProviders).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        val savedDebrid = sharedPref.getString("debrid_provider", null)
        if (savedDebrid != null) {
            val pos = debridProviders.indexOf(savedDebrid)
            if (pos >= 0) debridSpinner.setSelection(pos)
        }
        debridSpinner.makeTvCompatible()

        val debridKeyInput = root.findView<EditText>("debrid_key_input")
        debridKeyInput.setText(sharedPref.getString("debrid_key", ""))
        debridKeyInput.makeTvCompatible()

        fun updateDebridKeyHint(provider: String) {
            debridKeyInput.hint = if (provider == "TorrServer") {
                "TorrServer URL (http://127.0.0.1:8090)"
            } else {
                "Debrid API key"
            }
        }

        val initialProvider = debridSpinner.selectedItem?.toString().orEmpty()
        updateDebridKeyHint(initialProvider)

        debridSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val selected = debridProviders.getOrNull(position).orEmpty()
                updateDebridKeyHint(selected)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // ===== SAVE =====
        val saveBtn = root.findView<ImageView>("save")
        saveBtn.setImageDrawable(getDrawable("save_icon"))
        saveBtn.makeTvCompatible()
        saveBtn.setOnClickListener {
            sharedPref.edit {
                putString("provider", providers.filterIndexed { i, _ -> selectedProviders[i] }.joinToString(","))
                putString("language", languages.filterIndexed { i, _ -> selectedLanguages[i] }.joinToString(","))
                val selectedMetadataIndex = metadataLanguageSpinner.selectedItemPosition
                val selectedMetadataCode = metadataLanguageOptions.getOrNull(selectedMetadataIndex)?.first ?: "en"
                putString("metadata_language", selectedMetadataCode)
                putString("qualityfilter", qualities.filterIndexed { i, _ -> selectedQualities[i] }.joinToString(","))
                putString("sort", sortSpinner.selectedItem?.toString() ?: "")
                val limitValue = limitInput.text?.toString()?.trim().orEmpty()
                if (limitValue.isNotEmpty()) {
                    putString("limit", limitValue)
                }
                putString("sizefilter", sizeInput.text.toString())
                val linkLimitValue = linkLimitInput.text?.toString()?.trim().orEmpty()
                if (linkLimitValue.isNotEmpty()) {
                    putString("link_limit", linkLimitValue)
                }
                val selectedDebrid = debridSpinner.selectedItem?.toString() ?: ""
                putString("debrid_provider", selectedDebrid)
                putString("debrid_key", debridKeyInput.text.toString())
            }

            AlertDialog.Builder(requireContext())
                .setTitle("Restart Required")
                .setMessage("Changes have been saved. Do you want to restart the app to apply them?")
                .setPositiveButton("Yes") { _, _ ->
                    showToast("Saved and Restarting...")
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("No") { dialog, _ ->
                    showToast("Saved. Restart later to apply changes.")
                    dialog.dismiss()
                    dismiss()
                }
                .show()
        }

        // ===== RESET =====
        val resetBtn = root.findView<View>("delete_img")
        resetBtn.makeTvCompatible()
        resetBtn.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Reset")
                .setMessage("This will delete all saved settings.")
                .setPositiveButton("Reset") { _, _ ->
                    sharedPref.edit().clear().commit()
                    selectedProviders.fill(false)
                    updateProviderText()
                    selectedLanguages.fill(false)
                    updateLanguageText()
                    selectedQualities.fill(false)
                    updateQualityText()
                    sortSpinner.setSelection(0, false)
                    debridSpinner.setSelection(0, false)
                    limitInput.text.clear()
                    sizeInput.text.clear()
                    debridKeyInput.text.clear()
                    restartApp()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        return root
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {}

    private fun restartApp() {
        val context = requireContext().applicationContext
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component
        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        }
    }
}
