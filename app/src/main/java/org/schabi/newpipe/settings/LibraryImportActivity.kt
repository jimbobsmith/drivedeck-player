package org.schabi.newpipe.settings

import android.app.Application
import android.os.Bundle
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.mediasync.LibraryBackupReader
import org.schabi.newpipe.mediasync.LibraryImporter
import org.schabi.newpipe.mediasync.LibraryImportRules
import org.schabi.newpipe.util.ThemeHelper
import org.schabi.newpipe.util.Localization
import java.util.concurrent.Executors

/** No installer, restart, settings restore, provider lookup or sync is triggered by import. */
class LibraryImportActivity : AppCompatActivity() {
    private lateinit var model: LibraryImportViewModel
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) model.load(uris) }
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeHelper.getSettingsThemeStyle(this)); Localization.assureCorrectAppLanguage(this); super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[LibraryImportViewModel::class.java]
        val density = resources.displayMetrics.density
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((24*density).toInt(), (16*density).toInt(), (24*density).toInt(), (24*density).toInt()) }
        fun text(value: String) = TextView(this).apply { text = value; textSize = 17f; setPadding(0, (10*density).toInt(), 0, (10*density).toInt()); body.addView(this) }
        fun button(label: Int, action: () -> Unit) = Button(this).apply { setText(label); minimumHeight = (72*density).toInt(); setOnClickListener { action() }; body.addView(this) }
        text(getString(R.string.library_import_title)).textSize = 24f
        text(getString(R.string.library_import_description))
        val choose = button(R.string.library_import_choose) { picker.launch(arrayOf("*/*")) }
        val report = text("")
        val confirm = button(R.string.library_import_confirm) { model.commit() }
        val cancel = button(R.string.cancel) { model.cancel() }
        model.state.observe(this) { state -> report.text = state.message; choose.isEnabled = !state.busy; confirm.visibility = if(state.ready) View.VISIBLE else View.GONE; confirm.isEnabled = !state.busy; cancel.isEnabled = !state.busy }
        setContentView(ScrollView(this).apply { addView(body) })
    }
}
class LibraryImportViewModel(application: Application) : AndroidViewModel(application) {
    data class State(val message: String = "", val ready: Boolean = false, val busy: Boolean = false)
    val state = MutableLiveData(State())
    private val executor = Executors.newSingleThreadExecutor()
    private var records: List<org.json.JSONObject>? = null
    fun cancel() { records = null; state.value = State(getApplication<Application>().getString(R.string.library_import_canceled)) }
    fun load(uris: List<Uri>) {
        if (state.value?.busy == true) return
        records = null; state.value = State(busy = true)
        executor.execute {
            try {
                require(uris.size <= 20) { "import_file_limit" }
                val app = getApplication<Application>(); var total = 0
                val parsed = uris.map { uri -> app.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = LibraryBackupReader.bounded(input); total += bytes.size; require(total <= LibraryImportRules.MAX_BYTES) { "import_file_limit" }; LibraryBackupReader.parse(bytes.inputStream(), app.cacheDir)
                } ?: throw IllegalArgumentException("invalid_import_file") }
                val imported = LibraryImportRules.deduplicate(parsed.flatMap { it.records })
                val changes = LibraryImporter(NewPipeDatabase.getInstance(app)).preview(imported)
                records = imported
                val summary = parsed.joinToString("\n") { it.source + ": unsupported entries " + it.unsupported + ", remote playlists omitted " + it.remotePlaylists } + "\n\n" + LibraryImportRules.counts(changes) + "\n\n" + app.getString(R.string.library_import_merge_help)
                state.postValue(State(summary, ready = true))
            } catch (_: Exception) { records = null; state.postValue(State(getApplication<Application>().getString(R.string.library_import_failed))) }
        }
    }
    fun commit() {
        val approved = records ?: return; if (state.value?.busy == true) return
        state.value = State(busy = true)
        executor.execute {
            try { val changes = LibraryImporter(NewPipeDatabase.getInstance(getApplication())).commit(approved); records = null; state.postValue(State(getApplication<Application>().getString(R.string.library_import_done) + "\n" + LibraryImportRules.counts(changes))) }
            catch (_: Exception) { state.postValue(State(getApplication<Application>().getString(R.string.library_import_failed), ready = true)) }
        }
    }
    override fun onCleared() { executor.shutdown() }
}
