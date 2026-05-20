package com.github.valv.durinsgate

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ConfigStorage(context: Context) {
    private val prefs = context.getSharedPreferences("ssh_configs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveConfigs(configs: List<SshConfig>) {
        val json = gson.toJson(configs)
        prefs.edit().putString("configs_list", json).apply()
    }

    fun loadConfigs(): MutableList<SshConfig> {
        val json = prefs.getString("configs_list", null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<SshConfig>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }
}
