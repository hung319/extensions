package com.h4rs

import android.view.*
import android.widget.*
import android.os.Bundle
import android.net.Uri
import android.content.Intent
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.*

class SyncSettingsFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {
    private fun <T : View> View.findView(name: String): T {
        val id = plugin.resources!!.getIdentifier(name, "id", "com.h4rs")
        return this.findViewById(id)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = plugin.resources!!.getIdentifier(name, "layout", "com.h4rs")
        val layout = plugin.resources!!.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    private fun getDrawable(name: String): Drawable? {
        val id = plugin.resources!!.getIdentifier(name, "drawable", "com.h4rs")
        return ResourcesCompat.getDrawable(plugin.resources!!, id, null)
    }

    private fun getString(name: String): String? {
        val id = plugin.resources!!.getIdentifier(name, "string", "com.h4rs")
        return plugin.resources!!.getString(id)
    }

    private fun View.makeTvCompatible() {
        val outlineId = plugin.resources!!.getIdentifier("outline", "drawable", "com.h4rs")
        this.background = plugin.resources!!.getDrawable(outlineId, null)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return try {
            val settings = getLayout("settings", inflater, container)
            settings.findView<TextView>("login_text").text = "Login"
            settings.findView<TextView>("group_text").text = "Made by: anhdaden"

            val loginButton = settings.findView<ImageView>("button_login")
            loginButton.setImageDrawable(getDrawable("edit_icon"))
            loginButton.makeTvCompatible()

            loginButton.setOnClickListener(object : View.OnClickListener {
                override fun onClick(v: View?) {
                    val credsView = getLayout("login", inflater, container)

                    // Get new UI elements
                    val currentDeviceIdText = credsView.findView<TextView>("current_device_id")
                    val customDeviceIdInput = credsView.findView<EditText>("custom_device_id")

                    val tokenInput = credsView.findView<EditText>("token")
                    tokenInput.setText(getKey<String>("sync_token"))
                    val prNumInput = credsView.findView<EditText>("project_num")
                    prNumInput.setText(getKey<String>("sync_project_num"))
                    val backupDevice = credsView.findView<Switch>("backup_device")
                    backupDevice.text = "Backup data to the cloud"
                    backupDevice.isChecked = getKey<String>("backup_device") == "true"
                    val restoreDevice = credsView.findView<Switch>("restore_device")
                    restoreDevice.text = "Recover data from cloud"
                    restoreDevice.isChecked = getKey<String>("restore_device") == "true"

                    // Set current values
                    currentDeviceIdText.text = getKey<String>("device_id")
                    customDeviceIdInput.setText(getKey<String>("custom_device_id"))


                    val loadingView = getLayout("loading", inflater, container)
                    val loadingDialog = AlertDialog.Builder(context ?: throw Exception("Unable to build alert dialog"))
                        .setView(loadingView)
                        .setCancelable(false)
                        .create()

                    AlertDialog.Builder(context ?: throw Exception("Unable to build alert dialog"))
                        .setTitle("Login")
                        .setView(credsView)
                        .setPositiveButton("Save", object : DialogInterface.OnClickListener {
                            override fun onClick(p0: DialogInterface, p1: Int) {
                                var token = tokenInput.text.trim().toString()
                                var prNum = prNumInput.text.toString()
                                if (token.isNullOrEmpty() || prNum.isNullOrEmpty()) {
                                    showToast("Please fill in all information")
                                } else {
                                    loadingDialog.show()
                                    // Save the custom device ID
                                    setKey("custom_device_id", customDeviceIdInput.text.toString())

                                    setKey("sync_token", token)
                                    setKey("sync_project_num", prNum)
                                    setKey("backup_device", "${backupDevice.isChecked}")
                                    setKey("restore_device", "${restoreDevice.isChecked}")

                                    // Re-run the device ID logic after saving
                                    val customId = getKey<String>("custom_device_id")
                                    if (customId.isNullOrEmpty()) {
                                        setKey("device_id", getDeviceId(context?.packageName ?: "", context!!))
                                    } else {
                                        setKey("device_id", customId)
                                    }


                                    CoroutineScope(Dispatchers.IO).launch {
                                        try {
                                            val result = ApiUtils.syncProjectDetails(context)
                                            if (result?.first == false) {
                                                loadingDialog.dismiss()
                                                showToast(result?.second)
                                            } else {
                                                loadingDialog.dismiss()
                                                dismiss()
                                                showToast(result?.second)
                                            }
                                        } catch (e: Exception) {
                                            loadingDialog.dismiss()
                                            e.printStackTrace()
                                            showToast("Error syncing: " + e.toString())
                                        }
                                    }
                                }
                            }
                        })
                        .setNegativeButton("Reset", object : DialogInterface.OnClickListener {
                            override fun onClick(p0: DialogInterface, p1: Int) {
                                setKey("sync_token", "")
                                setKey("sync_project_num", "")
                                setKey("custom_device_id", "") // Also reset custom ID
                                showToast("Credentials removed")
                                dismiss()
                            }
                        })
                        .show()
                }
            })

            val groupButton = settings.findView<ImageView>("button_group")
            groupButton.setImageDrawable(getDrawable("telegram"))
            groupButton.makeTvCompatible()

            groupButton.setOnClickListener(object : View.OnClickListener {
                override fun onClick(v: View?) {
                    val url = "https://t.me/cloudstream_extension_vn"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                }
            })

            settings
        } catch (e: Exception) {
            null
        }
    }
}