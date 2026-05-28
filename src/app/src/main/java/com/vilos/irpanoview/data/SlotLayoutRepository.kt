package com.vilos.irpanoview.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.slotDataStore: DataStore<Preferences> by preferencesDataStore(name = "slot_layout")

class SlotLayoutRepository(private val context: Context) {

    suspend fun readPreferredCell(deviceKey: String): Int? =
        context.slotDataStore.data.map { prefs ->
            prefs[intPreferencesKey("cell_$deviceKey")]
        }.first()

    fun preferredCellFlow(deviceKey: String): Flow<Int?> =
        context.slotDataStore.data.map { prefs ->
            prefs[intPreferencesKey("cell_$deviceKey")]
        }

    suspend fun setPreferredCell(deviceKey: String, cellIndex: Int) {
        context.slotDataStore.edit { prefs ->
            prefs[intPreferencesKey("cell_$deviceKey")] = cellIndex
        }
    }
}
