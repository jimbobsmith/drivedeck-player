package org.schabi.newpipe.updates

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.graphics.Bitmap
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.settings.SettingsActivity
import org.schabi.newpipe.settings.UpdateSettingsFragment
import java.io.File
import java.security.MessageDigest

/** Opt-in synthetic emulator only. Never clears data or installs itself on a personal device. */
@RunWith(AndroidJUnit4::class)
class PlayerUpdaterRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun approved() {
        assertEquals("com.drivedeck.player.test", context.packageName)
        assertTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val permit = InstrumentationRegistry.getArguments().getString("updaterPermit") ?: error("Emulator permit required")
        assertTrue(permit.matches(Regex("[a-f0-9]{64}")))
        assertEquals(permit, File(context.noBackupFilesDir, "updater-validation.permit").readText())
    }
    private fun fixture(name: String) = File(context.filesDir, "updater-test-fixtures/$name")
    private fun manifest(file: File, version: Long = 304): DriveDeckReleaseManifest = DriveDeckReleaseManifest(
        packageName = context.packageName, versionCode = version, versionName = "0.1.0-alpha03-fixture",
        tag = "player-test-fixture-v3", commitSha = "a".repeat(40), assets = listOf(DriveDeckReleaseAsset(
            "player-universal.apk", "universal", digest(file), file.length())))
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(128 * 1024); while (true) { val count=input.read(buffer); if (count<0) break; hash.update(buffer,0,count) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    @Test fun verifyCandidatesAndDefaultOff() {
        approved()
        val manager = PlayerUpdateManager.get(context)
        assertFalse(manager.automatic); assertFalse(manager.autoDownload)
        assertNull(runBlocking { manager.installIntent() })
        val valid = fixture("valid.apk"); val expected = manifest(valid)
        assertTrue(AppUpdateVerifier.verify(context,valid,expected,expected.assets.single()) is ApkVerificationResult.Valid)
        val wrongSigner = fixture("wrong-signer.apk"); val wrong = manifest(wrongSigner)
        val result = AppUpdateVerifier.verify(context,wrongSigner,wrong,wrong.assets.single()) as ApkVerificationResult.Invalid
        assertTrue(result.reason.contains("certificate"))
        val other = fixture("other-package.apk"); val otherManifest = manifest(other)
        assertTrue((AppUpdateVerifier.verify(context,other,otherManifest,otherManifest.assets.single()) as ApkVerificationResult.Invalid).reason.contains("package"))
        val equal = fixture("current.apk"); val equalManifest = manifest(equal,204)
        assertTrue((AppUpdateVerifier.verify(context,equal,equalManifest,equalManifest.assets.single()) as ApkVerificationResult.Invalid).reason.contains("version"))
        assertTrue(AppUpdateVerifier.verify(context,valid,expected,expected.assets.single().copy(sha256="0".repeat(64))) is ApkVerificationResult.Invalid)
        assertTrue(AppUpdateVerifier.verify(context,valid,expected,expected.assets.single().copy(sizeBytes=valid.length()+1)) is ApkVerificationResult.Invalid)
        assertTrue(runCatching { androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.updates",File(context.filesDir,"outside-update-directory.apk")) }.isFailure)
    }
    @Test fun automaticJobsAndVerifiedBackgroundDownload() {
        approved()
        val valid=fixture("valid.apk"); val expected=manifest(valid)
        val payload=PlayerUpdateManager.encodeManifest(expected)
        val listing=JSONObject().put("id",1).put("tag_name",expected.tag).put("draft",false).put("prerelease",true)
            .put("assets",org.json.JSONArray().put(JSONObject().put("name","release-manifest.json").put("url","https://api.github.com/repos/jimbobsmith/drivedeck-player/releases/assets/11").put("size",payload.toByteArray().size))
                .put(JSONObject().put("name","player-universal.apk").put("url","https://api.github.com/repos/jimbobsmith/drivedeck-player/releases/assets/12").put("size",valid.length())))
        val connections=java.util.concurrent.atomic.AtomicInteger()
        val manager=PlayerUpdateManager(context, clientFactory={ GitHubReleaseClient { url ->
            connections.incrementAndGet()
            object : java.net.HttpURLConnection(url) {
                val bytes=if(url.host=="api.github.com") "[$listing]".toByteArray() else if(url.path.endsWith("release-manifest.json"))payload.toByteArray() else null
                override fun connect() { }
                override fun disconnect() { }
                override fun usingProxy()=false
                override fun getResponseCode()=200
                override fun getInputStream():java.io.InputStream=bytes?.inputStream()?:valid.inputStream()
                override fun getContentLengthLong()=bytes?.size?.toLong()?:valid.length()
                override fun getContentType()="application/octet-stream"
                override fun setRequestProperty(key:String,value:String) { assertNotEquals("Authorization",key) }
            }
        } },preferenceName="updater-runtime-background")
        assertFalse(manager.automatic)
        manager.setAutomatic(true)
        repeat(100){if(manager.state.value.phase==UpdatePhase.CHECKING)Thread.sleep(100)}
        assertEquals(UpdatePhase.AVAILABLE,manager.state.value.phase)
        val work=androidx.work.WorkManager.getInstance(context)
        val infos=work.getWorkInfosForUniqueWork(PlayerUpdateManager.CHECK_WORK).get(10,java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(infos.any{it.state==androidx.work.WorkInfo.State.ENQUEUED && it.constraints.requiredNetworkType==androidx.work.NetworkType.CONNECTED})
        manager.setAutoDownload(true)
        assertTrue(runBlocking { manager.backgroundCheck(true) })
        assertEquals(UpdatePhase.READY_TO_INSTALL,manager.state.value.phase)
        assertTrue(connections.get()>=5)
        assertNotNull(runBlocking { manager.installIntent() })
        manager.setAutomatic(false)
        val restored=PlayerUpdateManager(context,preferenceName="updater-runtime-background")
        repeat(100){if(restored.state.value.phase!=UpdatePhase.READY_TO_INSTALL)Thread.sleep(100)}
        assertEquals(UpdatePhase.READY_TO_INSTALL,restored.state.value.phase)
        assertFalse(restored.automatic)
        assertTrue(work.getWorkInfosForUniqueWork(PlayerUpdateManager.CHECK_WORK).get(10,java.util.concurrent.TimeUnit.SECONDS).none{it.state==androidx.work.WorkInfo.State.ENQUEUED})
    }
    @Test fun cancelledWorkerCanRetry() {
        approved()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var block = false
        val manager = PlayerUpdateManager(context, clientFactory = { GitHubReleaseClient { url ->
            object : java.net.HttpURLConnection(url) {
                override fun connect() { }
                override fun disconnect() { }
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getInputStream(): java.io.InputStream {
                    if (block) { entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
                    return "[]".byteInputStream()
                }
                override fun getContentLengthLong() = 2L
            }
        } }, preferenceName = "updater-runtime-cancellation")
        manager.setAutomatic(true)
        repeat(100) { if (manager.state.value.phase == UpdatePhase.CHECKING) Thread.sleep(50) }
        assertEquals(UpdatePhase.UP_TO_DATE, manager.state.value.phase)
        block = true
        runBlocking {
            val job = launch(Dispatchers.IO) { manager.backgroundCheck(false) }
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            job.cancel(); release.countDown(); job.join()
        }
        assertEquals(UpdatePhase.IDLE, manager.state.value.phase)
        block = false
        assertTrue(runBlocking { manager.backgroundCheck(false) })
        assertEquals(UpdatePhase.UP_TO_DATE, manager.state.value.phase)
        manager.setAutomatic(false)
    }
    @Test fun stagePreparedAndLibrary() {
        approved()
        val valid=fixture("valid.apk"); val expected=manifest(valid)
        assertTrue(AppUpdateVerifier.verify(context,valid,expected,expected.assets.single()) is ApkVerificationResult.Valid)
        val directory=File(context.filesDir,"updates/player_updates").apply { mkdirs() }
        valid.copyTo(File(directory,"304-player-universal.apk"),overwrite=true)
        File(directory,"prepared.json").writeText(PlayerUpdateManager.encodeManifest(expected))
        context.getSharedPreferences("updater-validation",0).edit().putString("retained","synthetic-library-marker").commit()
        val db=NewPipeDatabase.getInstance(context).openHelper.writableDatabase
        db.execSQL("INSERT INTO subscriptions(service_id,url,name,notification_mode) VALUES(0,'https://www.youtube.com/channel/UC_DD_updater_fixture','Updater validation fixture',0)")
        db.execSQL("INSERT INTO streams(uid,service_id,url,title,stream_type,duration,uploader,is_paid) VALUES(424242,0,'https://www.youtube.com/watch?v=aqz-KE-bpKQ','Updater fixture','VIDEO_STREAM',600,'Fixture',0)")
        db.execSQL("INSERT INTO stream_state(stream_id,progress_time) VALUES(424242,42000)")
        context.getSharedPreferences("player_updates",0).edit().putBoolean("automatic",false).putBoolean("auto_download",true).commit()
    }
    @Test fun settingsPreparedPortraitLandscapeAndInstallIntent() {
        approved()
        val manager=PlayerUpdateManager.get(context)
        repeat(100) { if (manager.state.value.phase!=UpdatePhase.READY_TO_INSTALL) Thread.sleep(100) }
        assertEquals(UpdatePhase.READY_TO_INSTALL,manager.state.value.phase)
        val intent=runBlocking { manager.installIntent() }!!
        assertEquals(Intent.ACTION_INSTALL_PACKAGE,intent.action)
        assertEquals("${context.packageName}.updates",intent.data!!.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0,intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        context.contentResolver.openInputStream(intent.data!!)!!.use { assertEquals(80,it.read()); assertEquals(75,it.read()) }
        ActivityScenario.launch<SettingsActivity>(Intent(context,SettingsActivity::class.java).putExtra(PlayerUpdateManager.OPEN_UPDATES,true)).use { scenario ->
            for ((name,orientation) in listOf("portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,"landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                scenario.onActivity { it.requestedOrientation=orientation }
                Thread.sleep(1000); instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val fragment=activity.supportFragmentManager.findFragmentById(R.id.settings_fragment_holder) as UpdateSettingsFragment
                    assertTrue(fragment.findPreference<Preference>("player_update_install")!!.isVisible)
                    assertFalse(fragment.findPreference<Preference>("player_update_check")!!.isEnabled)
                    fragment.scrollToPreference("player_update_install")
                }
                Thread.sleep(400); instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val fragment=activity.supportFragmentManager.findFragmentById(R.id.settings_fragment_holder) as UpdateSettingsFragment
                    val position=(fragment.listView.adapter as PreferenceGroupAdapter).getPreferenceAdapterPosition("player_update_install")
                    val row=fragment.listView.findViewHolderForAdapterPosition(position)!!.itemView
                    assertTrue(row.height>=72*context.resources.displayMetrics.density-1)
                }
                instrumentation.uiAutomation.takeScreenshot().also { bitmap -> File(context.filesDir,"updater-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
            }
        }
    }
    @Test fun assertUpgradeRetained() {
        approved()
        assertEquals(304L,AppUpdateVerifier.versionCode(context.packageManager.getPackageInfo(context.packageName,0)))
        assertEquals("synthetic-library-marker",context.getSharedPreferences("updater-validation",0).getString("retained",null))
        assertTrue(context.getSharedPreferences("player_updates",0).getBoolean("auto_download",false))
        val db=NewPipeDatabase.getInstance(context)
        db.query("SELECT name FROM subscriptions WHERE url='https://www.youtube.com/channel/UC_DD_updater_fixture'",null).use { assertTrue(it.moveToFirst()); assertEquals("Updater validation fixture",it.getString(0)) }
        db.query("SELECT progress_time FROM stream_state WHERE stream_id=424242",null).use { assertTrue(it.moveToFirst()); assertEquals(42000L,it.getLong(0)) }
        val manager=PlayerUpdateManager.get(context)
        repeat(30) { if(manager.state.value.phase==UpdatePhase.READY_TO_INSTALL)Thread.sleep(100) }
        assertNull(runBlocking { manager.installIntent() })
    }
}
